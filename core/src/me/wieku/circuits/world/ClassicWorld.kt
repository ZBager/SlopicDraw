package me.wieku.circuits.world

import com.google.common.eventbus.EventBus
import me.wieku.circuits.api.collections.Array2D
import me.wieku.circuits.api.collections.MappedArray
import me.wieku.circuits.api.element.IElement
import me.wieku.circuits.api.element.ITickable
import me.wieku.circuits.api.math.Direction
import me.wieku.circuits.api.math.Rectangle
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.api.state.StateManager
import me.wieku.circuits.api.simulation.CircuitMetadata
import me.wieku.circuits.api.world.IWorld
import me.wieku.circuits.api.world.clock.AsyncClock
import me.wieku.circuits.world.simulation.SimulationBackend
import me.wieku.circuits.world.simulation.BatchSignalDrive
import me.wieku.circuits.world.simulation.ClassicSimulation
import me.wieku.circuits.world.simulation.MAX_BATCH_TICKS
import me.wieku.circuits.world.simulation.SimulationBackends
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue

class ClassicWorld(val width: Int, val height: Int, val name: String,
		backend: SimulationBackend = SimulationBackends.create()):IWorld {
	private val manager: ClassicStateManager = ClassicStateManager(width * height)
	private val map: Array2D<IElement?> = Array2D(width, height)
	private val tickables = MappedArray<Vector2i, ITickable>(width * height)

	private val tasks = ConcurrentLinkedQueue<Runnable>()
	@Volatile private var simulation = backend
	private var topology = 0L
	private val buses = BusNetwork()
	val hasBusElements get() = buses.hasElements
	internal var metadata = CircuitMetadata()
	private var simulationClosed = false
	val simulationStatus get() = simulation.status
	val supportsPreviewBatches get() = simulation.supportsPreviewBatches
	@Volatile var previewHz = 60
		set(value) {
			require(value == 20 || value == 60)
			field = value
		}
	@Volatile var previewReadbacks = 0L
		private set
	private var unpublishedTicks = 0
	private var unpublishedFrom = 0L
	private var lastPreview: Long? = null

	@Synchronized
	fun setSimulationBackend(backend: SimulationBackend) {
		synchronizeSimulation()
		if (simulation !== backend) simulation.close()
		simulation = backend
		simulationClosed = false
	}

	@Synchronized
	fun openSimulation() {
		simulationClosed = false
	}

	@Synchronized
	fun closeSimulation() {
		synchronizeSimulation()
		simulationClosed = true
		simulation.close()
	}

	fun postInput(event: Any) = synchronized(this) {
		synchronizeSimulation()
		eventBus.post(event)
	}
	val eventBus = EventBus("buttons")

	var clock: AsyncClock? = null
		set(value) = synchronized(this) {
			if (field !== value) {
				synchronizeSimulation()
				field = value
			}
		}
	private var stopGeneration = 0L

	internal fun stopFromGate(): Boolean {
		val attached = clock ?: return false
		attached.stop()
		stopGeneration++
		return true
	}

	var entities = 0
	get() = tickables.currentElements
	private set

	@Synchronized
	override fun update(tick: Long) {
		if (simulationClosed) return
		synchronizeSimulation()
		updateTasks()
		evaluateTick(tick)
	}

	private fun evaluateTick(tick: Long) {
		simulation.evaluate(tick, topology, tickables, manager)
		buses.commit()
		manager.swap()
	}

	@Synchronized
	fun updateBatch(tick: Long, count: Int, drives: List<BatchSignalDrive> = emptyList()) {
		require(count in 1..MAX_BATCH_TICKS)
		require(drives.all { it.offset in 0 until count })
		if (simulationClosed) return
		synchronizeSimulation()
		updateTasks()
		if (simulation.evaluateBatch(count, topology, tickables, manager, drives)) {
			manager.swap()
			return
		}

		val scheduled = drives.groupBy { it.offset }
		repeat(count) { offset ->
			scheduled[offset]?.forEach { it.state.setActive(it.high) }
			evaluateTick(tick + offset)
		}
	}

	/** Advance one preview burst and return the number of ticks completed. */
	@Synchronized
	fun updatePreviewBatch(tick: Long, count: Int, now: Long = System.nanoTime(),
		running: () -> Boolean = { true }): Int {
		require(count in 1..MAX_BATCH_TICKS)
		if (simulationClosed || !running()) return 0
		updateTasks()
		val previous = lastPreview
		val publish = previous == null || now - previous >= 1_000_000_000L / previewHz
		val burst = minOf(count, simulation.previewBatchSize(topology, tickables))
		if (simulation.evaluateBatch(burst, topology, tickables, manager, emptyList(), publish)) {
			if (publish) {
				manager.swap()
				unpublishedTicks = 0
				lastPreview = now
				previewReadbacks++
			} else {
				if (unpublishedTicks == 0) unpublishedFrom = tick
				unpublishedTicks += burst
			}
			return burst
		}

		synchronizeSimulation()
		var completed = 0
		val generation = stopGeneration
		while (completed < count && running() && stopGeneration == generation) {
			evaluateTick(tick + completed)
			completed++
		}
		return completed
	}

	@Synchronized
	fun synchronizeSimulation() {
		if (unpublishedTicks == 0) return
		if (simulation.synchronizeBatch(manager)) manager.swap()
		else {
			// Unpublished bursts have no external writes or side effects. Rebuild from
			// the last host checkpoint, including gate runtime fields, if readback fails.
			repeat(unpublishedTicks) { offset ->
				ClassicSimulation.evaluate(unpublishedFrom + offset, topology, tickables, manager)
				buses.commit()
				manager.swap()
			}
		}
		unpublishedTicks = 0
		lastPreview = null
	}

	@Synchronized
	fun updateTasks() {
		val first = tasks.poll()
		if (first != null) {
			synchronizeSimulation()
			first.run()
			while (true) (tasks.poll() ?: break).run()
		}
		buses.refresh(this, topology)
	}

	override fun placeElement(position: Vector2i, name: String) {
		if(ElementRegistry.contains(name)) {
			placeElement(position, ElementRegistry.get(name)!!)
		} else {
			println("[ERROR] Element doesn't exist!")
		}
	}

	private fun placeElementNT(position: Vector2i, name: String) {
		if(ElementRegistry.contains(name)) {
			placeElementNT(position, ElementRegistry.get(name)!!)
		} else {
			println("[ERROR] Element doesn't exist!")
		}
	}

	private fun placeElement(position: Vector2i, clazz: Class<out IElement>) {
		tasks.add(Runnable {
			placeElementNT(position, clazz)
		})
	}

	private fun placeElementNT(position: Vector2i, clazz: Class<out IElement>) {
		if(!position.isInBounds(0, 0, width-1, height-1)) return
		if(map[position.x][position.y] != null) {
			if(map[position.x][position.y]!!.javaClass != clazz) {
				removeElementNT(position)
			} else return
		}
		var el:IElement = ElementRegistry.create(clazz, position)
		++topology
		map[position.x][position.y] = el
		buses.place(el)
		el.onPlace(this)
		if(el is ITickable) {
			tickables.put(position, el)
		}
	}

	@Synchronized
	fun forcePlace(x: Int, y: Int, name: String): IElement {
		if(ElementRegistry.contains(name)) {
			var position = Vector2i(x, y)
			var el:IElement = ElementRegistry.create(name, position)
			++topology
			map[position.x][position.y] = el
			buses.place(el)
			if(el is ITickable) {
				tickables.put(position, el)
			}
			return el
		} else {
			throw IllegalStateException("Element $name doesn't exist!")
		}
	}

	private var tempVec = Vector2i()
	fun clear(rectangle: Rectangle) {
		tasks.add(Runnable {
			clearNT(rectangle)
		})
	}

	private fun clearNT(rectangle: Rectangle) {
		if(rectangle.width > 2 && rectangle.height > 2) {
			lock = true
			for (x in rectangle.x+1 until rectangle.x + rectangle.width-1) {
				for (y in rectangle.y+1 until rectangle.y + rectangle.height-1) {
					removeElementNT(tempVec.set(x, y))
				}
			}
			lock = false

			for (x in rectangle.x until rectangle.x + rectangle.width) {
				removeElementNT(tempVec.set(x, rectangle.y))
				removeElementNT(tempVec.set(x, rectangle.y + rectangle.height-1))
			}

			for (y in rectangle.y until rectangle.y + rectangle.height) {
				removeElementNT(tempVec.set(rectangle.x, y))
				removeElementNT(tempVec.set(rectangle.x + rectangle.width - 1, y))
			}

		} else {
			for (x in rectangle.x until rectangle.x + rectangle.width) {
				for (y in rectangle.y until rectangle.y + rectangle.height) {
					removeElementNT(tempVec.set(x, y))
				}
			}
		}
	}

	fun fill(rectangle: Rectangle, toPlace: String) {
		tasks.add(Runnable {
			clearNT(rectangle)
			for (x in rectangle.x until rectangle.x + rectangle.width) {
				for (y in rectangle.y until rectangle.y + rectangle.height) {
					placeElementNT(Vector2i(x, y), toPlace)
				}
			}
		})
	}

	fun paste(position: Vector2i, clipboard: WorldClipboard) {
		tasks.add(Runnable {
			pasteNT(position, clipboard)
		})
	}

	fun stack(rectangle: Rectangle, direction: Direction, amount: Int) {
		tasks.add(Runnable {
			stackNT(rectangle, direction, amount)
		})
	}

	@Synchronized
	fun stackNT(rectangle: Rectangle, direction: Direction, amount: Int) {
		if (rectangle.width <= 0 || rectangle.height <= 0) return
		val clipboard = WorldClipboard.create(rectangle, this)
		pasteCopies(Vector2i(rectangle.x, rectangle.y), clipboard, direction, 1..amount)
	}

	fun stackClipboard(position: Vector2i, clipboard: WorldClipboard, direction: Direction, amount: Int) {
		val origin = position.copy()
		tasks.add(Runnable { pasteCopies(origin, clipboard, direction, 0 until amount) })
	}

	private fun pasteCopies(origin: Vector2i, clipboard: WorldClipboard, direction: Direction, copies: IntRange) {
		val position = Vector2i()
		val stepX = direction.x * clipboard.width
		val stepY = direction.y * clipboard.height
		for (copy in copies) {
			position.set(origin.x + stepX * copy, origin.y + stepY * copy)
			if (position.x >= width || position.y >= height ||
				position.x + clipboard.width <= 0 || position.y + clipboard.height <= 0) break
			pasteNT(position, clipboard)
		}
	}

	@Synchronized
	fun pasteNT(position: Vector2i, clipboard: WorldClipboard) {
		val left = maxOf(0, position.x)
		val top = maxOf(0, position.y)
		val right = minOf(width, position.x + clipboard.width)
		val bottom = minOf(height, position.y + clipboard.height)
		if (left >= right || top >= bottom) return

		clearNT(Rectangle(left, top, right, bottom))
		for (x in left until right) {
			for (y in top until bottom) {
				val cell = clipboard[x - position.x, y - position.y] ?: continue
				placeElementNT(Vector2i(x, y), cell)
			}
		}

		for (x in left until right) {
			for (y in top until bottom) {
				clipboard.applyData(x - position.x, y - position.y, map[x][y])
			}
		}
		manager.swap()
	}

	override fun removeElement(position: Vector2i) {
		tasks.add(Runnable {
			removeElementNT(position)
		})
	}

	private fun removeElementNT(position: Vector2i) {
		if(!position.isInBounds(0, 0, width-1, height-1)) return
		val element = map[position.x][position.y] ?: return
		++topology
		tickables.remove(position)
		map[position.x][position.y] = null
		buses.remove(element)
		element.onRemove(this)
	}

	override fun getElement(position: Vector2i) = if(position.isInBounds(0, 0, width - 1, height - 1)) map[position.x][position.y] else null

	private fun getNeighboursOf(position: Vector2i): Array<IElement> {
		val list = ArrayList<IElement>()
		Direction.VALID_DIRECTIONS.forEach {
			val tmpE = getElement(position + it.asVector)
			if (tmpE != null) list.add(tmpE)
		}
		return list.toTypedArray()
	}

	override fun getNeighboursOf(element: IElement): Array<IElement> = getNeighboursOf(element.getPosition())

	override fun getNeighboursOf(element: IElement, consumer: (IElement) -> Unit) = getNeighboursOf(element).forEach(consumer)

	private val stack = ArrayDeque<Vector2i>()
	private var first = true
	private var tempVector = Vector2i()
	private var lock = false
	override fun updateNeighboursOf(pos: Vector2i) {
		if(lock) return
		stack.push(pos)
		if(first) {
			first = false
			while(stack.isNotEmpty()) {
				val position = stack.pop()
				for(i in 0 until Direction.VALID_DIRECTIONS.size) {
					tempVector.set(position).add(Direction.VALID_DIRECTIONS[i].asVector)
					getElement(tempVector)?.onNeighbourChange(position, this)
				}
			}
			first = true
		}
	}

	override fun getStateManager(): StateManager = manager

	operator fun get(x:Int, y:Int) = map[x][y]
}

package me.wieku.circuits.api.simulation

import me.wieku.circuits.api.math.Axis
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.save.SaveManagers
import me.wieku.circuits.world.ClassicWorld
import me.wieku.circuits.world.ElementRegistry
import me.wieku.circuits.world.elements.bus.BusWire
import me.wieku.circuits.world.elements.bus.BusTap
import java.io.Closeable
import java.io.File

/** A headless circuit. The caller owns scheduling and may step one tick at a time. */
class Circuit private constructor(private val world: ClassicWorld) : Closeable {
	enum class Port { HORIZONTAL, VERTICAL }

	val width get() = world.width
	val height get() = world.height
	val name get() = world.name
	val backend get() = world.simulationStatus

	var metadata: CircuitMetadata
		get() = access { world.metadata }
		set(value) { access { world.metadata = value } }
	var ticks = 0L
		private set
	private var closed = false

	fun place(x: Int, y: Int, type: String) = access {
		require(x in 0 until width && y in 0 until height)
		require(ElementRegistry.contains(type)) { "Unknown element type '$type'." }
		world.placeElement(Vector2i(x, y), type)
	}

	fun erase(x: Int, y: Int) = access { world.removeElement(Vector2i(x, y)) }

	/** Apply queued topology edits without advancing time. Step also applies edits. */
	fun flushEdits() = access { world.updateTasks() }

	/** Schedule a driver write for the next commit. Other high drivers win over a low write. */
	fun drive(x: Int, y: Int, port: Port, high: Boolean) = access {
		world.updateTasks()
		val state = world.getElement(Vector2i(x, y))?.getState(port.axis())
			?: throw IllegalArgumentException("No signal at ($x, $y).")
		state.setActive(high)
	}

	/** Read committed state. Crossed wires are addressed by axis. */
	fun read(x: Int, y: Int, port: Port): Boolean? = access {
		world.getElement(Vector2i(x, y))?.getState(port.axis())?.isActive()
	}

	fun readBus(x: Int, y: Int, port: Port, bit: Int): Boolean? = access {
		(world.getElement(Vector2i(x, y)) as? BusWire)?.readBit(port.axis(), bit)
	}

	fun setBusTapBit(x: Int, y: Int, bit: Int) = access {
		world.updateTasks()
		val tap = world.getElement(Vector2i(x, y)) as? BusTap
			?: throw IllegalArgumentException("No bus tap at ($x, $y).")
		tap.bit = bit
	}

	fun step() = access {
		world.update(ticks)
		ticks++
	}

	fun save(file: File, legacy: Boolean = false) = access {
		world.updateTasks()
		SaveManagers.saveMap(world, file, legacy)
	}

	override fun close() = synchronized(world) {
		if (!closed) {
			world.closeSimulation()
			closed = true
		}
	}

	private fun Port.axis() = if (this == Port.HORIZONTAL) Axis.HORIZONTAL else Axis.VERTICAL

	private fun <T> access(block: () -> T): T = synchronized(world) {
		check(!closed) { "Circuit is closed." }
		block()
	}

	companion object {
		fun create(width: Int, height: Int, name: String): Circuit {
			require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE)
			return Circuit(ClassicWorld(width, height, name))
		}

		fun open(file: File) = Circuit(SaveManagers.loadMap(file))
	}
}

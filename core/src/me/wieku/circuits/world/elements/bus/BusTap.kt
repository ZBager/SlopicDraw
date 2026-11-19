package me.wieku.circuits.world.elements.bus

import me.wieku.circuits.api.element.BasicElement
import me.wieku.circuits.api.element.BasicWire
import me.wieku.circuits.api.element.ITickable
import me.wieku.circuits.api.element.edit.Copyable
import me.wieku.circuits.api.math.Axis
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.api.state.State
import me.wieku.circuits.api.world.IWorld
import me.wieku.circuits.save.SaveManager
import me.wieku.circuits.save.Saveable
import me.wieku.circuits.world.ClassicWorld

abstract class BusTap(position: Vector2i, val feedsBus: Boolean) :
	BasicElement(position), ITickable, Saveable, Copyable {
	var bit = 0
		set(value) {
			require(value in 0 until BUS_WIDTH) { "Bus bit must be between 0 and 7." }
			field = value
		}

	private var signal: BusSignal? = null
	private val wires = ArrayList<State>(3)

	fun simulationBus() = signal
	fun simulationWires(): List<State> = wires

	internal fun connect(world: IWorld) {
		signal = null
		wires.clear()
		var busNeighbours = 0
		world.getNeighboursOf(this) { neighbour ->
			val axis = Axis.getAxis(pos, neighbour.getPosition())
			when (neighbour) {
				is BusWire -> {
					busNeighbours++
					signal = neighbour.signals[neighbour.port(axis)]
				}
				is BasicWire -> neighbour.getState(axis)?.let { wires.add(it) }
			}
		}
		if (busNeighbours != 1) signal = null
	}

	override fun update(tick: Long) {
		val bus = signal
		if (feedsBus) {
			bus?.drive(bit, wires.any { it.isActive() })
		} else {
			val high = bus?.read(bit) == true
			for (wire in wires) wire.setActive(high)
		}
	}

	override fun onPlace(world: IWorld) {}
	override fun onRemove(world: IWorld) {}
	override fun onNeighbourChange(position: Vector2i, world: IWorld) {}
	override fun afterLoad(world: IWorld) {}
	override fun getState(axis: Axis): State? = null
	override fun setState(state: State, axis: Axis) {}
	override fun getIdleColor() = if (feedsBus) 0x007b76 else 0x95431b
	override fun getActiveColor() = if (feedsBus) 0x25dfcb else 0xffa66c
	override fun getColor() = if (signal?.read(bit) == true) getActiveColor() else getIdleColor()

	override fun load(world: ClassicWorld, manager: SaveManager) {
		bit = manager.getInteger()
	}

	override fun save(manager: SaveManager) = manager.putInteger(bit)
	override fun copyData() = hashMapOf<String, Any>("bit" to bit)
	override fun pasteData(data: HashMap<String, Any>) {
		bit = data["bit"] as Int
	}
}

class BusInput(position: Vector2i) : BusTap(position, true)
class BusOutput(position: Vector2i) : BusTap(position, false)

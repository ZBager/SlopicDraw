package me.wieku.circuits.world.elements.bus

import me.wieku.circuits.api.element.BasicElement
import me.wieku.circuits.api.math.Axis
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.api.state.State
import me.wieku.circuits.api.world.IWorld
import me.wieku.circuits.save.SaveManager
import me.wieku.circuits.save.Saveable
import me.wieku.circuits.world.ClassicWorld

internal const val BUS_WIDTH = 8
private const val BUS_MASK = (1 shl BUS_WIDTH) - 1

/** Independent Classic-style bit lanes on one connected bus. */
class BusSignal internal constructor(initial: Int) {
	var current = initial
		private set
	private var pending = initial
	private var driven = 0

	internal fun read(bit: Int) = current and (1 shl bit) != 0

	internal fun drive(bit: Int, high: Boolean) {
		val mask = 1 shl bit
		if (high) pending = pending or mask
		else if (driven and mask == 0) pending = pending and mask.inv()
		driven = driven or mask
	}

	fun restoreSimulationWord(word: Int) {
		pending = word and BUS_MASK
		commit()
	}

	internal fun commit() {
		current = pending
		driven = 0
	}
}

open class BusWire(position: Vector2i) : BasicElement(position), Saveable {
	internal open val ports get() = 1
	internal val signals = arrayOfNulls<BusSignal>(2)
	private val restored = IntArray(2)

	internal fun port(axis: Axis) = if (ports == 2 && axis == Axis.VERTICAL) 1 else 0
	internal fun bits(port: Int) = signals[port]?.current ?: restored[port]

	fun readBit(axis: Axis, bit: Int): Boolean {
		require(bit in 0 until BUS_WIDTH)
		return bits(port(axis)) and (1 shl bit) != 0
	}

	override fun onPlace(world: IWorld) {}
	override fun onRemove(world: IWorld) {}
	override fun onNeighbourChange(position: Vector2i, world: IWorld) {}
	override fun afterLoad(world: IWorld) {}
	override fun getState(axis: Axis): State? = null
	override fun setState(state: State, axis: Axis) {}

	override fun getIdleColor() = 0x125f99
	override fun getActiveColor() = 0x43b9ff
	override fun getColor() = if (bits(0) != 0 || bits(1) != 0) getActiveColor() else getIdleColor()

	override fun load(world: ClassicWorld, manager: SaveManager) {
		for (port in 0 until ports) restored[port] = manager.getInteger() and BUS_MASK
	}

	override fun save(manager: SaveManager) {
		for (port in 0 until ports) manager.putInteger(bits(port))
	}
}

class BusCross(position: Vector2i) : BusWire(position) {
	override val ports get() = 2
	override fun getIdleColor() = 0x7861be
	override fun getActiveColor() = 0xb9a0ff
}

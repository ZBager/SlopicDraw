package me.wieku.circuits.world.elements.gates

import me.wieku.circuits.api.element.BasicInput
import me.wieku.circuits.api.element.BasicWire
import me.wieku.circuits.api.element.holders.Inputs
import me.wieku.circuits.api.math.Axis
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.api.world.IWorld
import me.wieku.circuits.world.elements.io.Controller

abstract class ClockedGate(pos: Vector2i): SaveableGate(pos) {

	protected var toUpdate = true

	protected val controllers = Inputs()

	var simulationArmed: Boolean
		get() = toUpdate
		set(value) { toUpdate = value }

	fun simulationControllerCount() = controllers.size
	fun simulationController(index: Int) = controllers.array[index]!!

	override protected fun updateIO(world: IWorld) {
		inputs.clear()
		outputs.clear()
		controllers.clear()

		world.getNeighboursOf(this) {
			when(it) {
				is BasicInput -> {
					if(it is Controller) controllers += it else inputs += it
				}
				is BasicWire -> {
					outputs += it.getState(Axis.getAxis(getPosition(), it.getPosition()))!!
				}
			}
		}
	}
}

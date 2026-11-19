package me.wieku.circuits.api.element

import me.wieku.circuits.api.element.holders.Inputs
import me.wieku.circuits.api.element.holders.Outputs
import me.wieku.circuits.api.math.Axis
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.api.world.IWorld

abstract class BasicGate(pos: Vector2i): BasicElement(pos),ITickable {

	protected val inputs = Inputs()
	protected val outputs = Outputs()

	override fun onPlace(world: IWorld) {
		updateIO(world)
	}

	override fun onNeighbourChange(position: Vector2i, world: IWorld) {
		updateIO(world)
	}

	fun simulationInputCount() = inputs.size
	fun simulationInput(index: Int) = inputs.array[index]!!

	fun simulationOutputCount() = outputs.size
	fun simulationOutput(index: Int) = outputs.array[index]!!

	protected fun setOut(value: Boolean) {
		outputs.setActive(value)
	}

	protected open fun updateIO(world: IWorld) {
		inputs.clear()
		outputs.clear()

		world.getNeighboursOf(this) {
			when(it) {
				is BasicInput -> inputs += it
				is BasicWire -> {
					val state = it.getState(Axis.getAxis(getPosition(), it.getPosition()))
					if(state != null)
						outputs += state
				}
			}
		}
	}

}
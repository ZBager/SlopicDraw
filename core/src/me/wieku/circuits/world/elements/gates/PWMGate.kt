package me.wieku.circuits.world.elements.gates

import com.badlogic.gdx.math.MathUtils
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.save.SaveManager
import me.wieku.circuits.world.ClassicWorld
import java.util.HashMap

class PWMGate(pos: Vector2i): SaveableGate(pos), TimedGate {

	internal var period = 500

	internal var duty = 50

	private var counter = 0

	override var simulationCounter: Int
		get() = counter
		set(value) { counter = value }
	override val simulationLimit get() = period
	override val simulationOnTicks get() = period * duty / 100

	override fun update(tick: Long) {
		var calc = inputs.isActive()

		if(calc) {
			state!!.setActiveU(false)
			counter = 0
		} else {
			counter += 1
			counter = MathUtils.clamp(counter, 0, period)

			val ticks = simulationOnTicks

			if(counter <= ticks) {
				if(!state!!.isActive())
					state!!.setActiveU(true)
			} else {
				if(state!!.isActive())
					state!!.setActiveU(false)
			}

			if (counter == period) {
				counter = 0
			}
		}

		setOut(state!!.isActiveD())
	}

	override fun getIdleColor(): Int = 0xAA00FF

	override fun getActiveColor(): Int = 0xD500F9

	override fun load(world: ClassicWorld, manager: SaveManager) {
		super.load(world, manager)
		period = manager.getInteger()
		duty = manager.getInteger()
		counter = manager.getInteger()
	}

	override fun save(manager: SaveManager) {
		super.save(manager)
		manager.putInteger(period)
		manager.putInteger(duty)
		manager.putInteger(counter)
	}

	override fun copyData(): HashMap<String, Any> {
		val map =  super.copyData()
		map.put("period", period)
		map.put("duty", duty)
		map.put("counter", counter)
		return map
	}

	override fun pasteData(data: HashMap<String, Any>) {
		super.pasteData(data)
		period = data["period"] as Int
		duty = data["duty"] as Int
		counter = data["counter"] as Int
	}

}

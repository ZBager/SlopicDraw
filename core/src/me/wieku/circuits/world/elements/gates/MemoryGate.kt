package me.wieku.circuits.world.elements.gates

import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.save.SaveManager
import me.wieku.circuits.world.ClassicWorld
import kotlin.collections.HashMap

class MemoryGate(pos: Vector2i): ClockedGate(pos) {

	override fun update(tick: Long) {
		var calc = controllers.isActive()

		if(calc) {
			if(toUpdate) {
				var calc2 = inputs.isActive()
				state!!.setActiveU(calc2)
				toUpdate = false
			}
		} else {
			toUpdate = true
		}

		setOut(state!!.isActiveD())
	}

	override fun getIdleColor(): Int = 0x37474F

	override fun getActiveColor(): Int = 0x455A64

	override fun load(world: ClassicWorld, manager: SaveManager) {
		super.load(world, manager)
		toUpdate = manager.getBoolean()
	}

	override fun save(manager: SaveManager) {
		super.save(manager)
		manager.putBoolean(toUpdate)
	}

	override fun copyData(): HashMap<String, Any> {
		val map = super.copyData()
		map.put("toUpdate", toUpdate)
		return map
	}

	override fun pasteData(data: HashMap<String, Any>) {
		super.pasteData(data)
		toUpdate = data["toUpdate"] as Boolean
	}

}

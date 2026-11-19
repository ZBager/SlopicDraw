package me.wieku.circuits.world.elements.gates

import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.api.world.IWorld
import me.wieku.circuits.save.SaveManager
import me.wieku.circuits.world.ClassicWorld

class StopGate(pos: Vector2i): SaveableGate(pos) {

	internal var enabled = true

	private var toUpdate = true
	private var world: ClassicWorld? = null

	var simulationArmed: Boolean
		get() = toUpdate
		set(value) { toUpdate = value }

	fun simulationCanStop() = enabled && world?.clock != null

	override fun update(tick: Long) {
		if (inputs.isActive()) {
			if (toUpdate) {
				if (enabled && world!!.stopFromGate()) state!!.setActiveU(true)
				toUpdate = false
			}
		} else {
			toUpdate = true
			state!!.setActiveU(false)
		}

		setOut(state!!.isActiveD())
	}

	override fun getIdleColor(): Int = 0xBF360C

	override fun getActiveColor(): Int = 0xD84315

	override fun onPlace(world: IWorld) {
		this.world = world as ClassicWorld
		super.onPlace(world)
	}

	override fun load(world: ClassicWorld, manager: SaveManager) {
		super.load(world, manager)
		toUpdate = manager.getBoolean()
	}

	override fun save(manager: SaveManager) {
		super.save(manager)
		manager.putBoolean(toUpdate)
	}

	override fun afterLoad(world: IWorld) {
		super.afterLoad(world)
		this.world = world as ClassicWorld
	}

	override fun copyData(): HashMap<String, Any> {
		val map =  super.copyData()
		map.put("toUpdate", toUpdate)
		return map
	}

	override fun pasteData(data: HashMap<String, Any>) {
		super.pasteData(data)
		toUpdate = data["toUpdate"] as Boolean
	}

}
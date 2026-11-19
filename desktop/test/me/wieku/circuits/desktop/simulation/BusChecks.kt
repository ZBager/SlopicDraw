package me.wieku.circuits.desktop.simulation

import me.wieku.circuits.api.math.Axis
import me.wieku.circuits.api.math.Rectangle
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.save.SaveManagers
import me.wieku.circuits.world.ClassicWorld
import me.wieku.circuits.world.WorldClipboard
import me.wieku.circuits.world.elements.bus.BusTap
import me.wieku.circuits.world.elements.bus.BusWire
import me.wieku.circuits.world.simulation.ClassicSimulation
import java.io.IOException

internal fun verifyBusParity(software: Boolean) {
	val cpu = ClassicWorld(24, 18, "Bus", ClassicSimulation)
	val gpu = ClassicWorld(24, 18, "Bus", TerniSimulation(software))
	val dir = createTempDir(prefix = "logicdraw-bus-")

	fun ClassicWorld.place(x: Int, y: Int, type: String) = placeElement(Vector2i(x, y), type)
	fun ClassicWorld.bit(x: Int, y: Int, axis: Axis, bit: Int) = (this[x, y] as BusWire).readBit(axis, bit)
	fun ClassicWorld.drive(x: Int, y: Int, high: Boolean) = this[x, y]!!.getState(Axis.HORIZONTAL)!!.setActive(high)

	try {
		for (world in listOf(cpu, gpu)) {
			world.place(1, 4, "wire")
			world.place(2, 4, "bus_in")
			for (x in 3..7) world.place(x, 4, if (x == 5) "bus_cross" else "bus")
			world.place(8, 4, "bus_out")
			world.place(9, 4, "wire")
			world.place(10, 4, "input")
			world.place(11, 4, "or") // Vulkan still evaluates this gate.
			world.place(3, 3, "bus_in")
			world.place(3, 2, "wire")
			world.place(5, 3, "bus")
			world.place(5, 2, "bus")
			world.place(5, 1, "bus_in")
			world.place(4, 1, "wire")
			world.place(5, 5, "bus")
			world.place(5, 6, "bus_out")
			world.place(5, 7, "wire")
			world.updateTasks()
			for ((x, y) in listOf(2 to 4, 3 to 3, 8 to 4, 5 to 1, 5 to 6)) (world[x, y] as BusTap).bit = 7
			world.drive(1, 4, true)
			world.drive(3, 2, false)
			world.drive(4, 1, false)
			world.update(0)
			check(!world.bit(5, 4, Axis.HORIZONTAL, 7))
			world.update(1)
			check(world.bit(5, 4, Axis.HORIZONTAL, 7))
			check(!world.bit(5, 4, Axis.VERTICAL, 7) && !world.bit(5, 4, Axis.HORIZONTAL, 0))
			check(!world[9, 4]!!.getState(Axis.HORIZONTAL)!!.isActive())
			world.update(2)
			check(world[9, 4]!!.getState(Axis.HORIZONTAL)!!.isActive())
			check(!world[11, 4]!!.getState(Axis.HORIZONTAL)!!.isActive())
			world.update(3)
			check(world[11, 4]!!.getState(Axis.HORIZONTAL)!!.isActive())

			// Disconnect the output side and preserve its last committed bus state.
			world.removeElement(Vector2i(6, 4))
			world.updateTasks()
			check(world.bit(7, 4, Axis.HORIZONTAL, 7))
			world.place(6, 4, "bus")
			world.updateTasks()
			check(world.bit(6, 4, Axis.HORIZONTAL, 7))

			// Clipboard transformations carry tap settings and rebuild the rotated crossing.
			val copy = WorldClipboard.create(Rectangle(1, 1, 12, 8), world).rotateRight()
			world.paste(Vector2i(13, 1), copy)
			world.updateTasks()
			check((world[16, 2] as BusTap).bit == 7)
			val file = dir.resolve(if (world === gpu) "gpu.ldmap" else "cpu.ldmap")
			SaveManagers.saveMap(world, file)
			val restored = SaveManagers.loadMap(file)
			check(restored.bit(5, 4, Axis.HORIZONTAL, 7))
			check(!restored.bit(5, 4, Axis.VERTICAL, 7))
			check((restored[8, 4] as BusTap).bit == 7)
			check(SaveManagers.loadMapThumbnail(file).pixels.any { it != 0x606060ff })
			try {
				SaveManagers.saveMap(world, file, legacy = true)
				error("Legacy save accepted a bus.")
			} catch (expected: IOException) {
				check(SaveManagers.loadMap(file).hasBusElements)
			}
		}

		for (tick in 4L..28L) {
			for (world in listOf(cpu, gpu)) {
				world.drive(1, 4, tick % 3 == 0L)
				world.drive(3, 2, tick % 5 == 0L)
				world.drive(4, 1, tick % 2 == 0L)
				if (tick == 11L) world.removeElement(Vector2i(5, 3))
				if (tick == 13L) world.place(5, 3, "bus")
				world.updateBatch(tick, 1)
			}
			check(gpu.simulationStatus.contains("(batched)")) { gpu.simulationStatus }
			check(cpu.getStateManager().input.contentEquals(gpu.getStateManager().input))
			for (axis in listOf(Axis.HORIZONTAL, Axis.VERTICAL)) {
				for (bit in 0..7) check(cpu.bit(5, 4, axis, bit) == gpu.bit(5, 4, axis, bit))
			}
		}
		println("Bus tick latency, drivers, crossings, clipboard, editing, v3 and Terni parity passed.")
	} finally {
		cpu.closeSimulation()
		gpu.closeSimulation()
		dir.deleteRecursively()
	}
}

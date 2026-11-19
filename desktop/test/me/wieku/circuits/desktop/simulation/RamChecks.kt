package me.wieku.circuits.desktop.simulation

import me.wieku.circuits.api.math.Axis
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.world.ClassicWorld
import me.wieku.circuits.world.elements.gates.RamGate
import me.wieku.circuits.world.simulation.ClassicSimulation

internal fun verifyResidentRam(software: Boolean, mode: String) {
	val cpu = ClassicWorld(290, 12, "RAM parity", ClassicSimulation)
	val gpu = ClassicWorld(290, 12, "RAM parity", TerniSimulation(software))
	try {
		for (world in listOf(cpu, gpu)) {
			fun place(x: Int, y: Int, type: String) = world.placeElement(Vector2i(x, y), type)
			place(6, 5, "ram")
			place(5, 5, "input")
			place(4, 5, "wire")
			place(6, 4, "controller")
			place(6, 3, "wire")
			place(7, 5, "wire")
			when (mode) {
				"partitioned" -> repeat(257) { place(20 + it, 9, "or") }
				"multi-workgroup" -> {
					for (x in 8..276) place(x, 5, "wire")
					repeat(257) { place(20 + it, 6, "or") }
				}
			}
			world.updateTasks()
		}
		var tick = 0L
		var fallbackTicks = 0
		fun step(control: Boolean, data: Boolean) {
			for (world in listOf(cpu, gpu)) {
				world[4, 5]!!.getState(Axis.HORIZONTAL)!!.setActiveU(data)
				world[6, 3]!!.getState(Axis.VERTICAL)!!.setActiveU(control)
				world.updateBatch(tick, 1)
			}
			if (!gpu.simulationStatus.contains(mode)) fallbackTicks++
			checkState(cpu, gpu, tick)
			check(save(cpu).contentEquals(save(gpu))) { "RAM protocol or data diverged at tick $tick" }
			tick++
		}
		fun command(value: Long, bits: Int, response: Int = 2) {
			for (bit in 0 until bits) step(true, ((value ushr bit) and 1L) != 0L)
			repeat(response + 2) { step(false, false) }
		}

		command(0x0b, 9) // Resize to 32 bytes inside the resident allocation.
		command(0x5b, 7) // Select four-byte chunks.
		command(1L or (0xdeadbeefL shl 7) or (1L shl 2), 39)
		val memory = IntArray(16)
		val ram = gpu[6, 5] as RamGate
		check(ram.packSimulationRam(memory, 0, 32))
		check(memory[8] == 0xadbeef00.toInt() && memory[9] == 0xde)
		command(2, 6, 35) // Read the same four bytes back.
		command(0x7b, 7) // Invalid chunk size leaves the previous setting in place.
		command(0x4b, 9) // Resize to 128 bytes through Classic fallback.
		check(ram.simulationMemorySize() == 128)
		check(fallbackTicks == 1) { "Expected a single resize fallback, saw $fallbackTicks" }
		command(0x13, 6) // Select segment 1.
		command(0, 67, 35) // Long command exercises the wrapped bit counter.
		command(0x0b, 9) // Shrink and discard the old segment.
		command(0x4b, 9) // Reuse the grown allocation and zero the restored bytes.
		check(fallbackTicks == 1) { "The grown allocation should handle repeated resizing" }

		// Start a 256-byte resize, publish mid-command, then leave one successful
		// burst unpublished before the responding tick requests a Classic replay.
		for (bit in 0 until 9) step(true, ((0x6b shr bit) and 1) != 0)
		for (world in listOf(cpu, gpu)) {
			world[4, 5]!!.getState(Axis.HORIZONTAL)!!.setActiveU(false)
			world[6, 3]!!.getState(Axis.VERTICAL)!!.setActiveU(false)
		}
		repeat(3) { burst ->
			cpu.updateBatch(tick, 1)
			check(gpu.updatePreviewBatch(tick, 1, burst.toLong()) == 1)
			tick++
		}
		check(!gpu.simulationStatus.contains(mode)) { "Expected the unpublished resize to fall back" }
		check(ram.simulationMemorySize() == 256)
		check(gpu.previewReadbacks == 1L)
		checkState(cpu, gpu, tick - 1)
		check(save(cpu).contentEquals(save(gpu)))

		for (burst in 0 until 4) {
			cpu.updateBatch(tick, 5)
			check(gpu.updatePreviewBatch(tick, 5, burst.toLong()) == 5)
			tick += 5
		}
		check(gpu.previewReadbacks == 2L)
		gpu.synchronizeSimulation()
		checkState(cpu, gpu, tick - 1)
		check(save(cpu).contentEquals(save(gpu)))
		gpu.setSimulationBackend(ClassicSimulation)
		cpu.update(tick)
		gpu.update(tick)
		checkState(cpu, gpu, tick)
	} finally {
		cpu.closeSimulation()
		gpu.closeSimulation()
	}
	println("Resident RAM command, resize, and checkpoint parity passed ($mode).")
}

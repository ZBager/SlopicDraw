package me.wieku.circuits.desktop.simulation

import me.wieku.circuits.api.math.Axis
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.api.world.clock.AsyncClock
import me.wieku.circuits.api.world.clock.Updatable
import me.wieku.circuits.world.ClassicWorld
import me.wieku.circuits.world.elements.gates.DelayGate
import me.wieku.circuits.world.simulation.ClassicSimulation
import me.wieku.circuits.world.simulation.SimulationBackend
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun stopBoard(backend: SimulationBackend, mode: String): ClassicWorld {
	val world = ClassicWorld(290, 12, "Stop parity", backend)
	fun place(x: Int, y: Int, type: String) = world.placeElement(Vector2i(x, y), type)
	place(3, 3, "wire")
	place(4, 3, "input")
	place(5, 3, "delay")
	place(6, 3, "wire")
	place(7, 3, "input")
	place(8, 3, "debug_stop")
	place(9, 3, "wire")
	if (mode.isNotEmpty()) place(3, 7, "ram") // Exercise the shared replay flag when a RAM arena is present.
	when (mode) {
		"partitioned" -> repeat(257) { place(20 + it, 9, "or") }
		"multi-workgroup" -> {
			for (x in 10..276) place(x, 3, "wire")
			repeat(257) { place(20 + it, 4, "or") }
		}
	}
	world.updateTasks()
	val delay = world[5, 3] as DelayGate
	delay.pasteData(delay.copyData().apply { put("delay", 7) })
	world[3, 3]!!.getState(Axis.HORIZONTAL)!!.setActiveU(true)
	return world
}

private fun ClassicWorld.stopOutput() = this[9, 3]!!.getState(Axis.HORIZONTAL)!!.isActive()

internal fun verifyResidentStop(software: Boolean, mode: String) {
	for (attachClock in listOf(false, true)) {
		val cpu = stopBoard(ClassicSimulation, mode)
		val gpu = stopBoard(TerniSimulation(software), mode)
		try {
			for (world in listOf(cpu, gpu)) {
				if (attachClock) world.clock = AsyncClock(object : Updatable.ByTick {
					override fun update(value: Long) = world.update(value)
				}, 60)
				check(world.updatePreviewBatch(0, 2, 0) == 2)
				check(world.updatePreviewBatch(2, 2, 1) == 2)
			}
			check(gpu.simulationStatus.contains(mode)) { gpu.simulationStatus }
			val expected = if (attachClock) 5 else 16
			check(cpu.updatePreviewBatch(4, 16, 2) == expected)
			check(gpu.updatePreviewBatch(4, 16, 2) == expected)
			gpu.synchronizeSimulation()
			checkState(cpu, gpu, (3 + expected).toLong())
			check(save(cpu).contentEquals(save(gpu)))
			check(gpu.stopOutput() == attachClock)
			// The input is still high. After resuming the edge latch suppresses a second Stop.
			val next = (4 + expected).toLong()
			check(cpu.updatePreviewBatch(next, 3, 3) == 3)
			check(gpu.updatePreviewBatch(next, 3, 3) == 3)
			gpu.synchronizeSimulation()
			checkState(cpu, gpu, next + 2)
			check(save(cpu).contentEquals(save(gpu)))
			if (attachClock) {
				for (world in listOf(cpu, gpu)) {
					val source = world[3, 3]!!.getState(Axis.HORIZONTAL)!!
					source.setActiveU(false)
					world.updateBatch(next + 3, 12)
					check(!world.stopOutput())
					source.setActiveU(true)
					check(world.updatePreviewBatch(next + 15, 16, 4) == 9)
				}
				checkState(cpu, gpu, next + 23)
			}
		} finally {
			cpu.closeSimulation()
			gpu.closeSimulation()
		}
	}
	println("Resident Stop parity and exact burst boundary passed ($mode).")
}

internal fun verifyStopClock(software: Boolean) {
	val world = stopBoard(TerniSimulation(software), "")
	val stopped = CountDownLatch(1)
	val stepped = CountDownLatch(1)
	var completed = 0
	var nextTick = -1L
	val clock = AsyncClock(object : Updatable.ByTickBatch {
		override fun ticksPerCall(tickRate: Int) = 32

		override fun advance(tick: Long, count: Int, running: () -> Boolean): Int {
			completed = world.updatePreviewBatch(tick, count, running = running)
			stopped.countDown()
			return completed
		}

		override fun update(value: Long) {
			nextTick = value
			world.update(value)
			stepped.countDown()
		}
	}, 60)
	world.clock = clock
	try {
		clock.start()
		check(stopped.await(15, TimeUnit.SECONDS))
		check(!clock.isRunning())
		check(completed == 9 && world.stopOutput())
		clock.step()
		check(stepped.await(15, TimeUnit.SECONDS))
		check(nextTick == 9L) { "Clock stepped at tick $nextTick after Stop" }
	} finally {
		clock.stop()
		world.closeSimulation()
		AsyncClock.dispose()
	}
}

package me.wieku.circuits.desktop.simulation

import me.wieku.circuits.api.math.Axis
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.save.SaveManagers
import me.wieku.circuits.world.ClassicWorld
import me.wieku.circuits.world.simulation.BatchSignalDrive
import me.wieku.circuits.world.simulation.ClassicSimulation
import me.wieku.circuits.world.simulation.SimulationBackend
import java.util.Locale

// A NOR feeds its own input through one electrical net. A neighboring OR drives low
// on the same net, so each batch exercises both feedback and multiple drivers.
private fun feedbackBoard(gates: Int, backend: SimulationBackend, active: Boolean = true,
	connected: Boolean = false): ClassicWorld {
	require(gates > 0 && gates % 2 == 0)
	val columns = minOf(500, gates / 2)
	val rows = (gates / 2 + columns - 1) / columns
	val world = ClassicWorld(columns * 6, rows * 5, "Batch feedback", backend)
	for (pair in 0 until gates / 2) {
		val x = pair % columns * 6
		val y = pair / columns * 5
		world.placeElement(Vector2i(x + 1, y + 1), if (active) "nor" else "or")
		world.placeElement(Vector2i(x + 2, y + 1), "input")
		world.placeElement(Vector2i(x + 1, y + 2), "wire")
		world.placeElement(Vector2i(x + 2, y + 2), "wire")
		world.placeElement(Vector2i(x + 3, y + 2), "or")
		if (pair % 4096 == 4095) world.updateTasks()
	}
	if (connected) {
		// A shared low-driven net ties all the feedback pairs into one component.
		val right = columns * 6 - 1
		for (row in 0 until rows) {
			for (x in 3..right) world.placeElement(Vector2i(x, row * 5 + 3), "wire")
			if (row > 0) for (y in 0..3) {
				world.placeElement(Vector2i(right, row * 5 + y - 1), "wire")
			}
			world.updateTasks()
		}
	}
	world.updateTasks()
	return world
}

internal fun verifyBatches(software: Boolean) {
	val classic = feedbackBoard(200, ClassicSimulation)
	val terni = feedbackBoard(200, TerniSimulation(software))
	try {
		// Every prefix starts at the same snapshot, so all intermediate states are checked.
		for (count in (1..64).toList() + listOf(65, 127, 256)) {
			for (world in listOf(classic, terni)) {
				val states = world.getStateManager()
				states.input.fill(0)
				states.output.fill(0)
				states.used.fill(0)
				val first = world[1, 2]!!.getState(Axis.HORIZONTAL)!!
				val second = world[7, 2]!!.getState(Axis.HORIZONTAL)!!
				first.setActive(true) // Already pending and marked driven at the first boundary.
				second.setActiveU(true) // Pending but still eligible for a low driver.
				val writes = listOf(
					BatchSignalDrive(0, second, false),
					BatchSignalDrive(7, first, true),
					BatchSignalDrive(7, first, false),
					BatchSignalDrive(31, second, true)
				).filter { it.offset < count }
				world.updateBatch(0, count, writes)
			}
			check(terni.simulationStatus.contains("(batched)")) { terni.simulationStatus }
			val expected = classic.getStateManager()
			val actual = terni.getStateManager()
			check(expected.input.contentEquals(actual.input)) { "Current state differs at prefix $count." }
			check(expected.output.contentEquals(actual.output)) { "Pending state differs at prefix $count." }
			check(expected.used.contentEquals(actual.used)) { "Driver state differs at prefix $count." }
		}
		classic.update(256)
		terni.update(256)
		check(classic.getStateManager().input.contentEquals(terni.getStateManager().input))
		// Replacing a gate invalidates both native plans and forces ordered mixed execution.
		for (world in listOf(classic, terni)) {
			world.placeElement(Vector2i(1, 1), "delay")
			world.updateBatch(257, 16)
		}
		check(!terni.simulationStatus.contains("(batched)"))
		check(classic.getStateManager().input.contentEquals(terni.getStateManager().input))
		println("Single-workgroup batch parity passed for feedback, shared and scheduled drivers, and topology edits.")
	} finally {
		classic.closeSimulation()
		terni.closeSimulation()
	}
}

internal fun verifyPreviewCadence(software: Boolean, gates: Int = 200, connected: Boolean = false) {
	for ((hz, expectedReadbacks) in listOf(20 to 3L, 60 to 8L)) {
		val classic = feedbackBoard(gates, ClassicSimulation, connected = connected)
		val terni = feedbackBoard(gates, TerniSimulation(software), connected = connected)
		val directory = createTempDir(prefix = "logicdraw-preview-")
		terni.previewHz = hz
		try {
			for (tick in 0L..120L) {
				val previous = terni.previewReadbacks
				classic.update(tick)
				check(terni.updatePreviewBatch(tick, 1, tick * 1_000_000L) == 1)
				check(terni.simulationStatus.contains(when {
					connected -> "multi-workgroup"
					gates > 256 -> "partitioned"
					else -> "(batched)"
				}))
				val host = terni.getStateManager()
				if (terni.previewReadbacks != previous) {
					check(host.input.contentEquals(classic.getStateManager().input)) {
						"Preview snapshot differs at $hz Hz, tick $tick."
					}
				}
			}
			check(terni.previewReadbacks == expectedReadbacks) {
				"$hz Hz published ${terni.previewReadbacks} snapshots."
			}

			// Save consumes the unpublished suffix, and manual step advances exactly once.
			val saved = directory.resolve("preview.ldmap")
			SaveManagers.saveMap(terni, saved)
			val restored = SaveManagers.loadMap(saved)
			check(restored.getStateManager().input.contentEquals(classic.getStateManager().input))
			classic.update(121)
			terni.update(121)
			check(terni.getStateManager().input.contentEquals(classic.getStateManager().input))

			if (connected) {
				terni.updateBatch(122, 17)
				check(terni.simulationStatus.contains("multi-workgroup"))
				classic.updateBatch(122, 17)
				check(terni.getStateManager().input.contentEquals(classic.getStateManager().input))
			}

			// A live edit must synchronize before reallocating wire states.
			val editStart = if (connected) 139L else 122L
			for (tick in editStart until editStart + 5) {
				classic.update(tick)
				terni.updatePreviewBatch(tick, 1, tick * 1_000_000L)
			}
			for (world in listOf(classic, terni)) {
				world.removeElement(Vector2i(1, 2))
				world.updateTasks()
				world.update(editStart + 5)
			}
			check(terni.getStateManager().input.contentEquals(classic.getStateManager().input))
		} finally {
			classic.closeSimulation()
			terni.closeSimulation()
			directory.deleteRecursively()
		}
	}
	println("Resident batches publish at 20/60 Hz and synchronize at save, step, and topology boundaries.")
}

private data class BatchBenchmarkCase(val name: String, val size: Int, val previewHz: Int? = null)

internal fun benchmarkBatches(software: Boolean, samples: Int) {
	runBatchBenchmark(200, software, samples, listOf(
		BatchBenchmarkCase("Classic", 1),
		BatchBenchmarkCase("Terni", 1),
		BatchBenchmarkCase("Terni", 16),
		BatchBenchmarkCase("Terni", 64),
		BatchBenchmarkCase("Terni", 256),
		BatchBenchmarkCase("Terni resident 20 Hz", 256, 20),
		BatchBenchmarkCase("Terni resident 60 Hz", 256, 60)
	))
}

internal fun benchmarkResident(sizes: List<Int>, software: Boolean, samples: Int, connected: Boolean) {
	require(sizes.isNotEmpty() && sizes.distinct().size == sizes.size)
	for (gates in sizes) {
		require(gates > 0 && gates % 2 == 0)
		for (active in listOf(true, false)) {
			for (accelerated in listOf(false, true)) {
				val backend = if (accelerated) TerniSimulation(software) else ClassicSimulation
				val world = feedbackBoard(gates, backend, active, connected)
				try {
					world.previewHz = 60
					val warmup = if (accelerated) 300 else if (gates <= 256) 50_000 else 30
					val calls = if (gates > 256) minOf(samples, 200) else samples
					var tick = 0L
					fun run() {
						tick += if (accelerated) world.updatePreviewBatch(tick, 256) else {
							world.update(tick)
							1
						}
					}
					repeat(warmup) { run() }
					world.synchronizeSimulation()
					val firstTick = tick
					val previousPreviews = world.previewReadbacks
					val start = System.nanoTime()
					repeat(calls) { run() }
					world.synchronizeSimulation()
					val elapsed = System.nanoTime() - start
					val ticks = tick - firstTick
					val previews = world.previewReadbacks - previousPreviews
					if (accelerated) {
						check(world.simulationStatus.contains("(batched)")) { world.simulationStatus }
						if (connected && gates > 256)
							check(world.simulationStatus.contains("multi-workgroup")) { world.simulationStatus }
						check(previews > 0)
					}
					val expected = active && tick % 2L == 1L
					check(world[1, 2]!!.getState(Axis.HORIZONTAL)!!.isActive() == expected)
					println(String.format(Locale.ROOT,
						"sweep,%d,%s,%s,%.3f,%d,%.9f,%.3f,%d",
						gates, if (active) "active" else "idle", if (accelerated) "Terni" else "Classic",
						ticks.toDouble() / calls, ticks, elapsed / 1_000_000.0 / ticks, ticks * 1e9 / elapsed, previews))
				} finally {
					world.closeSimulation()
				}
			}
		}
	}
}

private fun runBatchBenchmark(gates: Int, software: Boolean, samples: Int, cases: List<BatchBenchmarkCase>) {
	require(samples > 0)
	for (case in cases) {
		val size = case.size
		val warmup = 30
		val world = feedbackBoard(gates, if (case.name == "Classic") ClassicSimulation else TerniSimulation(software))
		try {
			case.previewHz?.let { world.previewHz = it }
			fun run(batch: Int) {
				val tick = batch.toLong() * size
				if (case.previewHz != null) {
					check(world.updatePreviewBatch(tick, size) == size)
				} else if (size == 1) world.update(tick)
				else world.updateBatch(tick, size)
			}
			repeat(warmup) { run(it) }
			if (size > 1) check(world.simulationStatus.contains("(batched)")) { world.simulationStatus }
			else if (case.name == "Terni") check(world.simulationStatus.startsWith("Terni ")) { world.simulationStatus }
			val times = LongArray(samples)
			val previousPreviews = world.previewReadbacks
			val wallStart = System.nanoTime()
			for (sample in times.indices) {
				val start = System.nanoTime()
				run(warmup + sample)
				times[sample] = System.nanoTime() - start
			}
			world.synchronizeSimulation()
			val duration = System.nanoTime() - wallStart
			if (size > 1) check(world.simulationStatus.contains("(batched)")) { world.simulationStatus }
			check(world[1, 2]!!.getState(Axis.HORIZONTAL)!!.isActive() == ((warmup.toLong() + samples) * size % 2L == 1L))
			times.sort()
			val mean = duration.toDouble() / samples
			val p95 = times[(times.size - 1) * 95 / 100]
			println(String.format(Locale.ROOT,
				"%d gates, %s, batch %d: %.3f us/tick, %.0f completed TPS, %.3f us p95 batch latency, %d scheduled previews",
				gates, case.name, size, mean / size / 1000.0, size * 1_000_000_000.0 / mean, p95 / 1000.0,
				world.previewReadbacks - previousPreviews))
		} finally {
			world.closeSimulation()
		}
	}
}

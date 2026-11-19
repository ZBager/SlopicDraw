package me.wieku.circuits.desktop.simulation

import me.wieku.circuits.api.math.Axis
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.input.event.KeyDownEvent
import me.wieku.circuits.input.event.KeyUpEvent
import me.wieku.circuits.world.ClassicWorld
import me.wieku.circuits.world.elements.gates.KeyGate
import me.wieku.circuits.world.simulation.BatchSignalDrive
import me.wieku.circuits.world.simulation.ClassicSimulation
import me.wieku.circuits.world.simulation.SimulationBackend
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream

private fun ioBoard(backend: SimulationBackend, type: String, mode: String): ClassicWorld {
	val world = ClassicWorld(290, 12, type, backend)
	fun place(x: Int, y: Int, name: String) = world.placeElement(Vector2i(x, y), name)
	for (x in listOf(5, 15)) {
		place(x, 4, type)
		place(x - 1, 4, "input")
		place(x - 2, 4, "wire")
		place(x + 1, 4, "wire")
	}
	when (mode) {
		"partitioned" -> repeat(257) { place(20 + it, 9, "or") }
		"multi-workgroup" -> {
			for (x in 17..276) place(x, 4, "wire")
			repeat(257) { place(20 + it, 5, "or") }
		}
	}
	world.updateTasks()
	return world
}

private fun ClassicWorld.signal(x: Int) = this[x, 4]!!.getState(Axis.HORIZONTAL)!!

internal fun verifyResidentKeyboard(software: Boolean, mode: String) {
	val cpu = ioBoard(ClassicSimulation, "key", mode)
	val gpu = ioBoard(TerniSimulation(software), "key", mode)
	val worlds = listOf(cpu, gpu)
	val key = (cpu[5, 4] as KeyGate).keycode
	var tick = 0L
	try {
		fun advance(count: Int = 2) {
			for (world in worlds) check(world.updatePreviewBatch(tick, count, tick) == count)
			tick += count
		}
		fun post(down: Boolean) {
			for (world in worlds) world.postInput(if (down) KeyDownEvent(world, key) else KeyUpEvent(world, key))
			checkState(cpu, gpu, tick)
			check((cpu[5, 4] as KeyGate).locked == (gpu[5, 4] as KeyGate).locked)
		}
		advance()
		check(gpu.simulationStatus.contains(mode)) { gpu.simulationStatus }
		post(true)
		advance()
		post(false)
		check(gpu.signal(6).isActive())
		advance()
		post(false)
		check(!gpu.signal(6).isActive())
		for (world in worlds) (world[5, 4] as KeyGate).bistable = true
		post(true)
		advance()
		post(false)
		advance()
		post(false)
		check(gpu.signal(6).isActive())
		post(true)
		advance()
		post(false)
		check(!gpu.signal(6).isActive())

		for (world in worlds) world.signal(3).setActiveU(true)
		advance(1) // Commit the external drive; the lock takes effect on the next tick.
		val previews = gpu.previewReadbacks
		advance(2)
		check(gpu.previewReadbacks == previews)
		post(true) // Synchronize the unpublished lock before delivering the event.
		check((gpu[5, 4] as KeyGate).locked)
		advance()
		post(false)
		check(!gpu.signal(6).isActive())
		for (world in worlds) world.signal(3).setActiveU(false)
		advance(2)
		post(true)
		check(!(gpu[5, 4] as KeyGate).locked)
		advance()
		post(false)
		check(gpu.signal(6).isActive())
	} finally {
		for (world in worlds) world.closeSimulation()
	}
	println("Resident Keyboard event and lock parity passed ($mode).")
}

internal fun verifyResidentSerial(software: Boolean, mode: String) {
	val originalIn = System.`in`
	val originalOut = System.out
	fun run(backend: SimulationBackend): Pair<List<String>, List<ByteArray>> {
		val world = ioBoard(backend, "serialio", mode)
		val trace = ArrayList<String>()
		val snapshots = ArrayList<ByteArray>()
		val input = object : ByteArrayInputStream(byteArrayOf(0xa5.toByte(), 0x80.toByte())) {
			override fun available() = super.available().also { trace.add("available $it") }
			override fun read() = super.read().also { trace.add("read $it") }
		}
		val output = object : PrintStream(ByteArrayOutputStream()) {
			override fun print(c: Char) { trace.add("print $c") }
			override fun flush() { trace.add("flush") }
		}
		var tick = 0L
		var resident = 0
		try {
			System.setIn(input)
			System.setOut(output)
			fun advance(first: Boolean, second: Boolean) {
				world.signal(3).setActiveU(first)
				world.signal(13).setActiveU(second)
				world.updateBatch(tick++, 1)
				if (world.simulationStatus.contains(mode)) resident++
				snapshots.add(save(world))
			}
			advance(true, true)
			advance(true, true)
			for (bit in 7 downTo 0) advance((65 shr bit) and 1 != 0, (66 shr bit) and 1 != 0)
			advance(false, false)
			advance(true, true)
			advance(false, false)
			advance(false, false)
			repeat(10) { advance(false, false) }

			if (mode == "(batched)") {
				val drives = listOf(
					BatchSignalDrive(0, world.signal(3), true), BatchSignalDrive(0, world.signal(13), true),
					BatchSignalDrive(1, world.signal(3), false), BatchSignalDrive(1, world.signal(13), false))
				world.updateBatch(tick, 12, drives)
				snapshots.add(save(world))
			} else {
				advance(true, true)
				repeat(11) { advance(false, false) }
			}
			if (backend is TerniSimulation) check(resident > 10) { "Serial never stayed resident" }
			return trace to snapshots
		} finally {
			System.setIn(originalIn)
			System.setOut(originalOut)
			world.closeSimulation()
		}
	}
	val (classicTrace, classicStates) = run(ClassicSimulation)
	val (terniTrace, terniStates) = run(TerniSimulation(software))
	check(classicTrace == listOf("print A", "flush", "print B", "flush", "available 2", "read 165",
		"available 1", "read 128", "available 0", "available 0")) { classicTrace }
	check(terniTrace == classicTrace) { terniTrace }
	check(terniStates.size == classicStates.size)
	for (i in classicStates.indices) check(terniStates[i].contentEquals(classicStates[i])) { "Serial state differed at checkpoint $i" }
	println("Resident Serial I/O protocol and ordered stream parity passed ($mode).")
}

package me.wieku.circuits.desktop.simulation

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.backends.lwjgl.LwjglApplication
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration
import com.kotcrab.vis.ui.VisUI
import me.wieku.circuits.api.math.Axis
import me.wieku.circuits.api.math.Rectangle
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.api.world.clock.AsyncClock
import me.wieku.circuits.render.screens.Editor
import me.wieku.circuits.render.utils.FontManager
import me.wieku.circuits.save.SaveManager
import me.wieku.circuits.save.SaveManagers
import me.wieku.circuits.world.ClassicWorld
import me.wieku.circuits.world.WorldClipboard
import me.wieku.circuits.world.elements.gates.DelayGate
import me.wieku.circuits.world.elements.io.Input
import me.wieku.circuits.world.simulation.ClassicSimulation
import me.wieku.circuits.world.simulation.SimulationBackend
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val basic = arrayOf("and", "nand", "or", "nor", "xor", "xnor")
private val software = java.lang.Boolean.getBoolean("logicdraw.terni.software")

private fun ClassicWorld.place(x: Int, y: Int, name: String) = placeElement(Vector2i(x, y), name)

private fun checkState(cpu: ClassicWorld, gpu: ClassicWorld, tick: Long) {
	val a = cpu.getStateManager()
	val b = gpu.getStateManager()
	check(a.input.contentEquals(b.input)) { "Current state diverged at tick $tick" }
	check(a.output.contentEquals(b.output)) { "Pending state diverged at tick $tick" }
	check(a.used.contentEquals(b.used)) { "Driver state diverged at tick $tick" }
	check(a.usedNodes == b.usedNodes)
}

private fun save(world: ClassicWorld): ByteArray {
	val out = ByteArrayOutputStream()
	val manager: SaveManager = SaveManagers.getSaveManager(2)
	manager.saveMap(world, DataOutputStream(out))
	return out.toByteArray()
}

private fun load(bytes: ByteArray) = SaveManagers.getSaveManager(2).loadMap(DataInputStream(ByteArrayInputStream(bytes)))

private fun fixture(world: ClassicWorld) {
	with(world) {
		// Multiple drivers on a 96-cell net. Crosses carry a separate vertical signal.
		for (x in 2..97) place(x, 4, if (x == 32) "cross" else "wire")
		place(1, 4, "nor")
		place(98, 4, "or")
		place(32, 3, "wire")
		place(32, 2, "key")
		place(32, 5, "input")
		place(32, 6, "nand")
		place(31, 6, "wire")
		place(31, 5, "input") // Feedback reaches the same gate on the following tick.
		place(30, 4, "controller")
		place(30, 5, "memory")
		place(29, 5, "input")
		place(28, 5, "delay")
		place(28, 6, "wire")
		place(28, 7, "input")
		place(28, 8, "tflipflop")
		place(27, 8, "wire")
		place(26, 8, "input")
		place(25, 8, "pwm")
		place(35, 5, "input")
		place(35, 6, "nor") // A low driver drives a second wire via an OR.
		place(36, 6, "wire")
		place(36, 7, "input")
		place(35, 7, "or")
		updateTasks()
		val delay = this[28, 5] as DelayGate
		delay.pasteData(delay.copyData().apply { put("delay", 3) })
	}
}

private fun randomized(seed: Long, world: ClassicWorld) {
	val random = Random(seed)
	with(world) {
		for (y in 12..28 step 4) {
			for (x in 2..96 step 4) {
				place(x, y, basic[random.nextInt(basic.size)])
				place(x, y + 1, "input")
				place(x, y + 2, "wire")
				place(x, y - 1, "wire")
				place(x + 1, y, "input")
				place(x + 2, y, "wire")
			}
		}
		updateTasks()
		for (y in 12..28 step 4) {
			for (x in 2..96 step 4) {
				val input = this[x, y + 1] as Input
				input.pasteData(input.copyData().apply { put("inverted", random.nextBoolean()) })
			}
		}
	}
}

private fun verify() {
	val cpu = ClassicWorld(104, 34, "parity", ClassicSimulation)
	val terni = TerniSimulation(software, checkOrder = true)
	val gpu = ClassicWorld(104, 34, "parity", terni)
	try {
		for (world in listOf(cpu, gpu)) {
			fixture(world)
			randomized(0x1D2026, world)
		}
		val drives = Random(701)
		for (tick in 0L until 144L) {
			val key = drives.nextBoolean()
			val data = drives.nextBoolean()
			for (world in listOf(cpu, gpu)) {
				world[32, 2]!!.getState(Axis.VERTICAL)!!.setActiveU(key)
				val manager = world.getStateManager()
				val shared = world[29, 4]!!.getState(Axis.HORIZONTAL)!!
				manager[shared.id] = data
				// Force live configuration changes and a split/rejoin of a common net.
				if (tick == 13L || tick == 45L) world.removeElement(Vector2i(40, 4))
				if (tick == 14L || tick == 46L) world.place(40, 4, "wire")
				if (tick == 72L) {
					val clipboard = WorldClipboard.create(Rectangle(25, 5, 34, 9), world)
					world.paste(Vector2i(45, 5), clipboard)
				}
				if (tick == 56L) {
					val input = world[32, 5] as Input
					input.pasteData(input.copyData().apply { put("inverted", true) })
				}
				world.update(tick)
			}
			check(gpu.simulationStatus.startsWith("Terni ")) { gpu.simulationStatus }
			checkState(cpu, gpu, tick)
			// Compare saved edge flags, delay and PWM counters and all persisted data.
			check(save(cpu).contentEquals(save(gpu))) { "Serialized runtime differs at tick $tick" }
		}
		val bytes = save(cpu)
		val reloadedCpu = load(bytes)
		val reloadedGpu = load(bytes)
		reloadedGpu.setSimulationBackend(TerniSimulation(software))
		try {
			for (tick in 144L until 160L) {
				reloadedCpu.update(tick)
				reloadedGpu.update(tick)
				check(reloadedGpu.simulationStatus.startsWith("Terni "))
				checkState(reloadedCpu, reloadedGpu, tick)
			}
			reloadedGpu.setSimulationBackend(ClassicSimulation)
			reloadedGpu.update(160)
			reloadedCpu.update(160)
			checkState(reloadedCpu, reloadedGpu, 160)
		} finally {
			reloadedGpu.closeSimulation()
		}
		println("Parity passed through editing, mixed circuits, save/load and backend switching. ${gpu.simulationStatus}")
		println(terni.nativeReport)
	} finally {
		gpu.closeSimulation()
	}
}

private fun verifyReduction() {
	val cpu = ClassicWorld(50, 20, "reduction", ClassicSimulation)
	val gpu = ClassicWorld(50, 20, "reduction", TerniSimulation(software, checkOrder = true))
	try {
		for (world in listOf(cpu, gpu)) {
			// Every 4-input truth table. Mirror empty inputs on a second row.
			for (i in basic.indices) {
				val x = 4 + i * 8
				world.place(x, 4, basic[i])
				world.place(x, 12, basic[i])
				for (dir in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
					world.place(x + dir.first, 4 + dir.second, "input")
					world.place(x + 2 * dir.first, 4 + 2 * dir.second, "wire")
				}
			}
			world.place(2, 16, "nor")
			for (x in 3..9) world.place(x, 16, "wire")
			world.place(10, 16, "or")
			world.place(2, 17, "input")
			world.place(2, 18, "wire")
			world.place(0, 0, "wire") // Undriven state must survive a tick.
			world.updateTasks()
		}
		for (tick in 0..32) {
			for (world in listOf(cpu, gpu)) {
				for (i in basic.indices) {
					val x = 4 + i * 8
					val pins = arrayOf(x + 2 to 4, x - 2 to 4, x to 6, x to 2)
					for ((pin, pos) in pins.withIndex()) {
						val state = world[pos.first, pos.second]!!.getState(Axis.HORIZONTAL)!!
						state.setActiveU(((tick shr pin) and 1) != 0)
					}
				}
				world[2, 18]!!.getState(Axis.VERTICAL)!!.setActiveU(tick % 4 < 2)
				world[0, 0]!!.getState(Axis.HORIZONTAL)!!.setActiveU(true)
				if (tick == 8) world[5, 16]!!.getState(Axis.HORIZONTAL)!!.setActive(true)
				if (tick == 21) world.removeElement(Vector2i(8, 16))
				if (tick == 24) world.place(8, 16, "wire")
				world.update(tick.toLong())
			}
			check(gpu.simulationStatus.contains("(net reduction)")) { gpu.simulationStatus }
			checkState(cpu, gpu, tick.toLong())
		}
		println("Net reduction parity passed for complete truth tables, idle nets, and shared drivers.")
	} finally {
		gpu.closeSimulation()
	}
}

private fun verifySequentialEdges() {
	val cpu = ClassicWorld(32, 10, "edges", ClassicSimulation)
	val gpu = ClassicWorld(32, 10, "edges", TerniSimulation(software))
	val gates = intArrayOf(4, 12, 20)
	val previous = BooleanArray(gates.size)
	val changes = IntArray(gates.size)
	try {
		for (world in listOf(cpu, gpu)) {
			for ((i, name) in arrayOf("tflipflop", "memory", "delay").withIndex()) {
				val x = gates[i]
				world.place(x, 4, name)
				world.place(x, 3, "input")
				world.place(x, 2, "wire")
				world.place(x + 1, 4, "wire")
				if (i < 2) {
					world.place(x - 1, 4, "controller")
					world.place(x - 2, 4, "wire")
				}
			}
			world.place(29, 7, "nor")
			world.updateTasks()
			val delay = world[20, 4] as DelayGate
			delay.pasteData(delay.copyData().apply { put("delay", 3) })
		}
		for (tick in 0L until 48L) {
			val phase = (tick % 8).toInt()
			for (world in listOf(cpu, gpu)) {
				world[4, 2]!!.getState(Axis.VERTICAL)!!.setActiveU(phase in 1..4)
				world[2, 4]!!.getState(Axis.HORIZONTAL)!!.setActiveU(phase == 4)
				world[10, 4]!!.getState(Axis.HORIZONTAL)!!.setActiveU(phase in 2..4)
				world[12, 2]!!.getState(Axis.VERTICAL)!!.setActiveU(tick % 16 < 8)
				world[20, 2]!!.getState(Axis.VERTICAL)!!.setActiveU(phase in 1..4)
				world.update(tick)
			}
			check(gpu.simulationStatus.startsWith("Terni "))
			checkState(cpu, gpu, tick)
			check(save(cpu).contentEquals(save(gpu)))
			for ((i, x) in gates.withIndex()) {
				val high = gpu[x + 1, 4]!!.getState(Axis.HORIZONTAL)!!.isActive()
				if (previous[i] != high) changes[i]++
				previous[i] = high
			}
		}
		check(changes.all { it >= 4 }) { "Sequential fixture did not exercise every output" }
		println("Repeated T flip-flop edges and reset, D sampling, and delay transitions matched Classic.")
	} finally {
		gpu.closeSimulation()
	}
}

private fun verifyLegacySave(file: File) {
	val cpu = SaveManagers.loadMap(file)
	val gpu = SaveManagers.loadMap(file)
	gpu.setSimulationBackend(TerniSimulation(software, checkOrder = true))
	try {
		for (tick in 0L until 32L) {
			cpu.update(tick)
			gpu.update(tick)
			check(gpu.simulationStatus.startsWith("Terni ")) { gpu.simulationStatus }
			checkState(cpu, gpu, tick)
		}
		println("Loaded and compared the repository's v1 map.")
	} finally {
		gpu.closeSimulation()
	}
}

private fun verifyFallback() {
	val originalPath = System.getProperty("logicdraw.terni.library")
	val cpu = ClassicWorld(4, 4, "fallback", ClassicSimulation)
	val fallback = ClassicWorld(4, 4, "fallback", TerniSimulation(software))
	try {
		System.setProperty("logicdraw.terni.library", "missing-logicdraw-terni-library")
		for (world in listOf(cpu, fallback)) {
			world.place(1, 1, "nor")
			world.place(1, 2, "wire")
			world.update(0)
		}
		check(fallback.simulationStatus.startsWith("Classic (Terni fallback:"))
		checkState(cpu, fallback, 0)
	} finally {
		if (originalPath == null) System.clearProperty("logicdraw.terni.library")
		else System.setProperty("logicdraw.terni.library", originalPath)
		fallback.closeSimulation()
	}
}

private fun nanosToMs(value: Double) = value / 1_000_000.0

private fun populatedWorld(size: Int, accelerated: Boolean): Triple<ClassicWorld, IntArray, SimulationBackend> {
	val backend = if (accelerated) TerniSimulation(software) else ClassicSimulation
	val columns = minOf(size, 500)
	val rows = (size + columns - 1) / columns
	val world = ClassicWorld(columns * 4, rows * 2, "benchmark", backend)
	for (i in 0 until size) {
		val x = i % columns * 4
		val y = i / columns * 2
		world.place(x, y, basic[i % basic.size])
		world.place(x + 1, y, "input")
		world.place(x + 2, y, "wire")
		world.place(x, y + 1, "wire")
		if (i % 4096 == 4095) world.updateTasks()
	}
	world.updateTasks()
	val drives = IntArray(size) {
		world[it % columns * 4 + 2, it / columns * 2]!!.getState(Axis.HORIZONTAL)!!.id
	}
	return Triple(world, drives, backend)
}

private fun benchmark(size: Int, ticks: Int, active: Boolean, accelerated: Boolean) {
	val (world, drives, backend) = populatedWorld(size, accelerated)
	try {
		val states = world.getStateManager()
		val start = System.nanoTime()
		world.update(0)
		val initialize = nanosToMs((System.nanoTime() - start).toDouble())
		if (accelerated) check(world.simulationStatus.startsWith("Terni ")) { world.simulationStatus }
		val measured = LongArray(ticks)
		for (i in 1..(30 + ticks)) {
			if (active) {
				val bit = (i and 1).toByte()
				for (net in drives) states.output[net] = bit
			}
			val before = System.nanoTime()
			world.update(i.toLong())
			if (i > 30) measured[i - 31] = System.nanoTime() - before
		}
		measured.sort()
		val p95 = measured[(ticks - 1) * 95 / 100]
		println(String.format(Locale.ROOT,
			"%d gates %s %s: first tick %.3f ms, mean %.3f ms, p95 %.3f ms, %s",
			size, if (active) "active" else "idle", if (accelerated) "Terni" else "Classic",
			initialize, nanosToMs(measured.average()), nanosToMs(p95.toDouble()), world.simulationStatus))
		if (backend is TerniSimulation) println(backend.nativeReport)
	} finally {
		world.closeSimulation()
	}
}

private fun renderBenchmark(size: Int, accelerated: Boolean, seconds: Int) {
	require(size > 0 && seconds > 0)
	val (world, _, _) = populatedWorld(size, accelerated)
	val config = LwjglApplicationConfiguration()
	config.width = 1024
	config.height = 768
	config.vSyncEnabled = false
	config.forceExit = false
	config.foregroundFPS = 0
	config.backgroundFPS = 0
	LwjglApplicationConfiguration.disableAudio = true
	val done = CountDownLatch(1)
	var status = ""
	LwjglApplication(object : ApplicationAdapter() {
		private lateinit var editor: Editor
		private var sampleStart = 0L
		private var frames = 0
		private var tps = 0.0
		private var tpsSamples = 0
		private var previousSecond = 0L

		override fun create() {
			println("OpenGL renderer: " + Gdx.gl.glGetString(GL20.GL_RENDERER))
			FontManager.init()
			VisUI.load()
			editor = Editor(world)
			editor.show()
			editor.resize(Gdx.graphics.width, Gdx.graphics.height)
			sampleStart = System.nanoTime() + 3_000_000_000L
		}

		override fun render() {
			editor.render(Gdx.graphics.deltaTime)
			val elapsed = System.nanoTime() - sampleStart
			if (elapsed < 0) return
			frames++
			val second = elapsed / 1_000_000_000L
			if (second > previousSecond) {
				previousSecond = second
				tps += world.clock!!.getTPS().toDouble()
				tpsSamples++
			}
			if (second >= seconds) {
				status = world.simulationStatus
				editor.hide()
				println(String.format(Locale.ROOT,
					"%d gates rendering at 1024x768: %.1f FPS, %.1f TPS, %s",
					size, frames * 1_000_000_000.0 / elapsed, tps / tpsSamples, status))
				Gdx.app.exit()
			}
		}

		override fun dispose() {
			editor.dispose()
			VisUI.dispose()
			AsyncClock.dispose()
			done.countDown()
		}
	}, config)
	check(done.await(seconds.toLong() + 45, TimeUnit.SECONDS)) { "Editor benchmark did not finish" }
	if (accelerated) check(status.startsWith("Terni ")) { status }
}

fun main(args: Array<String>) {
	if (args.isNotEmpty() && args[0] == "render") {
		renderBenchmark(args[1].toInt(), args[2] == "terni", args[3].toInt())
	} else if (args.isNotEmpty() && args[0] == "benchmark") {
		val ticks = args[2].toInt()
		require(ticks > 0)
		for (size in args[1].split(',').map { it.toInt() }) {
			require(size > 0)
			for (active in listOf(false, true)) {
				benchmark(size, ticks, active, false)
				benchmark(size, ticks, active, true)
			}
		}
	} else {
		verify()
		verifyReduction()
		verifySequentialEdges()
		verifyFallback()
		if (args.isNotEmpty()) verifyLegacySave(File(args[0]))
	}
}

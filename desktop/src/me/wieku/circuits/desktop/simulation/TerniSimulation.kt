package me.wieku.circuits.desktop.simulation

import me.wieku.circuits.api.collections.MappedArray
import me.wieku.circuits.api.element.ITickable
import me.wieku.circuits.api.math.Axis
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.api.state.State
import me.wieku.circuits.api.state.StateManager
import me.wieku.circuits.world.elements.gates.*
import me.wieku.circuits.world.elements.io.Controller
import me.wieku.circuits.world.elements.io.Input
import me.wieku.circuits.world.simulation.ClassicSimulation
import me.wieku.circuits.world.simulation.BatchSignalDrive
import me.wieku.circuits.world.simulation.SimulationBackend
import java.io.File
import java.util.IdentityHashMap
import java.util.Locale

internal object TerniNative {
	fun load() {
		val path = System.getProperty("logicdraw.terni.library")
		if (path == null) System.loadLibrary("logicdraw_terni")
		else System.load(File(path).absolutePath)
	}

	external fun create(gates: IntArray, inputNets: IntArray, netCount: Int,
		writes: IntArray, resultCount: Int, allowSoftware: Boolean): Long
	external fun createResidentBatch(gates: IntArray, inputPins: IntArray, writes: IntArray,
		stateCount: Int, partitions: IntArray, groups: Int, allowSoftware: Boolean): Long
	external fun evaluateBatch(handle: Long, initial: IntArray, commands: IntArray, results: IntArray,
		resume: Boolean, publish: Boolean): Double
	external fun readBatch(handle: Long, results: IntArray)
	external fun describe(handle: Long): String
	external fun report(handle: Long): String
	external fun evaluate(handle: Long, signals: IntArray, results: IntArray, checkOrder: Boolean): Double
	external fun destroy(handle: Long)
}

internal class TerniSimulation(private val allowSoftware: Boolean, private val checkOrder: Boolean = false) : SimulationBackend {
	private var revision = -1L
	private var circuit: Circuit? = null
	private var failure: String? = null

	@Volatile
	override var status = "Classic (Terni pending)"
		private set

	val nativeReport: String get() = circuit?.let {
		String.format(Locale.ROOT, "%.3f ms native initialization, %s", it.initializeMs, TerniNative.report(it.handle))
	} ?: status

	override fun evaluate(tick: Long, topology: Long, gates: MappedArray<Vector2i, ITickable>, states: StateManager) {
		var compiled: Circuit? = null
		if (failure == null) {
			try {
				compiled = prepare(topology, gates)
				if (compiled != null) compiled.dispatch(states, checkOrder)
			} catch (error: LinkageError) {
				fail(error)
				compiled = null
			} catch (error: Exception) {
				fail(error)
				compiled = null
			}
		}
		// Only GPU setup and dispatch can fall back. Applying results may run user-visible
		// side effects, so the Classic tick must never be retried after that starts.
		if (compiled == null) ClassicSimulation.evaluate(tick, topology, gates, states)
		else {
			compiled.apply(tick, gates)
			if (!compiled.reported || compiled.samples == 120) status = compiled.status()
			if (compiled.samples == 120) compiled.resetTiming()
		}
	}

	override val supportsPreviewBatches get() = true

	override fun previewBatchSize(topology: Long, gates: MappedArray<Vector2i, ITickable>): Int {
		val batch = if (revision == topology) circuit?.batch else null
		return batch?.previewTicks ?: if (gates.currentElements <= 256) 256 else 8
	}

	override fun evaluateBatch(count: Int, topology: Long, gates: MappedArray<Vector2i, ITickable>,
		states: StateManager, drives: List<BatchSignalDrive>, publish: Boolean): Boolean {
		if (failure != null) return false
		val compiled = try {
			prepare(topology, gates)
		} catch (error: LinkageError) {
			fail(error)
			return false
		} catch (error: Exception) {
			fail(error)
			return false
		} ?: return false
		val batch = compiled.batch ?: return false
		val result = try {
			batch.dispatch(count, states, drives, allowSoftware, publish)
		} catch (error: LinkageError) {
			batch.disable(error)
			return false
		} catch (error: Exception) {
			batch.disable(error)
			return false
		}
		if (!result) return false
		if (publish) batch.apply(states)
		status = compiled.batchStatus
		compiled.reported = false
		compiled.resetTiming()
		return true
	}

	override fun synchronizeBatch(states: StateManager): Boolean {
		val batch = circuit?.batch ?: return false
		return try {
			batch.synchronize(states)
		} catch (error: LinkageError) {
			batch.disable(error)
			false
		} catch (error: Exception) {
			batch.disable(error)
			false
		}
	}

	private fun prepare(topology: Long, gates: MappedArray<Vector2i, ITickable>): Circuit? {
		if (revision != topology) {
			close()
			circuit = compile(gates)
			revision = topology
		}
		return circuit
	}

	private fun fail(error: Throwable) {
		close()
		failure = error.message ?: error.javaClass.simpleName
		status = "Classic (Terni fallback: $failure)"
		System.err.println(status)
	}

	override fun close() {
		circuit?.let {
			it.batch?.close()
			TerniNative.destroy(it.handle)
		}
		circuit = null
		revision = -1L
		status = if (failure == null) "Classic (Terni pending)" else "Classic (Terni fallback: $failure)"
	}

	private fun compile(gates: MappedArray<Vector2i, ITickable>): Circuit? {
		val accelerated = ArrayList<SaveableGate>()
		val slots = ArrayList<Int>()
		val inputRows = ArrayList<Input>()
		val inputIndices = IdentityHashMap<Input, Int>()
		for (slot in 0 until gates.size) {
			val gate = gates[slot]
			if (opcode(gate) < 0) continue
			gate as SaveableGate
			// The stock Input and Controller share the exact same read implementation.
			if ((0 until gate.simulationInputCount()).any {
				val input = gate.simulationInput(it)
				input.javaClass != Input::class.java && input !is Controller
			}) continue
			for (i in 0 until gate.simulationInputCount()) {
				val input = gate.simulationInput(i) as Input
				if (!inputIndices.containsKey(input)) {
					inputIndices[input] = inputRows.size
					inputRows.add(input)
				}
			}
			accelerated.add(gate)
			slots.add(slot)
		}
		if (accelerated.isEmpty()) {
			status = "Classic (no Terni-compatible gates)"
			return null
		}

		val netSlots = LinkedHashMap<Int, Int>()
		val inputNets = IntArray(inputRows.size * 4)
		for ((row, input) in inputRows.withIndex()) {
			for (pin in 0 until input.size) {
				val id = input.array[pin]!!.id
				inputNets[row * 4 + pin] = netSlots.getOrPut(id) { netSlots.size + 1 }
			}
		}

		val rows = IntArray(accelerated.size * 5) { -1 }
		for ((row, gate) in accelerated.withIndex()) {
			rows[row * 5] = opcode(gate)
			for (pin in 0 until gate.simulationInputCount()) {
				rows[row * 5 + 1 + pin] = inputIndices[gate.simulationInput(pin) as Input]!!
			}
		}

		val reductions = if (accelerated.size == gates.currentElements) reductions(accelerated, inputRows) else null
		val sampled = netSlots.keys.toIntArray()
		val terminals = inputRows.toTypedArray()
		val batch = if (reductions != null)
			BatchPlan.create(rows, inputNets, reductions, sampled, terminals) else null
		val start = System.nanoTime()
		TerniNative.load()
		val handle = TerniNative.create(rows, inputNets, netSlots.size + 1,
			reductions?.writes ?: IntArray(0), reductions?.states?.size ?: accelerated.size, allowSoftware)
		try {
			return Circuit(handle, TerniNative.describe(handle), (System.nanoTime() - start) / 1_000_000.0,
				accelerated.size,
				if (reductions == null) accelerated.toTypedArray() else emptyArray(),
				if (reductions == null) slots.toIntArray() else IntArray(0),
				terminals, sampled, reductions?.states, reductions?.privateCount ?: 0, batch)
		} catch (error: Throwable) {
			TerniNative.destroy(handle)
			throw error
		}
	}

	// Direct reduction is valid when every tickable is compiled and private state
	// does not alias another writer. Unusual saved state aliasing uses ordered replay.
	private fun reductions(gates: List<SaveableGate>, inputs: List<Input>): Reductions? {
		val privateStates = ArrayList<State>()
		val ids = HashSet<Int>()
		for (gate in gates) {
			val state = gate.getState(Axis.HORIZONTAL) ?: return null
			if (!ids.add(state.id)) return null
			privateStates.add(state)
		}
		for (input in inputs) {
			val state = input.getState(Axis.HORIZONTAL) ?: return null
			if (!ids.add(state.id)) return null
			privateStates.add(state)
		}
		val nets = LinkedHashMap<Int, Int>()
		val states = ArrayList(privateStates)
		val writes = IntArray(gates.size * 5 + inputs.size) { -1 }
		for ((row, gate) in gates.withIndex()) {
			writes[row * 5] = row
			for (pin in 0 until gate.simulationOutputCount()) {
				val state = gate.simulationOutput(pin)
				if (state.id in ids) return null
				writes[row * 5 + 1 + pin] = nets.getOrPut(state.id) {
					states.add(state)
					states.size - 1
				}
			}
		}
		for (i in inputs.indices) writes[gates.size * 5 + i] = gates.size + i
		return Reductions(writes, states.toTypedArray(), privateStates.size)
	}

	private class Reductions(val writes: IntArray, val states: Array<State>, val privateCount: Int)

	private class BatchPlan(private val gates: IntArray, private val pins: IntArray, private val writes: IntArray,
		private val stateIds: IntArray, private val slots: Map<Int, Int>, private val inputs: Array<Input>,
		private val privateCount: Int) {
		private val wide = gates.size / 5 > 256 || stateIds.size > 2048
		private val partitions = if (wide) BatchPartitions.build(gates, pins, writes, stateIds.size) else null
		val mode = when {
			partitions != null -> ", partitioned"
			wide -> ", multi-workgroup"
			else -> ""
		}
		private val maxPreviewTicks = if (wide && partitions == null) 32 else 256
		var previewTicks = if (maxPreviewTicks == 32) {
			when {
				stateIds.size <= 8_192 -> 32
				stateIds.size <= 65_536 -> 16
				else -> 8
			}
		} else if (stateIds.size <= 8_192) 256 else if (stateIds.size <= 65_536) 128 else 32
			private set
		private var tickNanos = 0.0
		private var samples = 0
		private var handle = 0L
		private var disabled = false
		private var resident = false
		private val initial = IntArray(stateIds.size + inputs.size)
		private val results = IntArray(stateIds.size)
		private val emptySchedule = IntArray(2)

		fun dispatch(count: Int, states: StateManager, drives: List<BatchSignalDrive>,
			allowSoftware: Boolean, publish: Boolean): Boolean {
			if (disabled || drives.size > 4096 || (wide && drives.isNotEmpty())) return false
			val schedule = if (drives.isEmpty()) {
				emptySchedule[0] = count
				emptySchedule
			} else {
				val records = IntArray(3 + count + drives.size * 2)
				records[0] = count
				records[1] = drives.size
				var written = 0
				val scheduled = drives.groupBy { it.offset }
				for (tick in 0 until count) {
					records[2 + tick] = written
					for (drive in scheduled[tick] ?: emptyList()) {
						val slot = slots[drive.state.id] ?: return false
						if (slot < privateCount) return false
						val base = 3 + count + written++ * 2
						records[base] = slot
						records[base + 1] = if (drive.high) 3 else 2
					}
				}
				records[2 + count] = written
				records
			}
			if (handle == 0L) handle = TerniNative.createResidentBatch(gates, pins, writes, stateIds.size,
				partitions?.layout ?: IntArray(0), partitions?.groups ?: 0, allowSoftware)
			if (!resident) {
				for (i in stateIds.indices) {
					val id = stateIds[i]
					initial[i] = (if (states[id]) 1 else 0) or
						(if (states.getDirty(id)) 2 else 0) or
						(if (i >= privateCount && states.used[id] > 0) 4 else 0)
				}
				for (i in inputs.indices) initial[stateIds.size + i] = if (inputs[i].inverted) 1 else 0
			}
			val start = System.nanoTime()
			TerniNative.evaluateBatch(handle, initial, schedule, results, resident, publish)
			if (wide && resident && !publish && drives.isEmpty()) {
				val measured = (System.nanoTime() - start).toDouble() / count
				tickNanos = if (samples == 0) measured else tickNanos * 0.875 + measured * 0.125
				if (++samples % 8 == 0) {
					// Power-of-two limits reuse recorded Terni command buffers. Leave headroom
					// for preview transfers and for the main thread to acquire the world lock.
					val duration = tickNanos * previewTicks
					if (duration > 1_500_000 && previewTicks > 1) previewTicks /= 2
					else if (duration < 500_000 && previewTicks < maxPreviewTicks) previewTicks *= 2
				}
			}
			resident = !publish
			return true
		}

		fun synchronize(states: StateManager): Boolean {
			if (!resident) return false
			TerniNative.readBatch(handle, results)
			apply(states)
			resident = false
			return true
		}

		fun apply(states: StateManager) {
			for (i in stateIds.indices) states[stateIds[i]] = results[i] != 0
		}

		fun disable(error: Throwable) {
			close()
			disabled = true
			System.err.println("Terni batch unavailable: ${error.message}")
		}

		fun close() {
			if (handle != 0L) TerniNative.destroy(handle)
			handle = 0L
			resident = false
		}

		companion object {
			fun create(rows: IntArray, inputNets: IntArray, reductions: Reductions,
				sampled: IntArray, inputs: Array<Input>): BatchPlan {
				val states = LinkedHashMap<Int, Int>()
				for (state in reductions.states) states[state.id] = states.size
				val netSlots = IntArray(sampled.size + 1) { -1 }
				for (i in sampled.indices) netSlots[i + 1] = states.getOrPut(sampled[i]) { states.size }
				val pins = IntArray(inputNets.size) { netSlots[inputNets[it]] }
				return BatchPlan(rows, pins, reductions.writes, states.keys.toIntArray(),
					states, inputs, reductions.privateCount)
			}
		}
	}

	private fun opcode(gate: ITickable?) = when (gate) {
		is AndGate -> 0
		is NandGate -> 1
		is OrGate -> 2
		is NorGate -> 3
		is XorGate -> 4
		is XnorGate -> 5
		else -> -1
	}

	private class Circuit(val handle: Long, val device: String, val initializeMs: Double, val gateCount: Int,
		private val gates: Array<SaveableGate>, private val slots: IntArray, private val inputs: Array<Input>,
		private val nets: IntArray, private val resultStates: Array<State>?, private val privateCount: Int,
		val batch: BatchPlan?) {
		private val signals = IntArray(1 + nets.size + inputs.size)
		private val results = IntArray(resultStates?.size ?: gateCount)
		private var gpuMs = 0.0
		private var start = 0L
		var reported = false
		val batchStatus = "Terni $device (batched) | $gateCount gates${batch?.mode.orEmpty()}"
		private val timing = DoubleArray(120)
		var samples = 0
			private set

		fun dispatch(states: StateManager, checkOrder: Boolean) {
			start = System.nanoTime()
			for (i in nets.indices) signals[i + 1] = if (states[nets[i]]) 1 else 0
			for (i in inputs.indices) signals[1 + nets.size + i] = if (inputs[i].inverted) 1 else 0
			gpuMs += TerniNative.evaluate(handle, signals, results, checkOrder && !reported)
		}

		fun apply(tick: Long, tickables: MappedArray<Vector2i, ITickable>) {
			if (resultStates == null) replay(tick, tickables)
			else {
				for (i in results.indices) {
					val flag = results[i]
					if (flag == 0) continue
					if (i < privateCount) resultStates[i].setActiveU(flag == 3)
					else resultStates[i].setActive(flag == 3)
				}
			}
			timing[samples++] = (System.nanoTime() - start) / 1_000_000.0
		}

		private fun replay(tick: Long, tickables: MappedArray<Vector2i, ITickable>) {
			var row = 0
			for (slot in 0 until tickables.size) {
				if (row < slots.size && slots[row] == slot) {
					val flags = results[row]
					val gate = gates[row++]
					for (pin in 0 until gate.simulationInputCount()) {
						if ((flags and (2 shl pin)) != 0) {
							(gate.simulationInput(pin) as Input).applySimulationRead((flags and (32 shl pin)) != 0)
						}
					}
					gate.applySimulationResult((flags and 1) != 0)
				} else tickables[slot]?.update(tick)
			}
		}

		fun status(): String {
			reported = true
			val sorted = timing.copyOf(samples).sortedArray()
			val mode = if (resultStates == null) "hybrid" else "net reduction"
			return String.format(Locale.ROOT,
				"Terni %s (%s) | %d gates | %d sampled nets | %.3f ms mean | %.3f ms p95 | %.3f ms compute",
				device, mode, gateCount, nets.size, sorted.average(), sorted[(samples - 1) * 95 / 100], gpuMs / samples)
		}

		fun resetTiming() {
			samples = 0
			gpuMs = 0.0
		}
	}
}

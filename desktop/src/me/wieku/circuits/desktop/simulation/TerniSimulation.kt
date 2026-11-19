package me.wieku.circuits.desktop.simulation

import me.wieku.circuits.api.collections.MappedArray
import me.wieku.circuits.api.element.BasicInput
import me.wieku.circuits.api.element.ITickable
import me.wieku.circuits.api.math.Axis
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.api.state.State
import me.wieku.circuits.api.state.StateManager
import me.wieku.circuits.world.elements.gates.*
import me.wieku.circuits.world.elements.bus.BusSignal
import me.wieku.circuits.world.elements.bus.BusTap
import me.wieku.circuits.world.elements.io.Controller
import me.wieku.circuits.world.elements.io.Input
import me.wieku.circuits.world.elements.io.Output
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
	external fun createResidentBatchV3(gates: IntArray, inputPins: IntArray, writes: IntArray,
		stateCount: Int, partitions: IntArray, groups: Int, allowSoftware: Boolean, busCount: Int, programmerCount: Int, initialWords: Int, ramWords: Int): Long
	external fun evaluateBatchV3(handle: Long, initial: IntArray, commands: IntArray, results: IntArray,
		resume: Boolean, publish: Boolean, uploadWords: Int, ram: IntArray): Boolean
	external fun readBatchV3(handle: Long, results: IntArray, ram: IntArray)
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

	val nativeReport: String get() = circuit?.takeIf { it.handle != 0L }?.let {
		String.format(Locale.ROOT, "%.3f ms native initialization, %s", it.initializeMs, TerniNative.report(it.handle))
	} ?: status

	override fun evaluate(tick: Long, topology: Long, gates: MappedArray<Vector2i, ITickable>, states: StateManager) {
		var compiled: Circuit? = null
		if (failure == null) {
			try {
				compiled = prepare(topology, gates)
				if (compiled?.handle == 0L) compiled = null
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
		if (compiled == null) {
			ClassicSimulation.evaluate(tick, topology, gates, states)
			if (circuit?.handle == 0L) status = "Classic (resident gates available for batches)"
		}
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
		status = batch.status
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
			if (it.handle != 0L) TerniNative.destroy(it.handle)
		}
		circuit = null
		revision = -1L
		status = if (failure == null) "Classic (Terni pending)" else "Classic (Terni fallback: $failure)"
	}

	private class Topology(val gates: List<ITickable>, val slots: List<Int>, val rows: IntArray,
		val terminals: Array<Input>, val inputNets: IntArray, val sampled: IntArray)

	private fun topology(gates: MappedArray<Vector2i, ITickable>, resident: Boolean): Topology {
		val accelerated = ArrayList<ITickable>()
		val slots = ArrayList<Int>()
		val inputRows = ArrayList<Input>()
		val inputIndices = IdentityHashMap<Input, Int>()
		for (slot in 0 until gates.size) {
			val gate = gates[slot]
			if (opcode(gate, resident) < 0) continue
			if (gate is BusTap) {
				accelerated.add(gate)
				slots.add(slot)
				continue
			}
			gate as SaveableGate
			val pinCount = simulationInputCount(gate)
			// The stock Input and Controller share the exact same read implementation.
			if ((0 until pinCount).any {
				val input = simulationInput(gate, it)
				input.javaClass != Input::class.java && input.javaClass != Controller::class.java
			}) continue
			for (pin in 0 until pinCount) {
				val input = simulationInput(gate, pin) as Input
				if (!inputIndices.containsKey(input)) {
					inputIndices[input] = inputRows.size
					inputRows.add(input)
				}
			}
			accelerated.add(gate)
			slots.add(slot)
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
			rows[row * 5] = opcode(gate, resident)
			if (gate !is SaveableGate) continue
			for (pin in 0 until simulationInputCount(gate)) {
				rows[row * 5 + 1 + pin] = inputIndices[simulationInput(gate, pin)]!!
			}
		}
		return Topology(accelerated, slots, rows, inputRows.toTypedArray(), inputNets, netSlots.keys.toIntArray())
	}

	private fun controllerCount(gate: SaveableGate): Int = when (gate) {
		is ClockedGate -> gate.simulationControllerCount()
		is ProgramInputGate -> gate.simulationControllerCount()
		is RamGate -> gate.simulationControllerCount()
		else -> 0
	}

	private fun simulationInputCount(gate: SaveableGate) = gate.simulationInputCount() + controllerCount(gate)

	private fun simulationInput(gate: SaveableGate, pin: Int): BasicInput {
		val controllers = controllerCount(gate)
		if (pin >= controllers) return gate.simulationInput(pin - controllers)
		return when (gate) {
			is ClockedGate -> gate.simulationController(pin)
			is ProgramInputGate -> gate.simulationController(pin)
			is RamGate -> gate.simulationController(pin)
			else -> error("Gate has no controllers")
		}
	}

	private fun compile(gates: MappedArray<Vector2i, ITickable>): Circuit? {
		val basic = topology(gates, false)
		val reductions = if (basic.gates.isNotEmpty() && basic.gates.size == gates.currentElements)
			reductions(basic, false) else null
		val batch = when {
			reductions != null -> BatchPlan.create(basic, reductions)
			(0 until gates.size).any { opcode(gates[it], true) > 5 } -> {
				val resident = topology(gates, true)
				if (resident.gates.size == gates.currentElements)
					reductions(resident, true)?.let { BatchPlan.create(resident, it) }
				else null
			}
			else -> null
		}
		val accelerated = basic.gates
		if (accelerated.isEmpty()) {
			status = if (batch == null) "Classic (no Terni-compatible gates)" else "Classic (resident gates available for batches)"
			if (batch == null) return null
			TerniNative.load()
			return Circuit(0, "", 0.0, batch.gateCount, emptyArray(), IntArray(0),
				emptyArray(), IntArray(0), null, 0, batch)
		}
		val sampled = basic.sampled
		val terminals = basic.terminals
		val start = System.nanoTime()
		TerniNative.load()
		val handle = TerniNative.create(basic.rows, basic.inputNets, sampled.size + 1,
			reductions?.writes ?: IntArray(0), reductions?.states?.size ?: accelerated.size, allowSoftware)
		try {
			return Circuit(handle, TerniNative.describe(handle), (System.nanoTime() - start) / 1_000_000.0,
				accelerated.size,
				if (reductions == null) accelerated.map { it as SaveableGate }.toTypedArray() else emptyArray(),
				if (reductions == null) basic.slots.toIntArray() else IntArray(0),
				terminals, sampled, reductions?.states?.map { it!! }?.toTypedArray(), reductions?.privateCount ?: 0, batch)
		} catch (error: Throwable) {
			TerniNative.destroy(handle)
			throw error
		}
	}

	// Direct reduction is valid when every tickable is compiled and private state
	// does not alias another writer. Unusual saved state aliasing uses ordered replay.
	private fun reductions(topology: Topology, extended: Boolean): Reductions? {
		val gates = topology.gates
		val inputs = topology.terminals
		val privateStates = ArrayList<State?>()
		val ids = HashSet<Int>()
		for (gate in gates) {
			if (gate is BusTap) {
				privateStates.add(null)
				continue
			}
			gate as SaveableGate
			val state = gate.getState(Axis.HORIZONTAL) ?: return null
			if (!ids.add(state.id)) return null
			privateStates.add(state)
		}
		for (input in inputs) {
			val state = input.getState(Axis.HORIZONTAL) ?: return null
			if (!ids.add(state.id)) return null
			privateStates.add(state)
		}
		val carrySlots = if (extended) IntArray(gates.size) { -1 } else IntArray(0)
		for (row in carrySlots.indices) {
			val gate = gates[row]
			val output = carryOutput(gate) ?: continue
			if (output.javaClass != Output::class.java) return null
			val state = output.getState(Axis.HORIZONTAL) ?: return null
			if (!ids.add(state.id)) return null
			carrySlots[row] = privateStates.size
			privateStates.add(state)
		}
		if (extended && topology.sampled.any { it in ids }) return null
		val nets = LinkedHashMap<Int, Int>()
		val states = ArrayList(privateStates)
		val extraStart = gates.size * 5 + inputs.size
		val writes = IntArray(extraStart + if (extended) gates.size * 5 else 0) { -1 }
		fun netSlot(state: State): Int = nets.getOrPut(state.id) {
			states.add(state)
			states.size - 1
		}
		for ((row, gate) in gates.withIndex()) {
			writes[row * 5] = row
			if (gate is BusTap) {
				for ((pin, state) in gate.simulationWires().withIndex()) {
					if (state.id in ids) return null
					val base = if (gate.feedsBus) extraStart + row * 5 else row * 5
					writes[base + 1 + pin] = netSlot(state)
				}
				continue
			}
			gate as SaveableGate
			for (pin in 0 until gate.simulationOutputCount()) {
				val state = gate.simulationOutput(pin)
				if (state.id in ids) return null
				writes[row * 5 + 1 + pin] = netSlot(state)
			}
			if (extended && carrySlots[row] >= 0) {
				val output = carryOutput(gate)!!
				val base = extraStart + row * 5
				writes[base] = carrySlots[row]
				for (pin in 0 until output.simulationOutputCount()) {
					val state = output.simulationOutput(pin)
					if (state.id in ids) return null
					writes[base + 1 + pin] = netSlot(state)
				}
			}
		}
		for (i in inputs.indices) writes[gates.size * 5 + i] = gates.size + i
		return Reductions(writes, states.toTypedArray(), privateStates.size)
	}

	private fun carryOutput(gate: ITickable) = when (gate) {
		is HalfAdderGate -> gate.simulationCarryOutput()
		is ProgramInputGate -> gate.simulationCarryOutput()
		else -> null
	}

	private class Reductions(val writes: IntArray, val states: Array<State?>, val privateCount: Int)

	private class BatchPlan(private val gates: IntArray, private val pins: IntArray, private val writes: IntArray,
		private val stateIds: IntArray, private val slots: Map<Int, Int>, private val inputs: Array<Input>,
		private val privateCount: Int, private val latches: Map<Int, ClockedGate>, private val timers: Map<Int, TimedGate>,
		private val taps: Map<Int, BusTap>, private val buses: Array<BusSignal>,
		private val programmers: Map<Int, ProgramInputGate>, private val hostState: ResidentHostState,
		private val stops: Map<Int, StopGate>, private val keys: Map<Int, KeyGate>) {
		val gateCount = gates.size / 5
		private val programmerStart = stateIds.size + buses.size
		private val stateCount = programmerStart + programmers.size
		private val programmerSlots = programmers.keys.withIndex().associate { it.value to programmerStart + it.index }
		private val wide = gateCount > 256 || stateCount > 2048
		private val partitions = if (wide) BatchPartitions.build(gates, pins, writes, stateCount, programmerSlots) else null
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
		var status = ""
			private set
		private var disabled = false
		private var resident = false
		private val parameterStart = stateCount + inputs.size
		private val programStart = parameterStart + if (timers.isEmpty() && taps.isEmpty() && programmers.isEmpty() && !hostState.isPresent)
			0 else 3 * gateCount
		private var initial = IntArray(programStart)
		private var remainingProgramTicks = Int.MAX_VALUE
		private val programRevisions = LongArray(programmers.size) { -1 }
		private var programUploadPending = programmers.isNotEmpty()
		private val results = IntArray(stateCount)
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
			if (!resident) {
				if (programmers.isNotEmpty() && !configureProgrammers(count)) return false
				if (hostState.isPresent) {
					val previousSize = hostState.words.size
					val packed = hostState.pack(initial, parameterStart)
					if (hostState.words.size != previousSize) close()
					if (!packed) return false
				}
				for (i in stateIds.indices) {
					val id = stateIds[i]
					if (id < 0) continue
					initial[i] = (if (states[id]) 1 else 0) or
						(if (states.getDirty(id)) 2 else 0) or
						(if (i >= privateCount && states.used[id] > 0) 4 else 0)
				}
				for ((row, gate) in timers) {
					val limit = gate.simulationLimit
					val counter = gate.simulationCounter
					// The staged driver word reserves five low bits for signal metadata.
					if (limit !in 0..0x07ffffff || counter !in 0..0x07ffffff) return false
					val self = writes[row * 5]
					initial[self] = initial[self] or (counter shl 3)
					initial[parameterStart + row * 3] = limit
					initial[parameterStart + row * 3 + 1] = gate.simulationOnTicks
				}
				for ((row, gate) in programmers) {
					val slot = programmerSlots[row]!!
					initial[slot] = gate.simulationIndex()
					val self = writes[row * 5]
					initial[self] = initial[self] or (gate.simulationLatchBits() shl 3)
				}
				for ((slot, gate) in latches) if (gate.simulationArmed) initial[slot] = initial[slot] or 8
				for ((row, gate) in keys) {
					if (gate.locked) {
						val self = writes[row * 5]
						initial[self] = initial[self] or 8
					}
				}
				for ((row, gate) in stops) {
					val self = writes[row * 5]
					if (gate.simulationArmed) initial[self] = initial[self] or 8
					initial[parameterStart + row * 3] = if (gate.simulationCanStop()) 1 else 0
				}
				for ((row, tap) in taps) initial[parameterStart + row * 3] = tap.bit
				for (i in buses.indices) initial[stateIds.size + i] = buses[i].current
				for (i in inputs.indices) initial[stateCount + i] = if (inputs[i].inverted) 1 else 0
			}
			if (programmers.isNotEmpty() && count > remainingProgramTicks) return false
			if (handle == 0L) {
				handle = TerniNative.createResidentBatchV3(gates, pins, writes, stateCount,
					partitions?.layout ?: IntArray(0), partitions?.groups ?: 0, allowSoftware, buses.size,
					programmers.size, initial.size, hostState.words.size)
				status = "Terni ${TerniNative.describe(handle)} (batched) | $gateCount gates$mode"
			}
			val start = System.nanoTime()
			if (!TerniNative.evaluateBatchV3(handle, initial, schedule, results, resident, publish,
					if (programUploadPending) initial.size else programStart, hostState.words)) {
				// Nothing from this attempt was published. The world replays from its host checkpoint.
				resident = false
				return false
			}
			programUploadPending = false
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
			if (programmers.isNotEmpty()) remainingProgramTicks -= count
			resident = !publish
			return true
		}

		private fun configureProgrammers(count: Int): Boolean {
			var words = programStart.toLong()
			remainingProgramTicks = Int.MAX_VALUE
			for (gate in programmers.values) {
				val index = gate.simulationIndex()
				val bytes = gate.simulationByteCount()
				if (index < -1 || bytes > Int.MAX_VALUE / 8) return false
				if (index >= 0) remainingProgramTicks = minOf(remainingProgramTicks, Int.MAX_VALUE - index)
				words += (bytes.toLong() + 3) / 4
			}
			if (words > Int.MAX_VALUE || count > remainingProgramTicks) return false
			if (words.toInt() != initial.size) {
				close()
				initial = IntArray(words.toInt())
			}
			val changed = programmers.values.withIndex().any { programRevisions[it.index] != it.value.simulationProgramRevision }
			if (!changed && !programUploadPending) return true
			var start = programStart
			var program = 0
			for ((row, gate) in programmers) {
				programRevisions[program++] = gate.simulationProgramRevision
				val param = parameterStart + row * 3
				val bytes = gate.simulationByteCount()
				initial[param] = start
				initial[param + 1] = bytes
				initial[param + 2] = programmerSlots[row]!!
				for (index in 0 until bytes step 4) {
					var packed = 0
					for (byte in 0 until minOf(4, bytes - index)) {
						packed = packed or ((gate.simulationByte(index + byte).toInt() and 255) shl (byte * 8))
					}
					initial[start++] = packed
				}
			}
			programUploadPending = true
			return true
		}

		fun synchronize(states: StateManager): Boolean {
			if (!resident) return false
			TerniNative.readBatchV3(handle, results, hostState.words)
			apply(states)
			resident = false
			return true
		}

		fun apply(states: StateManager) {
			hostState.restore()
			for ((row, gate) in keys) gate.locked = (results[writes[row * 5]] and 2) != 0
			for ((row, gate) in stops) gate.simulationArmed = (results[writes[row * 5]] and 2) != 0
			for (i in stateIds.indices) {
				val id = stateIds[i]
				if (id >= 0) states[id] = (results[i] and 1) != 0
			}
			for (i in buses.indices) buses[i].restoreSimulationWord(results[stateIds.size + i])
			for ((slot, gate) in latches) gate.simulationArmed = (results[slot] and 2) != 0
			for ((row, gate) in programmers) {
				gate.restoreSimulationState(results[programmerSlots[row]!!], results[writes[row * 5]] ushr 1)
			}
			for ((row, gate) in timers) gate.simulationCounter = results[writes[row * 5]] ushr 1
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
			programUploadPending = programmers.isNotEmpty()
		}

		companion object {
			fun create(topology: Topology, reductions: Reductions): BatchPlan {
				val sampled = topology.sampled
				val inputNets = topology.inputNets
				val states = LinkedHashMap<Int, Int>()
				val stateIds = IntArray(reductions.states.size + sampled.size)
				for ((slot, state) in reductions.states.withIndex()) {
					val id = state?.id ?: -1
					if (id >= 0) states[id] = slot
					stateIds[slot] = id
				}
				var stateCount = reductions.states.size
				val netSlots = IntArray(sampled.size + 1) { -1 }
				for (i in sampled.indices) netSlots[i + 1] = states.getOrPut(sampled[i]) {
					stateIds[stateCount] = sampled[i]
					stateCount++
					stateCount - 1
				}
				val pins = IntArray(inputNets.size) { netSlots[inputNets[it]] }
				val latches = LinkedHashMap<Int, ClockedGate>()
				val timers = LinkedHashMap<Int, TimedGate>()
				val taps = LinkedHashMap<Int, BusTap>()
				val buses = LinkedHashMap<BusSignal, Int>()
				val programmers = LinkedHashMap<Int, ProgramInputGate>()
				val ram = LinkedHashMap<Int, RamGate>()
				val stops = LinkedHashMap<Int, StopGate>()
				val keys = LinkedHashMap<Int, KeyGate>()
				val serial = LinkedHashMap<Int, SerialIOGate>()
				for ((row, gate) in topology.gates.withIndex()) {
					if (gate is ClockedGate) latches[reductions.writes[row * 5]] = gate
					if (gate is TimedGate) timers[row] = gate
					if (gate is ProgramInputGate) programmers[row] = gate
					if (gate is RamGate) ram[row] = gate
					if (gate is StopGate) stops[row] = gate
					if (gate is KeyGate) keys[row] = gate
					if (gate is SerialIOGate) serial[row] = gate
					if (gate is BusTap) {
						taps[row] = gate
						val signal = gate.simulationBus() ?: continue
						val extra = topology.rows.size + topology.terminals.size + row * 5
						reductions.writes[extra] = buses.getOrPut(signal) { stateCount + buses.size }
					}
				}
				return BatchPlan(topology.rows, pins, reductions.writes, stateIds.copyOf(stateCount),
					states, topology.terminals, reductions.privateCount, latches, timers, taps, buses.keys.toTypedArray(), programmers,
					ResidentHostState(ram, serial, stops.isNotEmpty()), stops, keys)
			}
		}
	}

	private fun opcode(gate: ITickable?, resident: Boolean) = when (gate) {
		is AndGate -> 0
		is NandGate -> 1
		is OrGate -> 2
		is NorGate -> 3
		is XorGate -> 4
		is XnorGate -> 5
		is HalfAdderGate -> if (resident) 6 else -1
		is MemoryGate -> if (resident) 7 + gate.simulationControllerCount() else -1
		is TFFGate -> if (resident) 12 + gate.simulationControllerCount() else -1
		is DelayGate -> if (resident) 17 else -1
		is PWMGate -> if (resident) 18 else -1
		is BusTap -> if (!resident) -1 else if (gate.feedsBus) 19 else 20
		is ProgramInputGate -> if (resident) 21 + gate.simulationControllerCount() else -1
		is RamGate -> if (resident) 26 + gate.simulationControllerCount() else -1
		is StopGate -> if (resident) 31 else -1
		is KeyGate -> if (resident) 32 else -1
		is SerialIOGate -> if (resident) 33 else -1
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

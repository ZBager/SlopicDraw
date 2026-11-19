package me.wieku.circuits.desktop.simulation

import me.wieku.circuits.world.elements.gates.RamGate
import me.wieku.circuits.world.elements.gates.SerialIOGate

internal class ResidentHostState(private val gates: Map<Int, RamGate>,
	private val serial: Map<Int, SerialIOGate>, private val hasStop: Boolean) {
	private val capacities = IntArray(gates.size)
	private val offsets = IntArray(gates.size)
	private val serialOffsets = IntArray(serial.size)
	var words = IntArray(0)
		private set
	val isPresent get() = gates.isNotEmpty() || serial.isNotEmpty() || hasStop

	fun pack(initial: IntArray, parameterStart: Int): Boolean {
		if (!isPresent) return true
		var end = 1 // A nonzero first word requests ordered host replay.
		for ((index, gate) in gates.values.withIndex()) {
			val length = gate.simulationMemorySize()
			if (length > 1024 * 1024) return false
			capacities[index] = maxOf(capacities[index], (length + 3) and -4, 64)
			offsets[index] = end
			end += 8 + capacities[index] / 4
			if (end > 8 * 1024 * 1024) return false
		}
		for (index in serialOffsets.indices) {
			serialOffsets[index] = end
			end += 3
		}
		if (end > 8 * 1024 * 1024) return false
		if (words.size != end) words = IntArray(end)
		words[0] = 0
		for ((index, entry) in gates.entries.withIndex()) {
			val offset = offsets[index]
			initial[parameterStart + entry.key * 3] = offset
			if (!entry.value.packSimulationRam(words, offset, capacities[index])) return false
		}
		for ((index, entry) in serial.entries.withIndex()) {
			val offset = serialOffsets[index]
			initial[parameterStart + entry.key * 3] = offset
			entry.value.packSimulationState(words, offset)
		}
		return true
	}

	fun restore() {
		for ((index, gate) in gates.values.withIndex()) gate.restoreSimulationRam(words, offsets[index])
		for ((index, gate) in serial.values.withIndex()) gate.restoreSimulationState(words, serialOffsets[index])
	}
}

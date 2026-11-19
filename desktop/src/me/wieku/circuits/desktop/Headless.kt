package me.wieku.circuits.desktop

import me.wieku.circuits.api.simulation.Circuit
import java.io.File

private data class Probe(val x: Int, val y: Int, val port: Circuit.Port, val bit: Int? = null)

internal fun runHeadless(args: Array<String>) {
	require(args.size >= 2) {
		"Usage: --headless MAP TICKS [--read X,Y,h|v] [--drive X,Y,h|v,0|1] [--read-bus X,Y,h|v,BIT] [--tap X,Y,BIT] [--save MAP | --save-legacy MAP]"
	}
	val ticks = args[1].toInt()
	require(ticks >= 0)
	val probes = ArrayList<Probe>()
	var destination: File? = null
	var legacy = false
	Circuit.open(File(args[0])).use { circuit ->
		for (index in 2 until args.size step 2) {
			require(index + 1 < args.size) { "Missing value for ${args[index]}." }
			val value = args[index + 1]
			when (args[index]) {
				"--tap" -> {
					val parts = value.split(',')
					require(parts.size == 3) { "Expected x,y,bit." }
					circuit.setBusTapBit(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
				}
				"--read", "--drive", "--read-bus" -> {
					val drive = args[index] == "--drive"
					val bus = args[index] == "--read-bus"
					val parts = value.split(',')
					require(parts.size == if (drive || bus) 4 else 3) { "Invalid signal coordinates." }
					val x = parts[0].toInt()
					val y = parts[1].toInt()
					val port = when (parts[2]) {
						"h" -> Circuit.Port.HORIZONTAL
						"v" -> Circuit.Port.VERTICAL
						else -> throw IllegalArgumentException("Expected h or v for the signal axis.")
					}
					if (drive) {
						require(parts[3] == "0" || parts[3] == "1")
						circuit.drive(x, y, port, parts[3] == "1")
					} else probes.add(Probe(x, y, port, if (bus) parts[3].toInt() else null))
				}
				"--save", "--save-legacy" -> {
					destination = File(value)
					legacy = args[index] == "--save-legacy"
				}
				else -> throw IllegalArgumentException("Unknown headless option ${args[index]}.")
			}
		}
		repeat(ticks) { circuit.step() }
		for ((x, y, port, bit) in probes) {
			val result = if (bit == null) circuit.read(x, y, port) else circuit.readBus(x, y, port, bit)
			val lane = if (bit == null) "" else " bit $bit"
			println("($x,$y) $port$lane: ${result ?: "no signal"}")
		}
		destination?.let { circuit.save(it, legacy) }
		println("${circuit.name}: ${circuit.ticks} ticks | ${circuit.backend}")
	}
}

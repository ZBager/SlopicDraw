package me.wieku.circuits.world.simulation

import me.wieku.circuits.api.collections.MappedArray
import me.wieku.circuits.api.element.ITickable
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.api.state.StateManager
import me.wieku.circuits.api.state.State

const val MAX_BATCH_TICKS = 256

data class BatchSignalDrive(val offset: Int, val state: State, val high: Boolean)

interface SimulationBackend {
	val status: String

	// The world owns the clock and both signal buffers. Execute one tick before swap().
	fun evaluate(tick: Long, topology: Long, gates: MappedArray<Vector2i, ITickable>, states: StateManager)
	val supportsPreviewBatches: Boolean get() = false

	// A published snapshot goes in output for the world's normal commit. An unpublished
	// batch retains its results on the device. On false, leave host state untouched.
	fun evaluateBatch(count: Int, topology: Long, gates: MappedArray<Vector2i, ITickable>,
		states: StateManager, drives: List<BatchSignalDrive>, publish: Boolean = true): Boolean = false

	fun synchronizeBatch(states: StateManager): Boolean = false

	fun close()
}

object ClassicSimulation : SimulationBackend {
	override val status = "Classic"
	override fun evaluate(tick: Long, topology: Long, gates: MappedArray<Vector2i, ITickable>, states: StateManager) {
		for (i in 0 until gates.size) gates[i]?.update(tick)
	}

	override fun close() {}
}

object SimulationBackends {
	// Installed by the desktop launcher. Mobile launchers use the default.
	var create: () -> SimulationBackend = { ClassicSimulation }
}

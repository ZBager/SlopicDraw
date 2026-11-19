package me.wieku.circuits.world.elements.gates

interface TimedGate {
	var simulationCounter: Int
	val simulationLimit: Int
	val simulationOnTicks: Int
}

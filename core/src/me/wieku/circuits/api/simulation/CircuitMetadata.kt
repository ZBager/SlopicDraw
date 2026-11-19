package me.wieku.circuits.api.simulation

/** Optional sharing information stored with a version 3 circuit. */
data class CircuitMetadata(
	val description: String? = null,
	val authors: String? = null,
	val thumbnail: String? = null
)

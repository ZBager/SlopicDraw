package me.wieku.circuits.world

import me.wieku.circuits.api.element.IElement
import me.wieku.circuits.api.math.Direction
import me.wieku.circuits.world.elements.bus.BusSignal
import me.wieku.circuits.world.elements.bus.BusTap
import me.wieku.circuits.world.elements.bus.BusWire
import java.util.ArrayDeque

internal class BusNetwork {
	private val wires = LinkedHashSet<BusWire>()
	private val taps = LinkedHashSet<BusTap>()
	private val components = ArrayList<BusSignal>()
	private var revision = -1L
	val hasElements get() = wires.isNotEmpty() || taps.isNotEmpty()

	fun place(element: IElement) {
		when (element) {
			is BusWire -> wires.add(element)
			is BusTap -> taps.add(element)
		}
	}

	fun remove(element: IElement) {
		when (element) {
			is BusWire -> wires.remove(element)
			is BusTap -> taps.remove(element)
		}
	}

	private data class Port(val wire: BusWire, val index: Int)

	fun refresh(world: ClassicWorld, topology: Long) {
		if (revision == topology) return
		revision = topology
		components.clear()
		if (!hasElements) return

		val visited = HashSet<Port>()
		val pending = ArrayDeque<Port>()
		val members = ArrayList<Port>()
		for (wire in wires) {
			for (index in 0 until wire.ports) {
				val start = Port(wire, index)
				if (!visited.add(start)) continue
				var initial = 0
				pending.add(start)
				while (pending.isNotEmpty()) {
					val current = pending.removeFirst()
					members.add(current)
					initial = initial or current.wire.bits(current.index)
					for (direction in Direction.VALID_DIRECTIONS) {
						val axis = direction.axis
						if (current.wire.port(axis) != current.index) continue
						val neighbour = world.getElement(current.wire.pos + direction.asVector) as? BusWire ?: continue
						val port = Port(neighbour, neighbour.port(axis))
						if (visited.add(port)) pending.add(port)
					}
				}
				val signal = BusSignal(initial)
				components.add(signal)
				for (member in members) member.wire.signals[member.index] = signal
				members.clear()
			}
		}
		for (tap in taps) tap.connect(world)
	}

	fun commit() {
		for (bus in components) bus.commit()
	}
}

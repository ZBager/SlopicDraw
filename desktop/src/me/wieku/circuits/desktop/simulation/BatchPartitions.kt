package me.wieku.circuits.desktop.simulation

internal data class BatchPartitions(val groups: Int, val layout: IntArray) {
	companion object {
		fun build(gateRows: IntArray, pins: IntArray, writes: IntArray, stateCount: Int): BatchPartitions? {
			val gateCount = gateRows.size / 5
			val parents = IntArray(stateCount) { it }
			val sizes = IntArray(stateCount) { 1 }
			fun root(slot: Int): Int {
				var index = slot
				while (parents[index] != index) {
					parents[index] = parents[parents[index]]
					index = parents[index]
				}
				return index
			}
			fun join(first: Int, second: Int) {
				if (second < 0) return
				val a = root(first)
				val b = root(second)
				if (a == b) return
				val larger = if (sizes[a] >= sizes[b]) a else b
				val smaller = if (larger == a) b else a
				parents[smaller] = larger
				sizes[larger] += sizes[smaller]
			}
			// Include readers, writers and indicator states. Even two read-only users
			// of the same state stay together so every slot has exactly one owner.
			for (base in gateRows.indices step 5) {
				val self = writes[base]
				for (pin in 1..4) {
					join(self, writes[base + pin])
					val input = gateRows[base + pin]
					if (input < 0) continue
					join(self, writes[gateRows.size + input])
					for (source in 0..3) join(self, pins[input * 4 + source])
				}
			}
			val gatesPerRoot = IntArray(stateCount)
			for (base in gateRows.indices step 5) gatesPerRoot[root(writes[base])]++
			val groupOfRoot = IntArray(stateCount)
			val statesPerGroup = IntArray(stateCount)
			val gatesPerGroup = IntArray(stateCount)
			val targetGates = (gateCount / 80).coerceIn(16, 256)
			var group = 0
			for (i in parents.indices) {
				if (parents[i] != i) continue
				val states = sizes[i]
				val gates = gatesPerRoot[i]
				if (states > 2048 || gates > 256) return null
				if (statesPerGroup[group] != 0 && (statesPerGroup[group] + states > 2048 ||
						gatesPerGroup[group] + gates > targetGates)) group++
				groupOfRoot[i] = group
				statesPerGroup[group] += states
				gatesPerGroup[group] += gates
			}
			val groups = group + 1
			if (groups > 65_535) return null
			// [state -> local slot][four offsets/counts per group][state IDs][gate IDs]
			val layout = IntArray(2 * stateCount + 4 * groups + gateCount)
			val stateOffsets = IntArray(groups)
			val gateOffsets = IntArray(groups)
			var stateOffset = stateCount + 4 * groups
			var gateOffset = stateOffset + stateCount
			for (index in 0 until groups) {
				val header = stateCount + index * 4
				layout[header] = stateOffset
				layout[header + 1] = statesPerGroup[index]
				layout[header + 2] = gateOffset
				layout[header + 3] = gatesPerGroup[index]
				stateOffsets[index] = stateOffset
				gateOffsets[index] = gateOffset
				stateOffset += statesPerGroup[index]
				gateOffset += gatesPerGroup[index]
			}
			for (state in 0 until stateCount) {
				val owner = groupOfRoot[root(state)]
				val index = stateOffsets[owner]++
				layout[state] = index - layout[stateCount + owner * 4]
				layout[index] = state
			}
			for (gate in 0 until gateCount) {
				val owner = groupOfRoot[root(writes[gate * 5])]
				layout[gateOffsets[owner]++] = gate
			}
			return BatchPartitions(groups, layout)
		}
	}
}

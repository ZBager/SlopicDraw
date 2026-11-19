package me.wieku.circuits.world

import me.wieku.circuits.api.state.State
import me.wieku.circuits.api.state.StateManager
import me.wieku.circuits.save.SaveManager
import java.io.IOException

class ClassicStateManager(managerSize: Int): StateManager(managerSize) {

	fun load(manager: SaveManager) {
		lastIndex = manager.getInteger()
		val poolsize = manager.getInteger()
		if (lastIndex !in 0..input.size || poolsize !in 0..lastIndex)
			throw IOException("Invalid state counts.")
		val free = BooleanArray(lastIndex)
		for (i in 0 until poolsize) {
			val index = manager.getInteger()
			if (index !in free.indices || free[index]) throw IOException("Invalid free state index.")
			free[index] = true
			indexPool.add(index)
		}

		for(i in 0 until lastIndex) {
			input[i] = manager.getByte()
			output[i] = input[i]
			if(!free[i]) {
				children[i] = State(i, this)
			}
		}
		usedNodes = lastIndex - indexPool.size
	}

	fun save(manager: SaveManager) {
		manager.putInteger(lastIndex)
		manager.putInteger(indexPool.size)
		for (index in indexPool) manager.putInteger(index)

		for(i in 0 until lastIndex) {
			manager.putByte(input[i])
		}
	}

}
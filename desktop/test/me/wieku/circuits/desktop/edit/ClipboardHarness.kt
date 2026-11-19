package me.wieku.circuits.desktop.edit

import me.wieku.circuits.api.math.Axis
import me.wieku.circuits.api.math.Direction
import me.wieku.circuits.api.math.Rectangle
import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.world.ClassicWorld
import me.wieku.circuits.world.WorldClipboard
import me.wieku.circuits.world.elements.gates.DelayGate
import me.wieku.circuits.world.elements.wire.Cross

private fun clipboard(): WorldClipboard {
	val source = ClassicWorld(8, 8, "clipboard source")
	source.placeElement(Vector2i(2, 2), "cross")
	for (position in listOf(Vector2i(1, 2), Vector2i(3, 2), Vector2i(2, 1), Vector2i(2, 3))) {
		source.placeElement(position, "wire")
	}
	source.placeElement(Vector2i(4, 2), "delay")
	source.updateTasks()
	val delay = source[4, 2] as DelayGate
	delay.pasteData(delay.copyData().apply { put("delay", 7) })
	return WorldClipboard.create(Rectangle(1, 1, 5, 4), source)
}

private fun checkAxes(world: ClassicWorld, x: Int, y: Int) {
	val cross = world[x, y] as Cross
	val horizontal = cross.getState(Axis.HORIZONTAL)
	val vertical = cross.getState(Axis.VERTICAL)
	check(horizontal !== vertical)
	check(horizontal === world[x - 1, y]!!.getState(Axis.HORIZONTAL))
	check(horizontal === world[x + 1, y]!!.getState(Axis.HORIZONTAL))
	check(vertical === world[x, y - 1]!!.getState(Axis.VERTICAL))
	check(vertical === world[x, y + 1]!!.getState(Axis.VERTICAL))
}

fun main(args: Array<String>) {
	val original = clipboard()
	val rotated = original.rotateRight()
	check(rotated.width == 3 && rotated.height == 4)
	val restored = rotated.rotateLeft()
	for (x in 0 until original.width) {
		for (y in 0 until original.height) {
			check(restored[x, y] === original[x, y])
		}
	}

	for (stepTick in listOf(false, true)) {
		val board = ClassicWorld(7, 10, "clipboard stack")
		val anchor = Vector2i(-1, 4)
		board.stackClipboard(anchor, rotated, Direction.EAST, 4)
		anchor.set(5, 0)
		check(board[3, 5] == null)
		if (stepTick) board.update(0) else board.updateTasks()
		for (x in intArrayOf(0, 3, 6)) {
			check(board[x, 5] is Cross)
			check((board[x, 7] as DelayGate).copyData()["delay"] == 7)
		}
		checkAxes(board, 3, 5)
		check(board[5, 7] == null)
	}

	val selection = ClassicWorld(13, 8, "selection stack")
	selection.paste(Vector2i(1, 1), restored)
	selection.stack(Rectangle(1, 1, 5, 4), Direction.EAST, 2)
	selection.updateTasks()
	for (x in intArrayOf(2, 6, 10)) {
		checkAxes(selection, x, 2)
		check((selection[x + 2, 2] as DelayGate).copyData()["delay"] == 7)
	}
	println("Clipboard rotation, clipped stacking, copied gate settings, and crossed nets passed.")
}

package me.wieku.circuits.save.managers

import me.wieku.circuits.world.ClassicWorld
import me.wieku.circuits.world.ClassicStateManager
import me.wieku.circuits.save.SaveManager
import me.wieku.circuits.save.SaveManagers
import me.wieku.circuits.save.Saveable
import me.wieku.circuits.world.ElementRegistry
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class SaveManagerVer01 : SaveManager {

	private lateinit var inputStream: DataInputStream
	private lateinit var outputStream: DataOutputStream
	private var maxPayloadBytes = Int.MAX_VALUE

	override fun loadHeader(file: DataInputStream): Array<String> {
		return arrayOf(file.readUTF(), file.readUTF(), file.readUTF())
	}

	override fun loadMap(file: DataInputStream): ClassicWorld = loadMap(file, Int.MAX_VALUE)

	internal fun loadMap(file: DataInputStream, maxCells: Int, maxPayloadBytes: Int = Int.MAX_VALUE): ClassicWorld {
		inputStream = file
		this.maxPayloadBytes = maxPayloadBytes
		file.readUTF()
		file.readUTF()
		file.readUTF()
		var name = file.readUTF()
		println("World name: $name")
		val width = file.readInt()
		val height = file.readInt()
		if (width <= 0 || height <= 0 || width.toLong() * height > maxCells)
			throw IOException("Unsupported map dimensions for this load.")
		val world = ClassicWorld(width, height, name)

		println("World size: ${world.width}x${world.height}")

		(world.getStateManager() as ClassicStateManager).load(this)

		println("States: ${world.getStateManager().usedNodes}")

		var counter = file.readInt()
		println("Loading $counter elements started...")
		while (counter>0) {
			var element = SaveManagers.placeSavedElement(world, file)
			if (element is Saveable) {
				(element as Saveable).load(world, this)
			}
			--counter
		}

		println("Loading elements finished, finalizing...")

		for (x in 0 until world.width) {
			for (y in 0 until world.height) {
				var element = world[x, y]
				//if (element != null && element is Saveable) {
					element?.afterLoad(world)
				//}
			}
		}
		return world
	}

	override fun saveMap(world: ClassicWorld, file: DataOutputStream) {
		outputStream = file
		file.writeUTF(world.name)
		file.writeInt(world.width)
		file.writeInt(world.height)

		(world.getStateManager() as ClassicStateManager).save(this)

		var counter = 0
		for (x in 0 until world.width) {
			for (y in 0 until world.height) {
				if (world[x, y] != null) ++counter
			}
		}

		file.writeInt(counter)
		for (x in 0 until world.width) {
			for (y in 0 until world.height) {
				var element = world[x, y]
				if (element != null) {
					file.writeInt(x)
					file.writeInt(y)
					file.writeUTF(ElementRegistry.names[element.javaClass]!!)

					if(element is Saveable)
						(element as Saveable).save(this)
				}
			}
		}
	}

	override fun putBoolean(value: Boolean) {
		outputStream.writeByte(if(value) 1 else 0)
	}

	override fun putByte(value: Byte) {
		outputStream.writeByte(value.toInt())
	}

	override fun putInteger(value: Int) {
		outputStream.writeInt(value)
	}

	override fun putLong(value: Long) {
		outputStream.writeLong(value)
	}

	override fun putString(value: String) {
		outputStream.writeUTF(value)
	}

	override fun getBoolean(): Boolean = inputStream.readByte() == (1).toByte()

	override fun getByte(): Byte = inputStream.readByte()

	override fun getBytes(size: Int): ByteArray {
		if (size !in 0..maxPayloadBytes) throw IOException("Invalid or oversized block payload.")
		return ByteArray(size).also { inputStream.readFully(it) }
	}

	override fun getInteger(): Int = inputStream.readInt()

	override fun getLong(): Long = inputStream.readLong()

	override fun getString(): String = inputStream.readUTF()

	override fun getVersion(): Int = 1
}
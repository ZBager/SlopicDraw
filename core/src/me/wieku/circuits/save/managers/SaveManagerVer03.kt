package me.wieku.circuits.save.managers

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter
import me.wieku.circuits.api.simulation.CircuitMetadata
import me.wieku.circuits.save.Saveable
import me.wieku.circuits.world.ClassicStateManager
import me.wieku.circuits.world.ClassicWorld
import me.wieku.circuits.world.ElementRegistry
import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class SaveManagerVer03 : StreamSaveManager() {
	override fun getVersion() = 3

	override fun loadHeader(file: DataInputStream): Array<String> {
		val metadata = readMetadata(file)
		return arrayOf(metadata.name, metadata.width.toString(), metadata.height.toString())
	}

	override fun loadMap(file: DataInputStream): ClassicWorld {
		val metadata = readMetadata(file)
		val world = ClassicWorld(metadata.width, metadata.height, metadata.name)
		world.metadata = metadata.sharing
		DataInputStream(GZIPInputStream(file)).use { body ->
			inputStream = body
			(world.getStateManager() as ClassicStateManager).load(this)
			val count = readCount(body, metadata)
			repeat(count) {
				val x = body.readInt()
				val y = body.readInt()
				if (x !in 0 until world.width || y !in 0 until world.height || world[x, y] != null)
					throw IOException("Invalid or duplicate block coordinates ($x, $y).")
				val type = body.readUTF()
				if (!ElementRegistry.contains(type)) throw IOException("Unknown block type '$type' at ($x, $y).")
				val size = readRecordSize(body)
				val record = ByteArray(size)
				body.readFully(record)
				val element = world.forcePlace(x, y, type)
				if (element is Saveable) {
					// Read the existing element payload. Later writers can append fields to this record.
					inputStream = DataInputStream(ByteArrayInputStream(record))
					element.load(world, this)
				}
			}
			if (body.read() != -1) throw IOException("Unexpected data after the circuit.")
		}
		for (x in 0 until world.width) {
			for (y in 0 until world.height) world[x, y]?.afterLoad(world)
		}
		world.updateTasks()
		return world
	}

	override fun saveMap(world: ClassicWorld, file: DataOutputStream) {
		val json = JsonValue(JsonValue.ValueType.`object`)
		json.addChild("name", JsonValue(world.name))
		json.addChild("width", JsonValue(world.width.toLong()))
		json.addChild("height", JsonValue(world.height.toLong()))
		world.metadata.description?.let { json.addChild("description", JsonValue(it)) }
		world.metadata.authors?.let { json.addChild("authors", JsonValue(it)) }
		world.metadata.thumbnail?.let { json.addChild("thumbnail", JsonValue(it)) }
		val metadata = json.toJson(JsonWriter.OutputType.json).toByteArray(Charsets.UTF_8)
		if (metadata.size > MAX_METADATA) throw IOException("Map metadata exceeds the size limit.")
		file.writeInt(metadata.size)
		file.write(metadata)

		DataOutputStream(GZIPOutputStream(file)).use { body ->
			outputStream = body
			(world.getStateManager() as ClassicStateManager).save(this)
			var count = 0
			for (x in 0 until world.width) {
				for (y in 0 until world.height) if (world[x, y] != null) count++
			}
			body.writeInt(count)

			val record = ByteArrayOutputStream()
			for (x in 0 until world.width) {
				for (y in 0 until world.height) {
					val element = world[x, y] ?: continue
					record.reset()
					if (element is Saveable) {
						outputStream = DataOutputStream(record)
						element.save(this)
					}
					if (record.size() > MAX_RECORD) throw IOException("Block data exceeds the record limit.")
					body.writeInt(x)
					body.writeInt(y)
					body.writeUTF(ElementRegistry.names[element.javaClass]!!)
					body.writeInt(record.size())
					record.writeTo(body)
				}
			}
		}
	}

	override fun getBytes(size: Int): ByteArray {
		if (size < 0 || size > inputStream.available()) throw IOException("Invalid block payload size.")
		return ByteArray(size).also { inputStream.readFully(it) }
	}

	internal data class Metadata(val name: String, val width: Int, val height: Int, val sharing: CircuitMetadata)

	internal companion object {
		private const val MAX_METADATA = 1024 * 1024
		private const val MAX_RECORD = 64 * 1024 * 1024

		fun readMetadata(file: DataInputStream): Metadata {
			val size = file.readInt()
			if (size !in 1..MAX_METADATA) throw IOException("Invalid map metadata length.")
			val bytes = ByteArray(size)
			file.readFully(bytes)
			val json = JsonReader().parse(String(bytes, Charsets.UTF_8))
			val width = json.getLong("width")
			val height = json.getLong("height")
			if (width !in 1L..Int.MAX_VALUE.toLong() || height !in 1L..Int.MAX_VALUE.toLong() ||
				width * height > Int.MAX_VALUE) throw IOException("Invalid map dimensions.")
			val sharing = CircuitMetadata(json.getString("description", null), json.getString("authors", null),
				json.getString("thumbnail", null))
			return Metadata(json.getString("name"), width.toInt(), height.toInt(), sharing)
		}

		fun readCount(file: DataInputStream, metadata: Metadata): Int {
			val count = file.readInt()
			if (count !in 0..(metadata.width * metadata.height)) throw IOException("Invalid block count.")
			return count
		}

		fun readRecordSize(file: DataInputStream): Int {
			val size = file.readInt()
			if (size !in 0..MAX_RECORD) throw IOException("Invalid block record length.")
			return size
		}
	}
}

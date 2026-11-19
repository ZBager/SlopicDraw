package me.wieku.circuits.desktop.save

import me.wieku.circuits.api.math.Vector2i
import me.wieku.circuits.api.simulation.Circuit
import me.wieku.circuits.api.simulation.CircuitMetadata
import me.wieku.circuits.save.SaveManagers
import me.wieku.circuits.save.SaveReadException
import me.wieku.circuits.world.ClassicWorld
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream

private fun write(file: File, body: (DataOutputStream) -> Unit) {
	DataOutputStream(GZIPOutputStream(file.outputStream())).use(body)
}

private fun unknownBlock(file: File, version: Int, magic: String) = write(file) {
	it.writeUTF(magic)
	it.writeInt(version)
	if (version == 1) {
		it.writeUTF("Unknown block fixture")
		it.writeUTF("8")
		it.writeUTF("8")
	}
	it.writeUTF("Unknown block fixture")
	it.writeInt(8)
	it.writeInt(8)
	it.writeInt(0)
	it.writeInt(0)
	it.writeInt(1)
	it.writeInt(2)
	it.writeInt(3)
	it.writeUTF("foreign_component")
}

private fun reject(file: File, reason: String, read: () -> Any) {
	val original = file.readBytes()
	try {
		read()
		error("Accepted ${file.name}")
	} catch (e: SaveReadException) {
		check(e.message!!.contains(file.name)) { e.toString() }
		check(e.message!!.contains(reason)) { e.toString() }
	}
	check(file.readBytes().contentEquals(original))
}

fun main(args: Array<String>) {
	val dir = createTempDir(prefix = "logicdraw-save-")
	try {
		for (version in 1..2) {
			val map = File(dir, "unknown-v$version.ldmap")
			unknownBlock(map, version, "LogicDraw Map")
			check(SaveManagers.getHeader(map)[0].startsWith("Unknown block fixture"))
			reject(map, "Unknown block type 'foreign_component' at (2, 3)") { SaveManagers.loadMap(map) }
			reject(map, "foreign_component") { SaveManagers.loadMapThumbnail(map) }
		}

		val blueprint = File(dir, "unknown.ldbp")
		unknownBlock(blueprint, 2, "LogicDraw Blueprint")
		reject(blueprint, "foreign_component") { SaveManagers.loadBlueprint(blueprint) }

		val unsupported = File(dir, "future.ldmap")
		write(unsupported) {
			it.writeUTF("LogicDraw Map")
			it.writeInt(SaveManagers.latest + 1)
		}
		reject(unsupported, "Unsupported save version") { SaveManagers.getHeader(unsupported) }
		reject(unsupported, "Unsupported save version") { SaveManagers.loadMap(unsupported) }

		val badHeader = File(dir, "bad-header.ldmap").apply { writeText("not a map") }
		reject(badHeader, "Invalid LogicDraw Map header") { SaveManagers.getHeader(badHeader) }

		val goodMap = File(dir, "good.ldmap")
		val goodBlueprint = File(dir, "good.ldbp")
		val world = ClassicWorld(8, 8, "Round trip")
		world.placeElement(Vector2i(2, 3), "nor")
		world.update(0)
		SaveManagers.saveMap(world, goodMap)
		SaveManagers.saveBlueprint(world, goodBlueprint)
		check(SaveManagers.getHeader(goodMap)[0] == world.name)
		check(SaveManagers.loadMap(goodMap)[2, 3] != null)
		val thumbnail = SaveManagers.loadMapThumbnail(goodMap)
		check(thumbnail.width == 160 && thumbnail.height == 160)
		check(thumbnail.pixels[65 * thumbnail.width + 45] == 0xffd600ff.toInt())
		check(thumbnail.pixels[0] == 0x606060ff)
		check(SaveManagers.loadBlueprint(goodBlueprint)[2, 3] != null)
		val legacyBlueprint = File(dir, "old.ldbp")
		SaveManagers.saveBlueprint(world, legacyBlueprint, legacy = true)
		check(SaveManagers.loadBlueprint(legacyBlueprint)[2, 3] != null)

		fun version3Record(file: File, type: String, size: Int, payload: IntArray) {
			DataOutputStream(file.outputStream()).use { header ->
				header.writeUTF("LogicDraw Map")
				header.writeInt(3)
				val metadata = """{"name":"Record fixture","width":8,"height":8}""".toByteArray(Charsets.UTF_8)
				header.writeInt(metadata.size)
				header.write(metadata)
				DataOutputStream(GZIPOutputStream(header)).use { body ->
					body.writeInt(1)
					body.writeInt(0)
					body.writeByte(0)
					body.writeInt(1)
					body.writeInt(2)
					body.writeInt(3)
					body.writeUTF(type)
					body.writeInt(size)
					for (value in payload) body.writeInt(value)
				}
			}
		}
		val extended = File(dir, "extended.ldmap")
		version3Record(extended, "nor", 8, intArrayOf(0, 123))
		check(SaveManagers.loadMap(extended)[2, 3] != null)
		val unknown = File(dir, "unknown-v3.ldmap")
		version3Record(unknown, "foreign_component", 4, intArrayOf(0))
		reject(unknown, "foreign_component") { SaveManagers.loadMap(unknown) }
		val incomplete = File(dir, "incomplete.ldmap")
		version3Record(incomplete, "nor", 8, intArrayOf(0))
		reject(incomplete, "ended unexpectedly") { SaveManagers.loadMap(incomplete) }
		reject(incomplete, "ended unexpectedly") { SaveManagers.loadMapThumbnail(incomplete) }

		val truncated = File(dir, "truncated.ldbp")
		truncated.writeBytes(goodBlueprint.readBytes().dropLast(5).toByteArray())
		reject(truncated, "ended unexpectedly") { SaveManagers.loadBlueprint(truncated) }

		val damaged = File(dir, "bad-trailer.ldmap")
		val bytes = goodMap.readBytes()
		bytes[bytes.size - 8] = (bytes[bytes.size - 8].toInt() xor 1).toByte()
		damaged.writeBytes(bytes)
		check(SaveManagers.getHeader(damaged)[0] == world.name)
		reject(damaged, "Corrupt GZIP trailer") { SaveManagers.loadMap(damaged) }

		reject(damaged, "Corrupt GZIP trailer") { SaveManagers.loadMapThumbnail(damaged) }

		val largeMap = File(dir, "large.ldmap")
		write(largeMap) {
			it.writeUTF("LogicDraw Map")
			it.writeInt(2)
			it.writeUTF("Large sparse topology")
			for (value in intArrayOf(8192, 4096, 0, 0, 1, 8191, 4095)) it.writeInt(value)
			it.writeUTF("description")
			it.writeInt(0)
		}
		val large = SaveManagers.loadMapThumbnail(largeMap)
		check(large.width == 240 && large.height == 120)
		check(large.pixels.last() == 0x8d6e63ff.toInt())

		val oversized = File(dir, "oversized-v1.ldmap")
		write(oversized) {
			it.writeUTF("LogicDraw Map")
			it.writeInt(1)
			for (value in listOf("Oversized", "8", "8", "Oversized")) it.writeUTF(value)
			for (value in intArrayOf(8, 8, 1, 0)) it.writeInt(value)
			it.writeByte(0)
			for (value in intArrayOf(1, 2, 3)) it.writeInt(value)
			it.writeUTF("programmer")
			it.writeInt(0)
			it.writeBoolean(false)
			it.writeBoolean(false)
			it.writeInt(0)
			it.writeInt(Int.MAX_VALUE)
		}
		reject(oversized, "oversized block payload") { SaveManagers.loadMapThumbnail(oversized) }

		val legacy = File(args[0])
		check(SaveManagers.loadMap(legacy).name == "Test")
		check(SaveManagers.loadMapThumbnail(legacy).pixels.any { it != 0x606060ff })
		val migrated = File(dir, "migrated.ldmap")
		val downgraded = File(dir, "downgraded.ldmap")
		val sharing = CircuitMetadata("Example — circuit", "Test author")
		Circuit.open(legacy).use {
			it.metadata = sharing
			it.save(migrated)
		}
		Circuit.open(migrated).use {
			check(it.name == "Test" && it.width == 100 && it.height == 100)
			check(it.metadata == sharing)
			it.save(downgraded, legacy = true)
		}
		check(SaveManagers.getHeader(downgraded)[0] == "Test (old)")
		val directExport = File(dir, "direct-v2.ldmap")
		SaveManagers.saveMap(SaveManagers.loadMap(legacy), directExport, legacy = true)
		fun payload(file: File) = GZIPInputStream(file.inputStream()).use { it.readBytes() }
		check(payload(downgraded).contentEquals(payload(directExport)))

		Circuit.create(4, 4, "Headless").use {
			it.place(1, 1, "wire")
			it.drive(1, 1, Circuit.Port.HORIZONTAL, true)
			check(it.read(1, 1, Circuit.Port.HORIZONTAL) == false)
			it.step()
			check(it.ticks == 1L && it.read(1, 1, Circuit.Port.HORIZONTAL) == true)
			it.erase(1, 1)
			it.flushEdits()
			check(it.read(1, 1, Circuit.Port.HORIZONTAL) == null)
		}

		val allocator = ClassicWorld(4, 4, "Free state pool")
		allocator.placeElement(Vector2i(1, 1), "wire")
		allocator.updateTasks()
		val id = allocator[1, 1]!!.getState(me.wieku.circuits.api.math.Axis.HORIZONTAL)!!.id
		allocator.removeElement(Vector2i(1, 1))
		SaveManagers.saveMap(allocator, File(dir, "free.ldmap"))
		allocator.placeElement(Vector2i(2, 2), "wire")
		allocator.updateTasks()
		check(allocator[2, 2]!!.getState(me.wieku.circuits.api.math.Axis.HORIZONTAL)!!.id == id)

		println("Save loading regression checks passed.")
	} finally {
		dir.deleteRecursively()
	}
}

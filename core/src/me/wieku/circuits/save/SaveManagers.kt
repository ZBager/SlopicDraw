package me.wieku.circuits.save

import me.wieku.circuits.api.element.IElement
import me.wieku.circuits.save.managers.SaveManagerVer01
import me.wieku.circuits.save.managers.SaveManagerVer02
import me.wieku.circuits.save.managers.SaveManagerVer03
import me.wieku.circuits.world.ClassicWorld
import me.wieku.circuits.world.ElementRegistry
import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object SaveManagers {
	val latest = 3

	fun loadMap(file: File): ClassicWorld = load(file, "ldmap", "LogicDraw Map")

	fun loadBlueprint(file: File): ClassicWorld = load(file, "ldbp", "LogicDraw Blueprint")

	private fun load(file: File, extension: String, magic: String): ClassicWorld =
		read(file, extension, magic) { stream, version, manager ->
			val world = manager.loadMap(stream)
			// Finish the gzip stream so a missing or damaged trailer is reported too.
			if (version < 3 && stream.read() != -1) throw IOException("Unexpected data after the circuit.")
			world
		}

	fun loadMapThumbnail(file: File): MapThumbnail =
		read(file, "ldmap", "LogicDraw Map") { stream, version, _ -> MapThumbnail.read(stream, version) }

	fun getHeader(file: File): Array<String> =
		read(file, "ldmap", "LogicDraw Map") { stream, version, manager ->
			manager.loadHeader(stream).also {
				if (version < latest) it[0] += " (old)"
			}
		}

	private fun <T> read(file: File, extension: String, magic: String,
		decode: (DataInputStream, Int, SaveManager) -> T): T {
		try {
			if (file.extension != extension) throw IOException("Expected a .$extension file.")
			return BufferedInputStream(FileInputStream(file)).use { raw ->
				raw.mark(2)
				val first = raw.read()
				val second = raw.read()
				val gzip = first == 0x1f && second == 0x8b
				if (!gzip && (first != 0 || second != magic.length)) throw IOException("Invalid $magic header.")
				raw.reset()
				val input = if (gzip) GZIPInputStream(raw) else raw
				DataInputStream(input).use { stream ->
					if (stream.readUTF() != magic) throw IOException("Invalid $magic header.")
					val version = stream.readInt()
					if (version !in 1..latest || gzip != (version < 3))
						throw IOException("Unsupported save version $version.")
					decode(stream, version, getSaveManager(version))
				}
			}
		} catch (error: EOFException) {
			throw SaveReadException(file, "The file ended unexpectedly.", error)
		} catch (error: IOException) {
			throw SaveReadException(file, error.message ?: "The file could not be read.", error)
		} catch (error: RuntimeException) {
			throw SaveReadException(file, "Invalid circuit data.", error)
		}
	}

	internal fun placeSavedElement(world: ClassicWorld, stream: DataInputStream): IElement {
		val x = stream.readInt()
		val y = stream.readInt()
		val type = stream.readUTF()
		if (!ElementRegistry.contains(type))
			throw IOException("Unknown block type '$type' at ($x, $y).")
		return world.forcePlace(x, y, type)
	}

	fun saveMap(world: ClassicWorld, file: File, legacy: Boolean = false) =
		write(world, file, "LogicDraw Map", if (legacy) 2 else latest)

	fun saveBlueprint(world: ClassicWorld, file: File, legacy: Boolean = false) =
		write(world, file, "LogicDraw Blueprint", if (legacy) 2 else latest)

	private fun write(world: ClassicWorld, file: File, magic: String, version: Int) {
		synchronized(world) {
			world.updateTasks()
			if (version < 3 && world.hasBusElements) throw IOException("Buses require save version 3.")
			val destination = file.absoluteFile
			val temporary = File.createTempFile("ld-save-", ".tmp", destination.parentFile)
			try {
				BufferedOutputStream(FileOutputStream(temporary)).use { raw ->
					val output = if (version < 3) GZIPOutputStream(raw) else raw
					DataOutputStream(output).use { stream ->
						stream.writeUTF(magic)
						stream.writeInt(version)
						getSaveManager(version).saveMap(world, stream)
					}
				}
				if (!temporary.renameTo(destination)) {
					FileInputStream(temporary).use { input ->
						FileOutputStream(destination).use { output -> input.copyTo(output) }
					}
				}
			} finally {
				temporary.delete()
			}
		}
	}


	fun getSaveManager(version: Int): SaveManager {
		if(version == 1) {
			return SaveManagerVer01()
		} else if(version == 2) {
			return SaveManagerVer02()
		} else if (version == 3) {
			return SaveManagerVer03()
		} else throw IllegalStateException("You have too old version of LogicDraw to open that world")
	}
}

class SaveReadException(file: File, reason: String, cause: Exception) : IOException("${file.name}\n$reason", cause)

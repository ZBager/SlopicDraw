package me.wieku.circuits.save

import com.badlogic.gdx.graphics.Color
import me.wieku.circuits.save.managers.SaveManagerVer01
import me.wieku.circuits.save.managers.SaveManagerVer03
import java.util.zip.GZIPInputStream
import me.wieku.circuits.world.ElementRegistry
import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

private const val PREVIEW_BYTES = 24 * 1024 * 1024

/** A topology thumbnail in RGBA8888, with the top row first. */
class MapThumbnail private constructor(private val mapWidth: Int, private val mapHeight: Int) {
	val width: Int
	val height: Int
	val pixels: IntArray

	init {
		if (mapWidth <= 0 || mapHeight <= 0) throw IOException("Invalid map dimensions.")
		val scale = minOf(240.0 / mapWidth, 160.0 / mapHeight)
		width = (mapWidth * scale).toInt().coerceIn(1, 240)
		height = (mapHeight * scale).toInt().coerceIn(1, 160)
		pixels = IntArray(width * height) { 0x606060ff }
	}

	private fun plot(x: Int, y: Int, color: Int) {
		if (x !in 0 until mapWidth || y !in 0 until mapHeight) throw IOException("Invalid block coordinates.")
		val left = (x.toLong() * width / mapWidth).toInt()
		val top = (y.toLong() * height / mapHeight).toInt()
		val right = maxOf(left + 1, ((x.toLong() + 1) * width / mapWidth).toInt())
		val bottom = maxOf(top + 1, ((y.toLong() + 1) * height / mapHeight).toInt())
		for (row in top until bottom) {
			for (column in left until right) pixels[row * width + column] = color
		}
	}

	companion object {
		internal fun read(stream: DataInputStream, version: Int): MapThumbnail {
			val metadata = if (version == 3) SaveManagerVer03.readMetadata(stream) else null
			val body = if (metadata == null) stream else GZIPInputStream(stream)
			DataInputStream(PreviewInput(body)).use { input ->
				if (version == 1) {
					// Legacy records interleave element properties with topology.
					val world = SaveManagerVer01().loadMap(input, 262_144, PREVIEW_BYTES)
					if (input.read() != -1) throw IOException("Unexpected data after the circuit.")
					val thumbnail = MapThumbnail(world.width, world.height)
					for (x in 0 until world.width) {
						if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
						for (y in 0 until world.height) {
							val element = world[x, y] ?: continue
							thumbnail.plot(x, y, (element.getIdleColor() shl 8) or 0xff)
						}
					}
					return thumbnail
				}

				val thumbnail = if (metadata != null) MapThumbnail(metadata.width, metadata.height) else {
					input.readUTF()
					MapThumbnail(input.readInt(), input.readInt())
				}
				val states = input.readInt()
				val free = input.readInt()
				if (states < 0 || free !in 0..states) throw IOException("Invalid state counts.")
				val scratch = ByteArray(8192)
				fun skip(bytes: Long) {
					var remaining = bytes
					while (remaining > 0) {
						val length = minOf(remaining, scratch.size.toLong()).toInt()
						input.readFully(scratch, 0, length)
						remaining -= length
					}
				}
				skip(states.toLong() + free.toLong() * 4)
				val count = input.readInt()
				if (count !in 0..1_000_000) throw IOException("Map exceeds the preview limit.")
				val colors = ElementRegistry.brushes.mapValues { (_, color) -> Color.rgba8888(color) or 0xff }
				repeat(count) {
					val x = input.readInt()
					val y = input.readInt()
					val type = input.readUTF()
					val color = colors[type] ?: throw IOException("Unknown block type '$type'.")
					thumbnail.plot(x, y, color)
					if (version == 3) skip(SaveManagerVer03.readRecordSize(input).toLong())
				}
				if (version == 3) {
					if (input.read() != -1) throw IOException("Unexpected data after the circuit.")
					return thumbnail
				}
				val properties = input.readInt()
				if (properties !in 0..count) throw IOException("Invalid property record count.")
				// Properties are irrelevant to the topology image. Finish reading to check the gzip trailer.
				var payloadBytes = 0
				while (true) {
					val length = input.read(scratch)
					if (length == -1) break
					payloadBytes += length
				}
				if (payloadBytes < properties * 8) throw IOException("Incomplete property records.")
				return thumbnail
			}
		}
	}
}

private class PreviewInput(input: InputStream) : FilterInputStream(input) {
	private var remaining = PREVIEW_BYTES

	private fun limit() {
		if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
		if (remaining == 0) throw IOException("Map exceeds the preview limit.")
	}

	override fun read(): Int {
		limit()
		val value = super.read()
		if (value >= 0) remaining--
		return value
	}

	override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
		if (length == 0) return 0
		limit()
		val count = super.read(bytes, offset, minOf(length, remaining))
		if (count > 0) remaining -= count
		return count
	}
}

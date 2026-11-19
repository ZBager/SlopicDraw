package me.wieku.circuits.render.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Scaling
import com.kotcrab.vis.ui.widget.VisLabel
import me.wieku.circuits.save.MapThumbnail
import me.wieku.circuits.save.SaveManagers
import me.wieku.circuits.save.SaveReadException
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class MapPreview {
	val image = Image().apply { setScaling(Scaling.fit) }
	val status = VisLabel("Select a map to preview it.")
	private var texture: Texture? = null
	private var request: Future<*>? = null
	private var generation = 0L
	private var worker: ThreadPoolExecutor? = null

	fun show(file: File) {
		val ticket = ++generation
		request?.cancel(true)
		worker?.purge()
		clearImage()
		status.setText("Loading preview…")
		val executor = worker ?: ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
			LinkedBlockingQueue<Runnable>()) { job ->
			Thread(job, "map-preview").apply { isDaemon = true }
		}.also { worker = it }

		val app = Gdx.app
		request = executor.submit {
			val key = FileVersion(file.absoluteFile, file.length(), file.lastModified())
			val thumbnail = try {
				val cached = synchronized(cache) { cache[key] }
				cached ?: SaveManagers.loadMapThumbnail(file).also {
					if (!Thread.currentThread().isInterrupted && key.matchesFile()) {
						synchronized(cache) { cache[key] = it }
					}
				}
			} catch (error: SaveReadException) {
				null
			}
			if (!Thread.currentThread().isInterrupted) {
				app.postRunnable {
					if (generation == ticket) {
						if (thumbnail != null && key.matchesFile()) display(thumbnail)
						else status.setText("Preview unavailable.")
					}
				}
			}
		}
	}

	private fun display(thumbnail: MapThumbnail) {
		val pixmap = Pixmap(thumbnail.width, thumbnail.height, Pixmap.Format.RGBA8888)
		try {
			pixmap.setBlending(Pixmap.Blending.None)
			for (y in 0 until thumbnail.height) {
				for (x in 0 until thumbnail.width) {
					pixmap.drawPixel(x, y, thumbnail.pixels[y * thumbnail.width + x])
				}
			}
			val result = Texture(pixmap)
			texture = result
			image.drawable = TextureRegionDrawable(TextureRegion(result))
			status.setText("")
		} finally {
			pixmap.dispose()
		}
	}

	private fun clearImage() {
		image.drawable = null
		texture?.dispose()
		texture = null
	}

	fun close() {
		++generation
		request?.cancel(true)
		request = null
		worker?.shutdownNow()
		worker = null
		clearImage()
	}

	private data class FileVersion(val file: File, val size: Long, val modified: Long) {
		fun matchesFile() = file.isFile && size == file.length() && modified == file.lastModified()
	}

	private companion object {
		val cache = object : LinkedHashMap<FileVersion, MapThumbnail>(16, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FileVersion, MapThumbnail>?) = size > 12
		}
	}
}

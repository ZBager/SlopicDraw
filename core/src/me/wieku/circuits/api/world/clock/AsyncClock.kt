package me.wieku.circuits.api.world.clock

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AsyncClock (private val updatable: Updatable<*>, tickRate: Int)  {
	private var sleepTime: Long = 1000000000L/tickRate
	var tickRate: Int = tickRate
	set (value) {
		if(value<0) IllegalStateException("Tick Rate has to be positive")
		field = value
		sleepTime = 1000000000L/value
	}

	@Volatile var currentTPS: Float = 0f

	@Volatile private var task: ScheduledFuture<*>? = null
	private var delta: Boolean = false
	private var currentTick: Long = 0
	private val tickLock = Any()
	@Volatile private var generation = 0L

	@Suppress("UNCHECKED_CAST")
	@Synchronized
	fun start() {
		if(isRunning()) return

		val scheduledGeneration = ++generation
		val batch = updatable as? Updatable.ByTickBatch
		val count = batch?.ticksPerCall(tickRate) ?: 1
		val period = 1_000_000_000L * count / tickRate
		var lastCheck = System.nanoTime()
		var timeCheck = 0L
		var tempTPS = 0L
		task = executor.scheduleAtFixedRate({

			var updateTime = System.nanoTime() - lastCheck
			lastCheck = System.nanoTime()

			synchronized(tickLock) {
				if (generation != scheduledGeneration) return@scheduleAtFixedRate
				val advanced = if (batch == null) {
					(updatable as Updatable<Number>).update(if (delta) updateTime / 1000000000f else currentTick)
					1
				} else batch.advance(currentTick, count) { generation == scheduledGeneration }
				currentTick += advanced
				timeCheck += updateTime
				tempTPS += advanced
				if (timeCheck >= 1_000_000_000L) {
					currentTPS = (tempTPS * (1_000_000_000.0 / timeCheck)).toFloat()
					tempTPS = 0
					timeCheck = 0
				}
			}

		}, 0, period, TimeUnit.NANOSECONDS)
	}

	@Synchronized
	fun stop() {
		++generation
		task?.cancel(false)
	}

	fun isRunning() : Boolean = task != null && !task!!.isCancelled && !task!!.isDone

	fun getTPS() = currentTPS

	@Suppress("UNCHECKED_CAST")
	fun step() {
		executor.schedule({
			synchronized(tickLock) {
				(updatable as Updatable<Number>).update(if (delta) sleepTime else currentTick)
				++currentTick
			}
		}, 0, TimeUnit.NANOSECONDS)
	}

	companion object {
		val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(5)

		fun dispose() {
			executor.shutdown()
			var terminated = executor.awaitTermination(2, TimeUnit.SECONDS)
			if(!terminated)
				executor.shutdownNow()
		}
	}

	init {
		when (updatable) {
			is Updatable.ByTick -> delta = false
			is Updatable.ByDelta -> delta = true
			else -> IllegalStateException("Updatable must be ByTick or ByDelta!")
		}
	}

}
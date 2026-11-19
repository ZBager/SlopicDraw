package me.wieku.circuits.desktop

import com.badlogic.gdx.Files
import com.badlogic.gdx.backends.lwjgl.LwjglApplication
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration
import me.wieku.circuits.desktop.simulation.TerniSimulation
import me.wieku.circuits.world.simulation.SimulationBackends
import me.wieku.circuits.Main
import me.wieku.circuits.utils.Version

fun main(args: Array<String>) {
	if (System.getProperty("logicdraw.backend") == "terni") {
		val os = System.getProperty("os.name")
		if (os.startsWith("Windows") || os == "Linux") {
			SimulationBackends.create = { TerniSimulation(java.lang.Boolean.getBoolean("logicdraw.terni.software")) }
		} else System.err.println("Classic (Terni is unavailable on " + os + ")")
	}
	if (args.firstOrNull() == "--headless") {
		try {
			runHeadless(args.copyOfRange(1, args.size))
		} catch (error: Exception) {
			System.err.println(error.message)
			kotlin.system.exitProcess(1)
		}
		return
	}
	val config = LwjglApplicationConfiguration()
	config.width = 1024
	config.height = 768
	config.title = "LogicDraw ${Version.version}"
	config.addIcon("assets/logo/logo32.png", Files.FileType.Internal)
	LwjglApplication(Main, config)
}

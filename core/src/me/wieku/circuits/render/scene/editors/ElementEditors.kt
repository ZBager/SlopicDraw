package me.wieku.circuits.render.scene.editors

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisWindow
import com.kotcrab.vis.ui.widget.spinner.IntSpinnerModel
import com.kotcrab.vis.ui.widget.spinner.Spinner
import ktx.vis.*
import me.wieku.circuits.api.element.IElement
import me.wieku.circuits.render.scene.onClickS
import me.wieku.circuits.world.ClassicWorld
import me.wieku.circuits.world.ElementRegistry
import me.wieku.circuits.world.elements.bus.BusTap
import me.wieku.circuits.world.elements.bus.BUS_WIDTH
import me.wieku.circuits.world.elements.gates.DelayGate
import me.wieku.circuits.world.elements.gates.KeyGate
import me.wieku.circuits.world.elements.gates.ProgramInputGate
import me.wieku.circuits.world.elements.gates.PWMGate
import me.wieku.circuits.world.elements.gates.RamGate
import me.wieku.circuits.world.elements.gates.StopGate
import me.wieku.circuits.world.elements.io.Input as CircuitInput

internal class ElementEditors(private val stage: Stage, private val world: ClassicWorld) {
	private var active: VisWindow? = null

	fun open(element: IElement) {
		if (active?.stage != null) return
		when (element) {
			is BusTap -> show(element) {
				val bit = integer("Bit", element.bit, 0, BUS_WIDTH - 1)
				return@show { element.bit = bit.value }
			}
			is DelayGate -> show(element) {
				val delay = integer("Delay", element.delay, 1, 10_000)
				return@show { element.delay = delay.value }
			}
			is PWMGate -> show(element) {
				val period = integer("Period", element.period, 1, 100_000)
				val duty = integer("Duty cycle", element.duty, 0, 100)
				return@show {
					element.period = period.value
					element.duty = duty.value
				}
			}
			is KeyGate -> show(element) {
				val readKey = keyBinding(element.keycode)
				val bistable = toggle("Bistable", element.bistable)
				return@show {
					element.keycode = readKey()
					element.bistable = bistable()
				}
			}
			is StopGate -> show(element) {
				val enabled = toggle("Enabled", element.enabled)
				return@show { element.enabled = enabled() }
			}
			is CircuitInput -> show(element) {
				val inverted = toggle("Inverted signal", element.inverted)
				return@show { element.inverted = inverted() }
			}
			is ProgramInputGate -> show(element) {
				val editor = hex(synchronized(world) { element.bytes.copyOf() })
				return@show { element.bytes = editor.saveToBytes() }
			}
			is RamGate -> show(element) {
				val editor = hex(synchronized(world) { element.memory.copyOf() })
				return@show { element.memory = editor.saveToBytes() }
			}
		}
	}

	private fun show(element: IElement, build: Form.() -> (() -> Unit)) {
		val dialog = window("${ElementRegistry.names[element.javaClass]} settings") {
			isModal = true
			addCloseButton()
			val form = Form(this)
			val apply = form.build()
			val ok = textButton("OK").cell(growX = true)
			ok.onClickS {
				if (form.isValid()) {
					synchronized(world) {
						world.synchronizeSimulation()
						if (world.getElement(element.getPosition()) === element) apply()
					}
					fadeOut()
				}
			}
			addListener(object : ChangeListener() {
				override fun changed(event: ChangeEvent?, actor: Actor?) {
					ok.isDisabled = !form.isValid()
				}
			})
			ok.isDisabled = !form.isValid()
			pack()
			centerWindow()
		}
		active = dialog
		stage.addActor(dialog.fadeIn())
		stage.keyboardFocus = dialog
	}

	private inner class Form(private val dialog: KVisWindow) {
		private val spinners = ArrayList<Spinner>()
		private var captureKey: ((Int) -> Unit)? = null

		init {
			dialog.addCaptureListener(object : InputListener() {
				override fun keyDown(event: InputEvent?, keycode: Int): Boolean {
					val capture = captureKey ?: return false
					captureKey = null
					capture(keycode)
					event?.stop()
					return true
				}
			})
			dialog.addListener(object : InputListener() {
				override fun keyDown(event: InputEvent?, keycode: Int): Boolean {
					if (keycode == Input.Keys.ESCAPE) dialog.fadeOut()
					return true
				}

				override fun keyUp(event: InputEvent?, keycode: Int) = true
				override fun keyTyped(event: InputEvent?, character: Char) = true
			})
		}

		fun isValid() = spinners.all { it.textField.isInputValid }

		fun integer(label: String, value: Int, min: Int, max: Int): IntSpinnerModel {
			val model = IntSpinnerModel(value, min, max, 1)
			val spinner = Spinner(label, model)
			dialog.add(spinner).growX().pad(2f)
			spinners.add(spinner)
			dialog.row()
			return model
		}

		fun toggle(label: String, value: Boolean): () -> Boolean {
			val button = dialog.checkBox(label) { isChecked = value }
			dialog.row()
			return { button.isChecked }
		}

		fun hex(bytes: ByteArray): HexEditor {
			val editor = HexEditor()
			editor.loadFromBytes(bytes)
			dialog.add(editor)
			dialog.row()
			return editor
		}

		fun keyBinding(initial: Int): () -> Int {
			var key = initial
			dialog.table {
				label("Key")
				val button = textButton(Input.Keys.toString(key)).cell(width = 120f)
				button.onClickS {
					button.setText("Press a key")
					stage.keyboardFocus = dialog
					captureKey = { pressed ->
						if (pressed != Input.Keys.ESCAPE) key = pressed
						button.setText(Input.Keys.toString(key))
					}
				}
			}
			dialog.row()
			return { key }
		}
	}
}

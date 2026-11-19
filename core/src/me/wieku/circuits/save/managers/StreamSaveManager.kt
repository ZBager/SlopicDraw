package me.wieku.circuits.save.managers

import me.wieku.circuits.save.SaveManager
import java.io.DataInputStream
import java.io.DataOutputStream

abstract class StreamSaveManager : SaveManager {
	protected lateinit var inputStream: DataInputStream
	protected lateinit var outputStream: DataOutputStream

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

	override fun getInteger(): Int = inputStream.readInt()

	override fun getLong(): Long = inputStream.readLong()

	override fun getString(): String = inputStream.readUTF()

}

package com.accessbridgeai.screen

import com.google.gson.GsonBuilder

data class DumpMeta(val formatVersion: Int, val packageName: String, val capturedAtMs: Long)
data class DumpFile(val meta: DumpMeta, val screen: RawScreen)

object DumpJson {
    const val FORMAT_VERSION = 1
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun write(dump: DumpFile): String = gson.toJson(dump)
    fun read(json: String): DumpFile = gson.fromJson(json, DumpFile::class.java)
}
package com.accessbridgeai.screen

import java.io.File

object Fixtures {
    private val loader = Fixtures::class.java.classLoader!!

    fun load(name: String): DumpFile {
        val stream = loader.getResourceAsStream("fixtures/$name.screen.json")
            ?: error("fixture not found: $name")
        return DumpJson.read(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    fun allNames(): List<String> {
        val dir = loader.getResource("fixtures") ?: return emptyList()
        return File(dir.toURI()).listFiles { f -> f.name.endsWith(".screen.json") }!!
            .map { it.name.removeSuffix(".screen.json") }.sorted()
    }
}
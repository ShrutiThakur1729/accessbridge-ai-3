package com.accessbridgeai.detect

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object Expectations {
    fun load(): Map<String, Map<String, List<Int>>> {
        val text = Expectations::class.java.classLoader!!
            .getResourceAsStream("fixtures/expectations.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val type = object : TypeToken<Map<String, Map<String, List<Int>>>>() {}.type
        return Gson().fromJson(text, type)
    }
}
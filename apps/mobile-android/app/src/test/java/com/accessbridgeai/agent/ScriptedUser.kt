package com.accessbridgeai.agent

class ScriptedUser(private val replies: List<String?>) : User {
    private var next = 0
    val said = mutableListOf<String>()
    val asked = mutableListOf<String>()
    override suspend fun say(text: String) { said += text }
    override suspend fun ask(question: String): String? { asked += question; return replies.getOrNull(next++) }
}
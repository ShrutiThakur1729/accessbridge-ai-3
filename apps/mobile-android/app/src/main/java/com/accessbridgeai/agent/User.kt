package com.accessbridgeai.agent

interface User {
    suspend fun say(text: String)
    /** Speak the question, listen, return the reply. null = no answer in time. */
    suspend fun ask(question: String): String?
}
package com.accessbridgeai.utils

import java.util.UUID

/**
 * Singleton object to manage persistent session state and activity timeouts.
 * Tracks session duration and allows reset for new user intents.
 */
object SessionManager {
    private var currentSessionId: String = UUID.randomUUID().toString()
    private var lastActivityTime: Long = System.currentTimeMillis()
    private const val SESSION_TIMEOUT_MS = 5 * 60 * 1000 // 5 minutes

    fun getSessionId(): String {
        val now = System.currentTimeMillis()
        if (now - lastActivityTime > SESSION_TIMEOUT_MS) {
            resetSession()
        }
        return currentSessionId
    }

    fun updateActivity() {
        lastActivityTime = System.currentTimeMillis()
    }

    fun resetSession() {
        currentSessionId = UUID.randomUUID().toString()
        lastActivityTime = System.currentTimeMillis()
    }
}

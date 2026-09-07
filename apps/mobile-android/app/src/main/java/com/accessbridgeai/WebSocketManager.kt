package com.accessbridgeai

import android.util.Log
import com.accessbridgeai.data.SessionManager
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val serverUrl: String, // e.g., "ws://10.0.2.2:3000" for Android Emulator targeting local backend
    private val sessionManager: SessionManager,
    private val onMessageReceived: (JSONObject) -> Unit,
    private val onConnectionStatusChanged: (Boolean) -> Unit
) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep socket alive indefinitely
        .pingInterval(60, TimeUnit.SECONDS)   // Send keep-alive pings
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var shouldReconnect = true
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectAttempt = 0
    private val pendingPayloads = mutableListOf<JSONObject>()

    fun connect() {
        shouldReconnect = true
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                reconnectAttempt = 0
                Log.d("WebSocketManager", "Connected to Gateway-Core successfully!")
                onConnectionStatusChanged(true)
                flushPendingPayloads()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val jsonResponse = JSONObject(text)
                    onMessageReceived(jsonResponse)
                } catch (e: Exception) {
                    Log.e("WebSocketManager", "Failed to parse incoming JSON payload: $text", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.d("WebSocketManager", "Connection closed: $reason")
                onConnectionStatusChanged(false)
                if (shouldReconnect) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.e("WebSocketManager", "WebSocket failure: ${t.message}")
                onConnectionStatusChanged(false)
                if (shouldReconnect) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun flushPendingPayloads() {
        handler.post {
            val toSend = synchronized(pendingPayloads) {
                val filtered = pendingPayloads.filter { payload ->
                    val type = payload.optString("type")
                    if (type == "USER_INTENT") {
                        val transcript = payload.optString("transcript")
                        transcript.trim().isNotEmpty()
                    } else {
                        true
                    }
                }
                val result = ArrayList(filtered)
                pendingPayloads.clear()
                result
            }
            Log.d("WebSocketManager", "Flushing ${toSend.size} valid pending payloads.")
            toSend.forEach { sendPayload(it) }
        }
    }

    private fun scheduleReconnect() {
        val delay = (Math.min(Math.pow(2.0, reconnectAttempt.toDouble()).toLong(), 30L)) * 1000L
        Log.d("WebSocketManager", "Scheduling reconnect in $delay ms (attempt $reconnectAttempt)")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (shouldReconnect && !isConnected) {
                reconnectAttempt++
                connect()
            }
        }, delay)
    }

    fun purgePendingPayloads() {
        synchronized(pendingPayloads) {
            Log.d("WebSocketManager", "Purging ${pendingPayloads.size} stale payloads from queue.")
            pendingPayloads.clear()
        }
    }

    // Send payload (e.g., user speech transcript or screen context) upstream to Service 01
    fun sendPayload(payload: JSONObject, purgeQueue: Boolean = false): Boolean {
        if (purgeQueue) {
            purgePendingPayloads()
        }
        try {
            // Ensure session_id is present in all outgoing payloads
            if (!payload.has("session_id")) {
                payload.put("session_id", sessionManager.getSessionId())
            }

            // Pre-send guard: Do not send USER_INTENT if transcript or user_prompt is empty
            if (payload.optString("type") == "USER_INTENT") {
                val transcript = payload.optString("transcript") // Match AccessibilityAgentService usage
                if (transcript.isBlank()) {
                    Log.w("WebSocketManager", "Aborting send: USER_INTENT has empty/blank transcript.")
                    return false
                }
            }

            // GUARD: If not connected, queue and trigger reconnect
            if (webSocket == null || !isConnected) {
                Log.w("WebSocketManager", "Socket disconnected. Queuing payload: ${payload.optString("type")}")
                synchronized(pendingPayloads) {
                    if (pendingPayloads.size < 50) { // Safety limit for memory
                        pendingPayloads.add(payload)
                    }
                }
                if (shouldReconnect) scheduleReconnect()
                return false 
            }

            val success = webSocket?.send(payload.toString()) ?: false
            if (!success) {
                Log.e("WebSocketManager", "Failed to send payload through socket. Queuing for retry.")
                synchronized(pendingPayloads) {
                    pendingPayloads.add(payload)
                }
            } else {
                Log.d("WebSocketManager", "Payload sent successfully: ${payload.optString("type")}")
            }
            return success
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Send error caught: ${e.message}. Payload queued for auto-retry.")
            synchronized(pendingPayloads) {
                pendingPayloads.add(payload)
            }
            isConnected = false
            return false
        }
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "Client shutting down")
        webSocket = null
        isConnected = false
        onConnectionStatusChanged(false)
        handler.removeCallbacksAndMessages(null)
    }
}
package com.accessbridgeai

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val serverUrl: String, // e.g., "ws://10.0.2.2:3000" for Android Emulator targeting local backend
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

    fun connect() {
        shouldReconnect = true
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                reconnectAttempt = 0
                Log.d("WebSocketManager", "Connected to Gateway-Core successfully!")
                onConnectionStatusChanged(true)
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

    private fun scheduleReconnect() {
        val delay = (Math.min(Math.pow(2.0, reconnectAttempt.toDouble()).toLong(), 30L)) * 1000L
        Log.d("WebSocketManager", "Scheduling reconnect in $delay ms (attempt $reconnectAttempt)")
        handler.postDelayed({
            if (shouldReconnect && !isConnected) {
                reconnectAttempt++
                connect()
            }
        }, delay)
    }

    // Send payload (e.g., user speech transcript or screen context) upstream to Service 01
    fun sendPayload(payload: JSONObject): Boolean {
        // GUARD: If not connected, don't even try OkHttp. 
        // This prevents the 40ms flood of socket errors.
        if (webSocket == null || !isConnected) {
            return false 
        }

        // Pre-send guard: Do not send USER_INTENT if transcript or user_prompt is empty
        if (payload.optString("type") == "USER_INTENT") {
            val transcript = payload.optString("transcript")
            val userPrompt = payload.optString("user_prompt")
            if (transcript.isBlank() || userPrompt.isBlank()) {
                Log.w("WebSocketManager", "Aborting send: USER_INTENT has empty/blank transcript or user_prompt.")
                return false
            }
        }

        return try {
            webSocket?.send(payload.toString()) ?: false
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Send error: ${e.message}")
            isConnected = false
            false
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
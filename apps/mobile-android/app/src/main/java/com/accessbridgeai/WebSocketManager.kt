package com.accessbridgeai

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val serverUrl: String,    // e.g., "ws://10.0.2.2:3000"
    private val telemetryUrl: String, // e.g., "ws://10.0.2.2:3001"
    private val onMessageReceived: (JSONObject) -> Unit,
    private val onConnectionStatusChanged: (Boolean) -> Unit
) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(60, TimeUnit.SECONDS)
        .build()

    private var mainWebSocket: WebSocket? = null
    private var telemetryWebSocket: WebSocket? = null
    
    private var isMainConnected = false
    private var isTelemetryConnected = false
    
    private var shouldReconnect = true
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectAttempt = 0

    fun connect() {
        shouldReconnect = true
        connectMain()
        connectTelemetry()
    }

    private fun connectMain() {
        val request = Request.Builder().url(serverUrl).build()
        mainWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isMainConnected = true
                reconnectAttempt = 0
                Log.d("WebSocketManager", "Main socket connected to $serverUrl")
                onConnectionStatusChanged(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val jsonResponse = JSONObject(text)
                    onMessageReceived(jsonResponse)
                } catch (e: Exception) {
                    Log.e("WebSocketManager", "Failed to parse main socket JSON: $text", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isMainConnected = false
                Log.d("WebSocketManager", "Main socket closed: $reason")
                checkOverallStatus()
                if (shouldReconnect) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isMainConnected = false
                Log.e("WebSocketManager", "Main socket failure: ${t.message}")
                checkOverallStatus()
                if (shouldReconnect) scheduleReconnect()
            }
        })
    }

    private fun connectTelemetry() {
        val request = Request.Builder().url(telemetryUrl).build()
        telemetryWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isTelemetryConnected = true
                Log.d("WebSocketManager", "Telemetry socket connected to $telemetryUrl")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isTelemetryConnected = false
                Log.d("WebSocketManager", "Telemetry socket closed: $reason")
                if (shouldReconnect) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isTelemetryConnected = false
                Log.e("WebSocketManager", "Telemetry socket failure: ${t.message}")
                if (shouldReconnect) scheduleReconnect()
            }
        })
    }

    private fun checkOverallStatus() {
        onConnectionStatusChanged(isMainConnected)
    }

    private fun scheduleReconnect() {
        handler.removeCallbacksAndMessages("reconnect")
        val delay = (Math.min(Math.pow(2.0, reconnectAttempt.toDouble()).toLong(), 30L)) * 1000L
        handler.postAtTime({
            if (shouldReconnect) {
                reconnectAttempt++
                if (!isMainConnected) connectMain()
                if (!isTelemetryConnected) connectTelemetry()
            }
        }, "reconnect", android.os.SystemClock.uptimeMillis() + delay)
    }

    fun sendPayload(payload: JSONObject): Boolean {
        if (mainWebSocket == null || !isMainConnected) return false

        if (payload.optString("type") == "USER_INTENT") {
            val transcript = payload.optString("transcript")
            val userPrompt = payload.optString("user_prompt")
            if (transcript.isBlank() || userPrompt.isBlank()) return false
        }

        return try {
            mainWebSocket?.send(payload.toString()) ?: false
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Main send error: ${e.message}")
            false
        }
    }

    /**
     * Routes telemetry data strictly through port 3001 (telemetryUrl).
     */
    fun sendMessage(text: String): Boolean {
        if (telemetryWebSocket == null || !isTelemetryConnected) {
            Log.w("WebSocketManager", "Telemetry socket not connected. Dropping message.")
            return false
        }
        return try {
            telemetryWebSocket?.send(text) ?: false
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Telemetry send error: ${e.message}")
            false
        }
    }

    fun disconnect() {
        shouldReconnect = false
        handler.removeCallbacksAndMessages(null)
        
        mainWebSocket?.close(1000, "Client shutdown")
        mainWebSocket = null
        isMainConnected = false
        
        telemetryWebSocket?.close(1000, "Client shutdown")
        telemetryWebSocket = null
        isTelemetryConnected = false
        
        onConnectionStatusChanged(false)
    }
}

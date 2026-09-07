package com.accessbridgeai

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.accessbridgeai.data.TelemetryLog
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

class AccessibilityAgentService : AccessibilityService() {

    private var voiceAssistantManager: VoiceAssistantManager? = null
    private var webSocketManager: WebSocketManager? = null
    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript

    private val _speechState = MutableStateFlow("IDLE")
    val speechState: StateFlow<String> = _speechState

    private val _connectionStatus = MutableStateFlow(false)
    val connectionStatus: StateFlow<Boolean> = _connectionStatus

    private var currentTaskId: String? = null
    private var currentOriginalIntent: String? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeDirectiveJob: Job? = null
    private var windowChangeDeferred: CompletableDeferred<Unit>? = null
    private val executionAttempts = mutableMapOf<String, Int>()

    // Phase 1 Universal DOM Extraction metadata
    private var isInputFocusedInCapture = false
    private var focusedHintInCapture: String? = null
    private var activePackageInCapture: String? = null

    data class ActionResult(
        val success: Boolean,
        val status: String,
        val errorMessage: String? = null,
        val packageLaunched: String? = null,
        val nodeContext: JSONObject? = null
    )

    companion object {
        private val _instance = MutableStateFlow<AccessibilityAgentService?>(null)
        val serviceInstance: StateFlow<AccessibilityAgentService?> = _instance

        var instance: AccessibilityAgentService?
            get() = _instance.value
            private set(value) {
                _instance.value = value
            }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        
        // Configure service flags programmatically for maximum reliability
        val info = serviceInfo
        info.flags = info.flags or 
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or 
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
        serviceInfo = info

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            accessibilityButtonController.registerAccessibilityButtonCallback(
                object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController) {
                        Log.d("AccessibilityAgent", "Accessibility Button Clicked - Triggering Assistant")
                        startAssistant()
                    }

                    override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
                        Log.d("AccessibilityAgent", "Accessibility Button Availability Changed: $available")
                    }
                }
            )
        }

        instance = this
        Log.d("AccessibilityAgent", "Accessibility Service Connected & Active!")
        initializeComponents()
    }

    fun startAssistant() {
        val taskId = java.util.UUID.randomUUID().toString()
        currentTaskId = taskId
        
        // 1. Acknowledge user with chime
        voiceAssistantManager?.playChime()

        // 2. Trigger local Speech-to-Text listening
        serviceScope.launch(Dispatchers.Main) {
            voiceAssistantManager?.startListening()
        }
    }

    private fun initializeComponents() {
        webSocketManager = WebSocketManager(
            serverUrl = "ws://10.241.151.184:3000",
            telemetryUrl = "ws://10.241.151.184:3001",
            onMessageReceived = { json -> handleBackendDirective(json) },
            onConnectionStatusChanged = { connected ->
                val wasConnected = _connectionStatus.value
                Log.d("AccessibilityAgent", "Main WebSocket connected: $connected")
                _connectionStatus.value = connected
                if (wasConnected && !connected) {
                    voiceAssistantManager?.stopAudioStreaming()
                    serviceScope.launch(Dispatchers.Main) {
                        Toast.makeText(this@AccessibilityAgentService, "Server Connection Lost", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        webSocketManager?.connect()

        voiceAssistantManager = VoiceAssistantManager(
            context = this,
            onSpeechRecognized = { transcript ->
                Log.d("AccessibilityAgent", "Final Transcript: $transcript")
                _partialTranscript.value = transcript
                sendUserIntent(transcript)
            },
            onSpeechStateChanged = { state ->
                Log.d("AccessibilityAgent", "Speech State: $state")
                _speechState.value = state
                if (state == "LISTENING") {
                    _partialTranscript.value = "" // Clear when starting, not finishing
                }
            },
            onPartialResults = { partial ->
                Log.d("AccessibilityAgent", "Partial Transcript: $partial")
                _partialTranscript.value = partial
            }
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // Principal Engineer Note: Screen scraping only happens after state is fully settled.
        // We trigger the completion specifically on TYPE_WINDOW_STATE_CHANGED for reliability.
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.d("AccessibilityAgent", "Window state settled: ${event.packageName}")
                windowChangeDeferred?.complete(Unit)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // We could optionally complete here, but window state is the strongest signal for "settled"
            }
        }
    }

    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        // Handled by system shortcut (Volume keys), but could be used here if flagRequestFilterKeyEvents was set
        return super.onKeyEvent(event)
    }

    fun sendPayload(payload: JSONObject) {
        val success = webSocketManager?.sendPayload(payload) ?: false
        if (success) {
            Log.d("AccessibilityAgent", "Successfully queued payload for transmission.")
        } else {
            val wasConnected = _connectionStatus.value
            Log.e("AccessibilityAgent", "Failed to send payload via WebSocket. Connection state: $wasConnected")
            if (wasConnected) {
                _connectionStatus.value = false
                voiceAssistantManager?.stopAudioStreaming()
                serviceScope.launch(Dispatchers.Main) {
                    Toast.makeText(this@AccessibilityAgentService, "Server Connection Lost", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getIsoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    /**
     * LOCAL DEBUG: Prints the JSON payload matching the WebSocket format directly to Logcat.
     * Allows local verification of DOM extraction and metadata enrichment.
     */
    fun testOfflineDomCompression() {
        serviceScope.launch {
            Log.d("AccessibilityAgent", "Running offline Phase 1 DOM extraction test...")
            
            // Post-Transition Settle Delay for screen capture
            delay(1200)

            val screenState = captureScreenStateWithRetry()
            val payload = JSONObject().apply {
                put("type", "OFFLINE_TEST")
                put("task_id", "test-offline-task")
                put("timestamp", System.currentTimeMillis())
            }
            attachScreenContext(payload, screenState)
            Log.d("AccessibilityAgent", "OFFLINE_MOCK_PAYLOAD:\n${payload.toString(2)}")
        }
    }

    private fun sendUserIntent(transcript: String) {
        val taskId = java.util.UUID.randomUUID().toString()
        currentTaskId = taskId
        currentOriginalIntent = transcript
        executionAttempts.clear()
        LoopDetector.clearHistory()
        
        serviceScope.launch {
            // Post-Transition Settle Delay for screen capture (Initial Intent)
            delay(1200)

            val screenState = captureScreenStateWithRetry()
            val payload = JSONObject().apply {
                put("type", "USER_INTENT")
                put("task_id", taskId)
                put("transcript", transcript)
                put("user_prompt", transcript)
                put("timestamp", System.currentTimeMillis())
            }
            attachScreenContext(payload, screenState)
            Log.d("AccessibilityAgent", "Sending Payload: ${payload.toString(2)}")
            sendPayload(payload)
        }
    }

    private fun handleBackendDirective(json: JSONObject) {
        // 1. Immediate termination for all error conditions (Empty, 400, or Error Directive)
        val status = json.optInt("status", 200)
        val error = json.optString("error")
        val isEmpty = json.length() == 0

        if (isEmpty || status == 400 || (error.isNotEmpty() && error != "NONE")) {
            val notice = when {
                isEmpty -> "Empty server response."
                status == 400 -> "Server returned a 400 error."
                else -> "Server error: $error"
            }

            // Objective 5: Telemetry - Failure Detected
            val telemetry = TelemetryLog(
                sessionId = currentTaskId ?: "unknown",
                targetAppPackage = "N/A",
                failedAction = "BACKEND_DIRECTIVE",
                failureReason = notice,
                timestamp = getIsoTimestamp()
            )
            webSocketManager?.sendMessage(Gson().toJson(telemetry))

            Log.e("AccessibilityAgent", "Backend failure intercepted: $notice")
            
            executionAttempts.clear()
            voiceAssistantManager?.stopAllOperations()
            activeDirectiveJob?.cancel()

            serviceScope.launch {
                Toast.makeText(this@AccessibilityAgentService, notice, Toast.LENGTH_LONG).show()
                voiceAssistantManager?.speak(notice)
            }
            return
        }

        // 2. Handle non-UI directives immediately (can stay on background/caller thread)
        val audioResponse = json.optString("audio_response")
        if (audioResponse.isNotBlank()) {
            voiceAssistantManager?.playAudioResponse(audioResponse)
        }

        if (json.optBoolean("stop_listening", false)) {
            voiceAssistantManager?.stopAudioStreaming()
        }

        // 3. Transition to Main thread for UI-dependent actions and state updates
        activeDirectiveJob?.cancel()
        activeDirectiveJob = serviceScope.launch {
            try {
                val speechResponse = json.optString("speech_response")
                val actionType = json.optString("action_type")
                val targetText = json.optString("target_text")
                val packageName = json.optString("package_name")
                val textToType = json.optString("text_to_type")
                val scrollDirection = json.optString("scroll_direction")
                val executionMode = json.optString("execution_mode")
                val requiresBiometric = json.optBoolean("requires_biometric", false)
                val isCompleted = json.optBoolean("is_completed", false)
                val taskId = json.optString("task_id")
                val tapX = if (json.has("tap_x") && !json.isNull("tap_x")) json.optDouble("tap_x") else Double.NaN
                val tapY = if (json.has("tap_y") && !json.isNull("tap_y")) json.optDouble("tap_y") else Double.NaN

                if (taskId.isNotEmpty()) {
                    currentTaskId = taskId
                }

                Log.d("AccessibilityAgent", "Processing directive: $actionType, task_id: $currentTaskId")

                // Local Loop Detection Logic Update
                if (actionType == "CLICK" || actionType == "TYPE_TEXT") {
                    val currentScreenState = captureScreenStateWithRetry()
                    val elements = currentScreenState.optJSONArray("screenElements") ?: JSONArray()
                    val domHash = LoopDetector.calculateDomHash(elements)
                    
                    if (LoopDetector.isLoopDetected(packageName ?: "", domHash, actionType, targetText ?: "", elements.length())) {
                        Log.e("AccessibilityAgent", "LOOP DETECTED: Action $actionType on $targetText with same DOM state (3x sequential).")
                        
                        // Objective 5: Telemetry - Loop Detected
                        val telemetry = TelemetryLog(
                            sessionId = currentTaskId ?: "unknown",
                            targetAppPackage = packageName ?: "unknown",
                            failedAction = actionType,
                            targetLabel = targetText,
                            failureReason = "LOOP_DETECTED",
                            timestamp = getIsoTimestamp()
                        )
                        webSocketManager?.sendMessage(Gson().toJson(telemetry))

                        val loopPayload = JSONObject().apply {
                            put("type", "USER_INTENT_FEEDBACK")
                            put("task_id", currentTaskId ?: "")
                            put("action_status", "FAILED")
                            put("status", "LOOP_DETECTED")
                            put("failure_reason", "Execution loop detected locally (3x sequential actionable intent).")
                            put("timestamp", System.currentTimeMillis())
                        }
                        sendPayload(loopPayload)
                        return@launch
                    }
                    LoopDetector.recordAction(packageName ?: "", domHash, actionType, targetText ?: "", elements.length())
                }

                // Interceptor for Biometric Authentication
                if (requiresBiometric || actionType == "CONFIRM_BIOMETRIC") {
                    Log.d("AccessibilityAgent", "Biometric authentication required for action: $actionType")
                    val mainActivity = MainActivity.instance
                    if (mainActivity == null) {
                        Log.e("AccessibilityAgent", "MainActivity instance is null, cannot show biometric prompt")
                        val feedback = JSONObject().apply {
                            put("type", "USER_INTENT_FEEDBACK")
                            put("task_id", currentTaskId ?: "")
                            put("action_status", "FAILED")
                            put("failure_reason", "BIOMETRIC_FAILED")
                        }
                        sendPayload(feedback)
                        return@launch
                    }

                    mainActivity.showBiometricPrompt(
                        onSuccess = {
                            serviceScope.launch {
                                // Re-process the directive but skip the biometric check if it was just a confirm
                                if (actionType == "CONFIRM_BIOMETRIC") {
                                    val feedback = JSONObject().apply {
                                        put("type", "USER_INTENT_FEEDBACK")
                                        put("task_id", currentTaskId ?: "")
                                        put("action_status", "SUCCESS")
                                    }
                                    sendPayload(feedback)
                                } else {
                                    // Execute the actual action after successful auth
                                    executeDirectiveAction(json)
                                }
                            }
                        },
                        onError = {
                            serviceScope.launch {
                                val feedback = JSONObject().apply {
                                    put("type", "USER_INTENT_FEEDBACK")
                                    put("task_id", currentTaskId ?: "")
                                    put("action_status", "FAILED")
                                    put("failure_reason", "BIOMETRIC_FAILED")
                                }
                                sendPayload(feedback)
                            }
                        }
                    )
                    return@launch
                }

                // 4. Text-to-Speech (TTS) Execution (Local Fallback)
                if (speechResponse.isNotBlank()) {
                    voiceAssistantManager?.speak(speechResponse)
                }

                if (actionType == "FINISH" || actionType == "HARD_STOP" || error == "HARD_STOP" || isCompleted) {
                    Log.d("AccessibilityAgent", "Task finished or safety intercept: $actionType error=$error")
                    if (actionType == "HARD_STOP" || error == "HARD_STOP") {
                        voiceAssistantManager?.stopAllOperations()
                        activeDirectiveJob?.cancel()
                    }
                    return@launch
                }

                // 5. Coordinate Tap Handling
                executeDirectiveAction(json)
            } catch (e: Exception) {
                Log.e("AccessibilityAgent", "Error parsing backend directive", e)
            }
        }
    }

    private suspend fun executeDirectiveAction(json: JSONObject) {
        val actionType = json.optString("action_type")
        val targetText = json.optString("target_text")
        val packageName = json.optString("package_name")
        val textToType = json.optString("text_to_type")
        val scrollDirection = json.optString("scroll_direction")
        val executionMode = json.optString("execution_mode")
        val tapX = if (json.has("tap_x") && !json.isNull("tap_x")) json.optDouble("tap_x") else Double.NaN
        val tapY = if (json.has("tap_y") && !json.isNull("tap_y")) json.optDouble("tap_y") else Double.NaN

        // 5. Coordinate Tap Handling
        // If execution_mode is COORDINATE or tap coordinates are provided, perform direct gesture
        if (executionMode == "COORDINATE" || (!tapX.isNaN() && !tapY.isNaN())) {
            val point = resolveCoordinates(tapX, tapY)
            tapAtCoordinates(point.x, point.y)
            return
        }

        var actionResult: ActionResult? = null

        when (actionType) {
            "OPEN_APP" -> {
                if (!packageName.isNullOrBlank()) {
                    actionResult = openAppByPackage(packageName, targetText)
                } else {
                    actionResult = ActionResult(
                        false,
                        "INVALID_ARGUMENT",
                        "Package name is missing in OPEN_APP directive."
                    )
                }
            }
            "CLICK" -> {
                actionResult = if (!targetText.isNullOrBlank()) {
                    clickElementByText(targetText)
                } else {
                    ActionResult(
                        false,
                        "INVALID_ARGUMENT",
                        "Target text or coordinates missing for CLICK."
                    )
                }
            }
            "TYPE_TEXT" -> {
                actionResult = if (!textToType.isNullOrBlank()) typeTextIntoFocusedNode(textToType)
                else ActionResult(
                    false,
                    "INVALID_ARGUMENT",
                    "Text to type is missing for TYPE_TEXT."
                )
            }
            "SCROLL" -> {
                actionResult = performScroll(scrollDirection)
            }
            "SWIPE_UP" -> {
                val success = swipeUpToOpenDrawer()
                actionResult = ActionResult(success, if (success) "SUCCESS" else "GESTURE_FAILED")
            }
            "SWIPE_HORIZONTAL" -> {
                val direction = json.optString("direction", "LEFT")
                val success = scrollLauncherPage(direction)
                actionResult = ActionResult(success, if (success) "SUCCESS" else "GESTURE_FAILED")
            }
            "NONE" -> actionResult = ActionResult(true, "SUCCESS")
            "CONFIRM_BIOMETRIC" -> actionResult = ActionResult(true, "SUCCESS")
        }

        if (actionType != "NONE" && actionResult != null) {
            if (actionResult.status == "HOME_FALLBACK_STARTED") {
                // Standardized async transition handling: return early and let the
                // coroutine-based fallback handle the eventual feedback payload.
                return
            }

            val targetNode = when (actionType) {
                "OPEN_APP" -> actionResult.packageLaunched ?: packageName
                "CLICK" -> targetText
                "TYPE_TEXT" -> targetText
                else -> targetText
            }
            // OPEN_APP result is now delayed until the target app actually renders nodes
            // to avoid sending empty screen_state payloads to the backend.
            sendFeedbackAfterAction(
                result = actionResult,
                actionType = actionType,
                targetNode = targetNode,
                targetPackage = actionResult.packageLaunched ?: packageName,
                immediate = false,
                debounce = !actionResult.success
            )
        }
    }

    override fun onInterrupt() {
        Log.d("AccessibilityAgent", "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        voiceAssistantManager?.release()
        webSocketManager?.disconnect()
        instance = null
    }

    // --- Screen Automation Actions ---

    // 1. Find and click a UI element containing specific text (Robust recursive search)
    fun clickElementByText(text: String): ActionResult {
        val windows = windows
        val target = text.lowercase()
        var bestError: ActionResult? = null

        Log.d("AccessibilityAgent", "Attempting robust multi-window click search for: '$text'")

        // Iterate through all windows to find the target text
        for (window in windows) {
            val root = window.root ?: continue
            
            // 1. Fast path: Use built-in search
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (!node.isVisibleToUser) {
                    if (bestError == null || getStatusPriority(bestError.status) < getStatusPriority("OFF_SCREEN")) {
                        bestError = ActionResult(false, "OFF_SCREEN", "Node '$text' found but is off-screen.")
                    }
                    continue
                }
                val result = attemptClickRich(node)
                if (result.success) {
                    val context = getNodeContext(node)
                    nodes.forEach { it.recycle() }
                    root.recycle()
                    return result.copy(nodeContext = context)
                }
                if (bestError == null || getStatusPriority(result.status) > getStatusPriority(bestError.status)) {
                    bestError = result
                }
            }
            nodes.forEach { it.recycle() }
            
            // 2. Fallback: Deep recursive search within this window
            val recursiveResult = recursiveClickSearchRich(root, target)
            root.recycle()
            
            if (recursiveResult.success) {
                Log.d("AccessibilityAgent", "Recursive click search succeeded in window: ${window.title} for: '$text'")
                return recursiveResult
            }
            
            if (bestError == null || getStatusPriority(recursiveResult.status) > getStatusPriority(bestError.status)) {
                bestError = recursiveResult
            }
        }
        
        return bestError ?: ActionResult(false, "NODE_MISSING", "Element with text '$text' not found in any window.")
    }

    private fun getStatusPriority(status: String): Int {
        return when (status) {
            "ACTION_FAILED" -> 3
            "NOT_CLICKABLE" -> 2
            "OFF_SCREEN" -> 1
            "NODE_MISSING" -> 0
            else -> -1
        }
    }

    private fun recursiveClickSearchRich(node: AccessibilityNodeInfo, target: String): ActionResult {
        var bestError: ActionResult? = null
        var successResult: ActionResult? = null
        
        fun search(curr: AccessibilityNodeInfo): Boolean {
            val nodeText = curr.text?.toString()?.lowercase() ?: ""
            val nodeContentDesc = curr.contentDescription?.toString()?.lowercase() ?: ""
            val nodeHint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                curr.hintText?.toString()?.lowercase() ?: ""
            } else ""
            val nodeTooltip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                curr.tooltipText?.toString()?.lowercase() ?: ""
            } else ""
            val viewId = curr.viewIdResourceName?.lowercase() ?: ""
            
            val isMatch = nodeText.contains(target) || 
                          nodeContentDesc.contains(target) || 
                          nodeHint.contains(target) ||
                          nodeTooltip.contains(target) ||
                          viewId.contains(target)

            if (isMatch) {
                if (!curr.isVisibleToUser) {
                    if (bestError == null || getStatusPriority(bestError!!.status) < getStatusPriority("OFF_SCREEN")) {
                        bestError = ActionResult(false, "OFF_SCREEN", "Found node '$target' but it is off-screen.", nodeContext = getNodeContext(curr))
                    }
                } else {
                    val clickResult = attemptClickRich(curr)
                    if (clickResult.success) {
                        successResult = clickResult
                        return true
                    }
                    if (bestError == null || getStatusPriority(clickResult.status) > getStatusPriority(bestError!!.status)) {
                        bestError = clickResult
                    }
                }
            }
            
            for (i in 0 until curr.childCount) {
                val child = curr.getChild(i) ?: continue
                if (search(child)) {
                    child.recycle()
                    return true
                }
                child.recycle()
            }
            return false
        }
        
        if (search(node)) return successResult ?: ActionResult(true, "SUCCESS")
        return bestError ?: ActionResult(false, "NODE_MISSING", "Element with text '$target' not found.")
    }

    private fun attemptClickRich(node: AccessibilityNodeInfo): ActionResult {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) return ActionResult(true, "SUCCESS") 
            }
            // Move to parent if current node is not clickable
            current = current.parent
        }
        
        // Coordinate-based fallback click for is_clickable == false
        val clickX = rect.exactCenterX()
        val clickY = rect.exactCenterY()
        
        Log.d("AccessibilityAgent", "Attempting coordinate-based fallback click at ($clickX, $clickY)")
        val gestureSuccess = dispatchClickAt(clickX, clickY)
        
        return if (gestureSuccess) {
            val context = getNodeContext(node)
            ActionResult(true, "SUCCESS", nodeContext = context)
        } else {
            val nodeDesc = "Text: ${node.text ?: ""}, Desc: ${node.contentDescription ?: ""}, ID: ${node.viewIdResourceName ?: ""}, Bounds: ${rect.toShortString()}"
            val context = getNodeContext(node)
            ActionResult(false, "NOT_CLICKABLE", "Node $nodeDesc and parents not clickable, and gesture fallback failed.", nodeContext = context)
        }
    }

    private fun getNodeContext(node: AccessibilityNodeInfo): JSONObject {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return JSONObject().apply {
            put("text", node.text ?: "")
            put("content_description", node.contentDescription ?: "")
            put("resource_id", node.viewIdResourceName ?: "")
            put("class_name", node.className ?: "")
            put("bounds", rect.toShortString())
            put("isClickable", node.isClickable)
            put("isEditable", node.isEditable)
            put("isVisible", node.isVisibleToUser)
            put("isEnabled", node.isEnabled)
            put("isFocused", node.isFocused)
        }
    }

    private fun dispatchClickAt(x: Float, y: Float): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false
        
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        
        // Treat successful gesture dispatch as a click execution per requirements
        var success = false
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }, null)
        
        try {
            latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Log.e("AccessibilityAgent", "Gesture latch interrupted", e)
        }
        
        return success
    }

    private fun clickAtNormalized(normalizedX: Double, normalizedY: Double): ActionResult {
        val metrics = resources.displayMetrics
        val x = (normalizedX.coerceIn(0.0, 1.0) * metrics.widthPixels).toFloat()
        val y = (normalizedY.coerceIn(0.0, 1.0) * metrics.heightPixels).toFloat()
        Log.d("AccessibilityAgent", "Dispatching Service 03 coordinate tap at ($x, $y)")
        val success = dispatchClickAt(x, y)
        return ActionResult(success, if (success) "SUCCESS" else "GESTURE_FAILED")
    }

    // 2. Launch external apps safely
    private fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
            Log.d("AccessibilityAgent", "Successfully launched: $packageName")
            true
        } catch (e: Exception) {
            Log.e("AccessibilityAgent", "Failed to launch $packageName", e)
            false
        }
    }

    private fun openAppByPackage(packageName: String?, appName: String? = null): ActionResult {
        var targetPackage = packageName

        // 1. Resolve package name dynamically if not provided or if direct launch fails
        if (targetPackage.isNullOrBlank() && !appName.isNullOrBlank()) {
            targetPackage = AppResolver.findPackageByLabel(this, appName)
        }

        // 2. Attempt launch with resolved or provided package
        if (!targetPackage.isNullOrBlank()) {
            if (launchApp(targetPackage)) {
                return ActionResult(true, "SUCCESS", packageLaunched = targetPackage)
            }
        }
        
        // 3. Fallback: Query all installed applications (Safety net if queryIntentActivities missed it)
        if (!appName.isNullOrBlank()) {
            Log.d("AccessibilityAgent", "Standard launch failed, querying all installed apps for: '$appName'")
            val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in apps) {
                val label = appInfo.loadLabel(packageManager).toString()
                if (label.equals(appName, ignoreCase = true)) {
                    val foundPackage = appInfo.packageName
                    if (launchApp(foundPackage)) {
                        return ActionResult(true, "SUCCESS", packageLaunched = foundPackage)
                    }
                }
            }
        }

        // 4. Final Fallback: App Drawer Search (Deterministic 2-step state machine)
        if (!appName.isNullOrBlank()) {
            Log.d("AccessibilityAgent", "Intent-based launch failed. Triggering App Drawer fallback for '$appName'.")
            
            val taskToFinalize = currentTaskId
            val originalIntentToFinalize = currentOriginalIntent

            serviceScope.launch {
                runAppDrawerFallback(appName, taskToFinalize, originalIntentToFinalize)
            }
            return ActionResult(true, "HOME_FALLBACK_STARTED")
        }

        return ActionResult(false, "FAILED", "App with label '$appName' not found or not launchable.")
    }

    fun swipeUpToOpenDrawer(): Boolean {
        val displayMetrics = resources.displayMetrics
        val startX = displayMetrics.widthPixels / 2f
        val startY = displayMetrics.heightPixels * 0.85f
        val endY = displayMetrics.heightPixels * 0.15f
        
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(startX, endY)
        
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        
        var success = false
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, null)
        
        try {
            latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            // Gesture completion acknowledged. Animation settlement (500ms+) 
            // is handled by the feedback loop coroutine to avoid blocking the main thread.
        } catch (e: Exception) {
            Log.e("AccessibilityAgent", "Swipe Up Interrupted", e)
        }
        return success
    }

    fun scrollLauncherPage(direction: String): Boolean {
        val displayMetrics = resources.displayMetrics
        val centerY = displayMetrics.heightPixels / 2f
        val startX = if (direction == "RIGHT") displayMetrics.widthPixels * 0.2f else displayMetrics.widthPixels * 0.8f
        val endX = if (direction == "RIGHT") displayMetrics.widthPixels * 0.8f else displayMetrics.widthPixels * 0.2f
        
        val path = Path()
        path.moveTo(startX, centerY)
        path.lineTo(endX, centerY)
        
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        
        var success = false
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, null)
        latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        return success
    }

    private suspend fun runAppDrawerFallback(appName: String, taskId: String?, originalIntent: String?) {
        Log.d("AccessibilityAgent", "Running deterministic App Drawer Fallback for '$appName'")
        
        // 1. Global Home
        performGlobalAction(GLOBAL_ACTION_HOME)
        delay(1000) 
        
        // 2. Swipe Up to open Drawer
        swipeUpToOpenDrawer()
        delay(1000) // Wait for drawer animation
        
        // 3. Locate Search Bar
        val searchBox = findAppDrawerSearchBox()
        
        val finalResult = if (searchBox != null) {
            // 4. Inject App Name
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, appName)
            }
            val setSuccess = searchBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (setSuccess) {
                delay(500)
                // 5. Dispatch IME search
                triggerSearchAction(searchBox)
                delay(1200) // Wait for results to populate
                
                // 6. Click first search result (prioritize nodes that are NOT the search box)
                clickSearchResult(appName, searchBox)
            } else {
                ActionResult(false, "ACTION_FAILED", "Failed to set text in search box")
            }
        } else {
            Log.e("AccessibilityAgent", "Could not find search box in App Drawer")
            // Try direct click on home screen as a last-ditch effort
            clickElementByText(appName)
        }
        
        searchBox?.recycle()
        
        sendFeedbackAfterAction(
            result = finalResult,
            actionType = "OPEN_APP",
            targetNode = appName,
            immediate = false,
            debounce = !finalResult.success,
            capturedTaskId = taskId,
            capturedOriginalIntent = originalIntent
        )
    }

    private fun clickSearchResult(appName: String, searchBox: AccessibilityNodeInfo): ActionResult {
        val windows = windows
        val target = appName.lowercase()
        
        for (window in windows) {
            val root = window.root ?: continue
            val result = recursiveSearchAndClickResult(root, target, searchBox)
            root.recycle()
            if (result.success) return result
        }
        
        return ActionResult(false, "NODE_MISSING", "Could not find app result for '$appName' in search results")
    }

    private fun recursiveSearchAndClickResult(node: AccessibilityNodeInfo, target: String, exclude: AccessibilityNodeInfo): ActionResult {
        if (node != exclude) {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (text.contains(target) || desc.contains(target)) {
                val clickRes = attemptClickRich(node)
                if (clickRes.success) return clickRes
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = recursiveSearchAndClickResult(child, target, exclude)
            child.recycle()
            if (res.success) return res
        }
        return ActionResult(false, "NODE_MISSING")
    }

    private fun findSearchableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.lowercase() ?: ""
        } else ""
        
        val viewId = node.viewIdResourceName ?: ""
        
        if (node.isEditable && (
            text.contains("search") || 
            hint.contains("search") || 
            viewId.contains("search_box_input") ||
            viewId.contains("search_src_text")
        )) {
            return AccessibilityNodeInfo.obtain(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSearchableNode(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findAppDrawerSearchBox(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val commonIds = listOf(
            "com.google.android.apps.nexuslauncher:id/search_container_all_apps",
            "com.google.android.googlequicksearchbox:id/googleapp_search_box",
            "com.android.launcher3:id/search_container_all_apps",
            "search_box_input",
            "search_src_text",
            "search_bar"
        )
        
        for (id in commonIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val found = AccessibilityNodeInfo.obtain(nodes[0])
                nodes.forEach { it.recycle() }
                root.recycle()
                return found
            }
        }
        
        val searchable = findSearchableNode(root)
        root.recycle()
        return searchable
    }

    // 3. Type text into the currently focused or first available editable field
    suspend fun typeTextIntoFocusedNode(textToType: String): ActionResult {
        val windows = windows
        var targetNode: AccessibilityNodeInfo? = null

        // Strategy 1: Search all windows for focused input
        for (window in windows) {
            val root = window.root ?: continue
            targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (targetNode != null && targetNode.isEditable) {
                root.recycle()
                break
            }
            root.recycle()
        }
        
        // Strategy 2: Fallback to the first editable node in any window
        if (targetNode == null) {
            for (window in windows) {
                val root = window.root ?: continue
                targetNode = findFirstEditableNode(root)
                if (targetNode != null) {
                    root.recycle()
                    break
                }
                root.recycle()
            }
        }

        return if (targetNode != null && targetNode.isEditable) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (success) {
                Log.d("AccessibilityAgent", "Successfully injected text. Waiting for settlement...")
                
                // Short delay to allow OS/App to register the text input buffer
                delay(150)
                
                Log.d("AccessibilityAgent", "Triggering search IME action...")
                triggerSearchAction(targetNode)
                
                val context = getNodeContext(targetNode)
                targetNode.recycle()
                ActionResult(true, "SUCCESS", nodeContext = context)
            } else {
                val context = getNodeContext(targetNode)
                targetNode.recycle()
                Log.e("AccessibilityAgent", "ACTION_SET_TEXT failed on target node.")
                ActionResult(false, "ACTION_FAILED", "ACTION_SET_TEXT returned false.", nodeContext = context)
            }
        } else {
            Log.e("AccessibilityAgent", "No focused or editable field found to type into.")
            ActionResult(false, "NODE_MISSING", "No focused or editable field found.")
        }
    }

    private suspend fun triggerSearchAction(node: AccessibilityNodeInfo) {
        // Strategy 0: Direct Key Event dispatch via shell (Most reliable for IME Enter)
        val enterSuccess = try {
            val process = Runtime.getRuntime().exec("input keyevent 66")
            process.waitFor()
            true
        } catch (e: Exception) {
            false
        }
        
        if (enterSuccess) {
            Log.d("AccessibilityAgent", "IME Enter submitted via adb shell keyevent 66")
            return
        }

        // Strategy 1: Use native ACTION_IME_ENTER if available (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val imeResult = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (imeResult) {
                Log.d("AccessibilityAgent", "IME Search submitted via ACTION_IME_ENTER")
                return
            }
        }

        // Strategy 2: Look for a search/go/enter button in all windows
        val windows = windows
        for (window in windows) {
            val root = window.root ?: continue
            if (searchForImeButton(root)) {
                Log.d("AccessibilityAgent", "IME Search submitted via UI Button click")
                root.recycle()
                return
            }
            root.recycle()
        }
        
        // Strategy 3: Fallback click to trigger listeners
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun searchForImeButton(curr: AccessibilityNodeInfo): Boolean {
        val text = curr.text?.toString()?.lowercase() ?: ""
        val desc = curr.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = curr.viewIdResourceName?.lowercase() ?: ""
        
        // Common search button labels, content descriptions, or IDs
        val isSearchMatch = (curr.isClickable && (
            text == "search" || text == "go" || text == "enter" || text == "done" ||
            desc.contains("search") || desc.contains("submit") || desc.contains("magnifying glass") ||
            viewId.contains("search") || viewId.contains("submit") || viewId.contains("btn_search")
        ))

        if (isSearchMatch) {
            val res = curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (res) Log.d("AccessibilityAgent", "Clicked IME action button: '$text'/'$desc'/'$viewId'")
            return res
        }

        for (i in 0 until curr.childCount) {
            val child = curr.getChild(i) ?: continue
            if (searchForImeButton(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstEditableNode(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    fun performScroll(direction: String): ActionResult {
        val windows = windows
        var scrollableNode: AccessibilityNodeInfo? = null
        
        for (window in windows) {
            val root = window.root ?: continue
            scrollableNode = findScrollableNode(root)
            if (scrollableNode != null) {
                root.recycle()
                break
            }
            root.recycle()
        }
        
        return if (scrollableNode != null) {
            val action = if (direction.equals("BACKWARD", ignoreCase = true)) {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            val success = scrollableNode.performAction(action)
            val context = getNodeContext(scrollableNode)
            scrollableNode.recycle()
            Log.d("AccessibilityAgent", "Scroll $direction success: $success")
            if (success) ActionResult(true, "SUCCESS", nodeContext = context) 
            else ActionResult(false, "ACTION_FAILED", "ACTION_SCROLL returned false.", nodeContext = context)
        } else {
            Log.w("AccessibilityAgent", "No scrollable container found")
            ActionResult(false, "NODE_MISSING", "No scrollable container found to scroll $direction.")
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    private fun sendFeedbackAfterAction(
        result: ActionResult,
        actionType: String,
        targetNode: String?,
        targetPackage: String? = null,
        immediate: Boolean = false,
        debounce: Boolean = false,
        capturedTaskId: String? = currentTaskId,
        capturedOriginalIntent: String? = currentOriginalIntent
    ) {
        serviceScope.launch {
            // Mandatory Post-Action UI Settlement Window
            val settleDelay = when (actionType) {
                "OPEN_APP", "SWIPE_UP" -> 1200L
                "CLICK", "TYPE_TEXT" -> 600L
                else -> 0L
            }
            if (settleDelay > 0) {
                Log.d("AccessibilityAgent", "Applying mandatory ${settleDelay}ms UI settlement delay for $actionType")
                delay(settleDelay)
            }

            if (debounce) {
                Log.d("AccessibilityAgent", "Debouncing feedback payload by an additional 1200ms...")
                delay(1200)
            }

            if (!immediate && actionType != "OPEN_APP" && actionType != "SWIPE_UP" && actionType != "CLICK" && actionType != "TYPE_TEXT") {
                // Wait for UI to settle: either by event or timeout
                windowChangeDeferred = CompletableDeferred()
                withTimeoutOrNull(2000) {
                    windowChangeDeferred?.await()
                }
                windowChangeDeferred = null
                
                // Fallback for other non-specific actions
                delay(600)
            }
            
            // Scrape enriched screen state after the specified settlement delay
            val screenState = captureScreenStateWithRetry()
            
            var finalSuccess = result.success
            var finalStatus = result.status
            var finalErrorMessage = result.errorMessage
            val attemptKey = "$actionType:${targetNode ?: targetPackage ?: ""}"
            val executionAttempt = if (result.success) {
                executionAttempts.remove(attemptKey)
                0
            } else {
                (executionAttempts[attemptKey] ?: 0) + 1
            }.also { if (!result.success) executionAttempts[attemptKey] = it }

            // Verification: Ensure requested app is indeed in foreground (only if not immediate)
            if (!immediate && actionType == "OPEN_APP" && targetPackage != null && result.success) {
                val currentPackage = rootInActiveWindow?.packageName?.toString() ?: ""
                if (currentPackage != targetPackage) {
                    finalSuccess = false
                    finalStatus = "TRANSITION_FAILED"
                    finalErrorMessage = "App transition failed. Current package: '$currentPackage', Expected: '$targetPackage'"
                    Log.w("AccessibilityAgent", finalErrorMessage)
                }
            }

            // Ensure we only send SUCCESS if we actually have active screen elements in Phase 1 structure
            val elements = screenState.optJSONArray("screenElements")
            if (!immediate && finalSuccess && (elements == null || elements.length() == 0)) {
                finalSuccess = false
                finalStatus = "EMPTY_UI_STATE"
                finalErrorMessage = "Action appeared successful but no UI elements were captured after settlement."
                Log.w("AccessibilityAgent", finalErrorMessage)
            }

            // Objective 5: Telemetry - Failure Detected during UI Action
            if (!finalSuccess) {
                val telemetry = TelemetryLog(
                    sessionId = capturedTaskId ?: "unknown",
                    targetAppPackage = targetPackage?.ifBlank { null } ?: activePackageInCapture?.ifBlank { null } ?: "N/A",
                    failedAction = actionType,
                    targetLabel = targetNode,
                    failureReason = finalErrorMessage ?: finalStatus,
                    timestamp = getIsoTimestamp()
                )
                webSocketManager?.sendMessage(Gson().toJson(telemetry))
            }

            val payload = JSONObject().apply {
                put("type", "USER_INTENT_FEEDBACK")
                put("task_id", capturedTaskId ?: "")
                put("original_intent", capturedOriginalIntent ?: "")
                put("action_status", if (finalSuccess) "SUCCESS" else "FAILED")
                
                put("status", finalStatus)
                put("action", actionType)
                put("target_action", actionType)
                put("target_node", targetNode ?: "")
                put("execution_attempt", executionAttempt)
                if (targetPackage != null) put("target_package", targetPackage)
                if (result.nodeContext != null) put("node_context", result.nodeContext)

                if (!finalSuccess) {
                    put("failure_reason", finalErrorMessage ?: finalStatus)
                    put("error_message", finalErrorMessage ?: finalStatus)
                }

                put("timestamp", System.currentTimeMillis())
            }
            attachScreenContext(payload, screenState)
            
            Log.d("AccessibilityAgent", "Dispatching Feedback Loop: ${payload.toString(2)}")
            val sendSuccess = webSocketManager?.sendPayload(payload) ?: false
            if (!sendSuccess) {
                Log.e("AccessibilityAgent", "CRITICAL: Feedback payload failed to send. Task ID: $capturedTaskId")
            }
        }
    }

    // --- Screen State Extraction (Task 5 Phase 1 - Robustified) ---

    private suspend fun captureScreenStateWithRetry(): JSONObject {
        var retries = 5 // Increased retries to handle slow-loading apps or animations
        var finalResult = JSONObject()
        
        // Initial debounce/delay for UI settlement after action
        delay(500)

        // Window Settle Verification for empty DOM trees:
        // retry fetching rootInActiveWindow up to 3 times with a 150ms delay
        // if it yields 0 child elements (originalElementsCount: 0), ensuring apps have fully rendered their UI.
        var windowSettleRetries = 3
        while (windowSettleRetries > 0) {
            val root = rootInActiveWindow
            val originalElementsCount = root?.childCount ?: 0
            if (root != null && originalElementsCount > 0) {
                root.recycle()
                break
            }
            root?.recycle()
            Log.d("AccessibilityAgent", "rootInActiveWindow is null or has 0 children (originalElementsCount: $originalElementsCount), retrying settle... ($windowSettleRetries left)")
            delay(200)
            windowSettleRetries--
        }

        while (retries > 0) {
            val currentBatch = JSONArray()
            // Reset metadata for each attempt
            isInputFocusedInCapture = false
            focusedHintInCapture = null
            activePackageInCapture = null

            // Prioritize rootInActiveWindow for the main app content
            val activeRoot = rootInActiveWindow
            val windowsList = windows
            
            var nodesCaptured = 0

            if (activeRoot != null) {
                val pkg = activeRoot.packageName?.toString() ?: ""
                if (!pkg.contains("com.android.systemui", ignoreCase = true)) {
                    traverseAndExtract(activeRoot, currentBatch)
                    nodesCaptured = currentBatch.length()
                }
                activeRoot.recycle()
            }

            // If active root didn't give us much, or to be thorough, check other windows
            if (nodesCaptured == 0 && windowsList.isNotEmpty()) {
                for (window in windowsList) {
                    val root = window.root ?: continue
                    val pkg = root.packageName?.toString() ?: ""
                    if (pkg.contains("com.android.systemui", ignoreCase = true)) {
                        root.recycle()
                        continue
                    }
                    traverseAndExtract(root, currentBatch)
                    root.recycle()
                }
            }

            if (currentBatch.length() > 0) {
                finalResult = JSONObject().apply {
                    put("screenElements", currentBatch)
                    put("current_package_name", activePackageInCapture ?: "")
                    put("active_input_focused", isInputFocusedInCapture)
                    put("focused_hint", focusedHintInCapture ?: JSONObject.NULL)
                }
                Log.d("AccessibilityAgent", "Successfully captured ${currentBatch.length()} elements.")
                break
            } else if (retries == 1) {
                // Tier-4 Fallback: Capture screenshot if DOM parsing yields nothing in final attempt
                Log.d("AccessibilityAgent", "DOM capture failed, attempting Tier-4 visual fallback (screenshot)...")
                val screenshotB64 = captureScreenshotBase64()
                if (screenshotB64 != null) {
                    finalResult = JSONObject().apply {
                        put("screenElements", JSONArray())
                        put("screenshot_b64", screenshotB64)
                        put("current_package_name", activePackageInCapture ?: "")
                        put("active_input_focused", isInputFocusedInCapture)
                        put("focused_hint", focusedHintInCapture ?: JSONObject.NULL)
                        put("capture_mode", "VISUAL_FALLBACK")
                    }
                    break
                }
            }
            
            Log.d("AccessibilityAgent", "UI extraction returned 0 elements, retrying... ($retries left)")
            delay(400) // Increased poll interval for stability
            retries--
        }

        // Return empty structure if all retries fail
        if (finalResult.length() == 0) {
            Log.w("AccessibilityAgent", "Failed to capture any UI elements after all retries.")
            finalResult = JSONObject().apply {
                put("screenElements", JSONArray())
                put("current_package_name", "")
                put("active_input_focused", false)
                put("focused_hint", JSONObject.NULL)
            }
        }
        
        return finalResult
    }

    private suspend fun attachScreenContext(payload: JSONObject, screenState: JSONObject) {
        val elements = screenState.optJSONArray("screenElements") ?: JSONArray()
        payload.put("screenElements", elements)
        payload.put("screen_state", elements)
        
        if (elements.length() == 0) {
            Log.w("AccessibilityAgent", "DOM traversal yielded no elements. Attaching Base64 screenshot fallback.")
            val screenshot = if (screenState.has("screenshot_b64")) {
                screenState.getString("screenshot_b64")
            } else {
                captureScreenshotBase64()
            }
            
            if (!screenshot.isNullOrBlank()) {
                payload.put("screenshot_base64", screenshot)
                payload.put("capture_mode", "VISUAL_FALLBACK")
            }
        } else {
            // Include visual fallback data if it was already captured during retry
            if (screenState.has("screenshot_b64")) {
                payload.put("screenshot_base64", screenState.getString("screenshot_b64"))
                payload.put("capture_mode", screenState.optString("capture_mode", "VISUAL_FALLBACK"))
            }
        }

        payload.put("current_package_name", screenState.optString("current_package_name"))
        payload.put("current_app_package", screenState.optString("current_package_name"))
        payload.put("active_input_focused", screenState.optBoolean("active_input_focused"))
        val hint = screenState.optString("focused_hint")
        if (hint.isNotBlank() && hint != "null") {
            payload.put("focused_hint", hint)
        }
    }

    private suspend fun captureScreenshotBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w("AccessibilityAgent", "Screenshot capture requires API 30+; skipping Service 03 fallback image.")
            return null
        }
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val buffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()
                        if (bitmap == null) {
                            continuation.resume(null)
                            return
                        }
                        val maxWidth = 720
                        val scaled = if (bitmap.width > maxWidth) {
                            val height = (bitmap.height.toFloat() / bitmap.width * maxWidth).toInt().coerceAtLeast(1)
                            Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
                        } else {
                            bitmap
                        }
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 55, out)
                        continuation.resume(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                    } catch (e: Exception) {
                        Log.e("AccessibilityAgent", "Failed to encode screenshot for Service 03", e)
                        continuation.resume(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w("AccessibilityAgent", "takeScreenshot failed errorCode=$errorCode")
                    continuation.resume(null)
                }
            })
        }
    }

    private fun tapAtCoordinates(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val gestureBuilder = GestureDescription.Builder()
        // 50ms tap duration as requested
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        gestureBuilder.addStroke(stroke)

        Log.d("AccessibilityAgent", "Executing coordinate tap at ($x, $y)")
        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                val feedback = JSONObject().apply {
                    put("type", "USER_INTENT_FEEDBACK")
                    put("task_id", currentTaskId ?: "")
                    put("action_status", "SUCCESS")
                }
                sendPayload(feedback)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.e("AccessibilityAgent", "Coordinate tap cancelled at ($x, $y)")
                val feedback = JSONObject().apply {
                    put("type", "USER_INTENT_FEEDBACK")
                    put("task_id", currentTaskId ?: "")
                    put("action_status", "FAILED")
                    put("failure_reason", "GESTURE_CANCELLED")
                }
                sendPayload(feedback)
            }
        }, null)
    }

    private fun resolveCoordinates(x: Double, y: Double): PointF {
        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels.toFloat()
        val screenHeight = dm.heightPixels.toFloat()

        val realX = if (x in 0.0..1.0) (x * screenWidth).toFloat() else x.toFloat()
        val realY = if (y in 0.0..1.0) (y * screenHeight).toFloat() else y.toFloat()

        return PointF(realX, realY)
    }

    private fun traverseAndExtract(rootNode: AccessibilityNodeInfo, array: JSONArray) {
        // We use a stack of Pairs: the node and its nearest clickable ancestor's JSON representation
        val stack = mutableListOf<Pair<AccessibilityNodeInfo, JSONObject?>>()
        stack.add(AccessibilityNodeInfo.obtain(rootNode) to null)

        while (stack.isNotEmpty()) {
            val (node, clickableParent) = stack.removeAt(stack.size - 1)
            
            // 1. SYSTEM UI & VISIBILITY FILTERING
            val nodePackage = node.packageName?.toString() ?: ""
            if (nodePackage.contains("com.android.systemui", ignoreCase = true) || !node.isVisibleToUser) {
                node.recycle()
                continue
            }

            // 2. ENRICHED METADATA EXTRACTION
            if (activePackageInCapture == null && nodePackage.isNotEmpty()) {
                activePackageInCapture = nodePackage
            }

            if (node.isEditable && node.isFocused) {
                isInputFocusedInCapture = true
                focusedHintInCapture = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    node.hintText?.toString() ?: node.text?.toString()
                } else {
                    node.text?.toString()
                }
            }

            // 3. PARENT-NODE COLLAPSING LOGIC
            var nextClickableParent = clickableParent
            var shouldCreateNode = true

            // If we are inside a clickable parent and this node is NOT editable, collapse it.
            // This ensures list rows, buttons with icons, and combined elements are treated as single units.
            if (clickableParent != null && !node.isEditable) {
                val nodeText = node.text?.toString()?.trim() ?: ""
                val nodeDesc = node.contentDescription?.toString()?.trim() ?: ""
                
                if (nodeText.isNotEmpty() || nodeDesc.isNotEmpty()) {
                    val currentText = clickableParent.optString("text", "").trim()
                    val currentDesc = clickableParent.optString("content_description", "").trim()
                    
                    if (nodeText.isNotEmpty() && !currentText.contains(nodeText)) {
                        val newText = if (currentText.isEmpty()) nodeText else "$currentText $nodeText"
                        clickableParent.put("text", newText)
                    }
                    if (nodeDesc.isNotEmpty() && !currentDesc.contains(nodeDesc)) {
                        val newDesc = if (currentDesc.isEmpty()) nodeDesc else "$currentDesc $nodeDesc"
                        clickableParent.put("content_description", newDesc)
                    }
                }
                // Skip creating a separate node entry for this child to keep DOM lean and focused
                shouldCreateNode = false
            }

            if (shouldCreateNode) {
                val nodeObj = JSONObject().apply {
                    put("text", node.text?.toString() ?: "")
                    put("content_description", node.contentDescription?.toString() ?: "")
                    put("resource_id", node.viewIdResourceName ?: "")
                    put("class_name", node.className?.toString() ?: "")
                    put("package_name", nodePackage)
                    put("isClickable", node.isClickable)
                    put("isEditable", node.isEditable)
                    put("isFocused", node.isFocused)
                    put("isScrollable", node.isScrollable)
                    put("isVisible", node.isVisibleToUser)
                    put("isEnabled", node.isEnabled)
                    put("isChecked", node.isChecked)
                    put("isSelected", node.isSelected)

                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    put("bounds", JSONObject().apply {
                        put("left", bounds.left)
                        put("top", bounds.top)
                        put("right", bounds.right)
                        put("bottom", bounds.bottom)
                    })
                }
                array.put(nodeObj)
                
                // If this new node is clickable, it becomes the parent context for its descendants
                if (node.isClickable) {
                    nextClickableParent = nodeObj
                }
            }

            // 4. DFS RECURSION
            // Add children to stack in reverse order to maintain DFS left-to-right order
            for (i in node.childCount - 1 downTo 0) {
                val child = node.getChild(i)
                if (child != null) {
                    stack.add(child to nextClickableParent)
                }
            }
            node.recycle()
        }
    }
}

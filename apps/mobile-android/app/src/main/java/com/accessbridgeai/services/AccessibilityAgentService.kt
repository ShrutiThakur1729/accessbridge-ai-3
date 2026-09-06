package com.accessbridgeai.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.accessbridgeai.VoiceAssistantManager
import com.accessbridgeai.WebSocketManager
import com.accessbridgeai.ExecutionEngine
import com.accessbridgeai.MainActivity
import com.accessbridgeai.utils.LauncherAppResolver
import com.accessbridgeai.utils.LoopDetector
import com.accessbridgeai.utils.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Core Accessibility Service for AccessBridge AI.
 * Handles DOM extraction, gesture execution, and communication with the local backend.
 */
class AccessibilityAgentService : AccessibilityService() {

    companion object {
        var instance: AccessibilityAgentService? = null
            private set

        private val _serviceInstance = MutableStateFlow<AccessibilityAgentService?>(null)
        val serviceInstance: StateFlow<AccessibilityAgentService?> = _serviceInstance
    }

    private var webSocketManager: WebSocketManager? = null
    private var voiceAssistantManager: VoiceAssistantManager? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeDirectiveJob: Job? = null
    
    // Task State Tracking
    private var currentTaskId: String? = null
    private var currentOriginalIntent: String? = null
    private val executionAttempts = mutableMapOf<String, Int>()

    private val _connectionStatus = MutableStateFlow(false)
    val connectionStatus: StateFlow<Boolean> = _connectionStatus

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> get() = _partialTranscript

    private val _speechState = MutableStateFlow("IDLE")
    val speechState: StateFlow<String> get() = _speechState

    data class ActionResult(
        val success: Boolean,
        val status: String,
        val failure_reason: String? = null,
        val nodeContext: JSONObject? = null,
        val packageLaunched: String? = null,
        val candidates: List<String>? = null
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _serviceInstance.value = this
        
        Log.d("AccessibilityAgent", "Service connected. Initializing components...")
        
        webSocketManager = WebSocketManager(
            serverUrl = "ws://192.168.29.217:3000", // Updated for physical Wi-Fi testing
            onMessageReceived = { handleBackendDirective(it) },
            onConnectionStatusChanged = { connected -> 
                _connectionStatus.value = connected 
                if (connected) {
                    Log.i("AccessibilityAgent", "WebSocket Connected Successfully")
                }
            }
        )
        
        voiceAssistantManager = VoiceAssistantManager(
            context = this,
            onSpeechRecognized = { sendUserIntent(it) },
            onSpeechStateChanged = { state ->
                 _speechState.value = state
            },
            onPartialResults = { partial ->
                 _partialTranscript.value = partial
            }
        )
        
        webSocketManager?.connect()
    }

    fun startAssistant() {
        voiceAssistantManager?.startListening()
    }

    fun handleUserCancel() {
        voiceAssistantManager?.stopAllOperations()
        activeDirectiveJob?.cancel()
        currentTaskId = null
        currentOriginalIntent = null
        SessionManager.resetSession()
        Log.d("AccessibilityAgent", "User cancelled session. Session reset.")
    }

    private fun isSelfWindow(node: AccessibilityNodeInfo): Boolean {
        return node.packageName == "com.accessbridgeai"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event monitoring can be added here for dynamic UI updates
    }

    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        // Handled by system shortcut (Volume keys), but could be used here if flagRequestFilterKeyEvents was set
        return super.onKeyEvent(event)
    }

    fun sendPayload(payload: JSONObject): Boolean {
        payload.put("session_id", SessionManager.getSessionId())
        if (!payload.has("task_id")) {
            payload.put("task_id", UUID.randomUUID().toString())
        }
        
        Log.d("AccessBridgeService", "Dispatching payload to Gateway: ${payload.optString("type")}")
        
        val success = webSocketManager?.sendPayload(payload) ?: false
        if (success) {
            Log.d("AccessBridgeService", "Successfully queued payload for transmission: ${payload.toString(2)}")
        } else {
            val wasConnected = _connectionStatus.value
            Log.e("AccessBridgeService", "Failed to send payload. Socket Connected: $wasConnected. Type: ${payload.optString("type")}")
            if (wasConnected) {
                _connectionStatus.value = false
                voiceAssistantManager?.stopAudioStreaming()
                serviceScope.launch(Dispatchers.Main) {
                    Toast.makeText(this@AccessibilityAgentService, "Server Connection Lost", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return success
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
                put("session_id", SessionManager.getSessionId())
                put("timestamp", System.currentTimeMillis())
            }
            attachScreenContext(payload, screenState)
            Log.d("AccessibilityAgent", "OFFLINE_MOCK_PAYLOAD:\n${payload.toString(2)}")
        }
    }

    private fun sendUserIntent(transcript: String) {
        Log.d("AccessBridgeService", "Speech-to-Text Completed. Transcript: \"$transcript\"")
        if (transcript.equals("cancel", ignoreCase = true) || transcript.equals("stop", ignoreCase = true)) {
            handleUserCancel()
            return
        }
        val taskId = UUID.randomUUID().toString()
        currentTaskId = taskId
        currentOriginalIntent = transcript
        executionAttempts.clear()
        LoopDetector.clearHistory()
        SessionManager.updateActivity()
        
        serviceScope.launch {
            // Focus-Settling Delay to allow window focus to settle from voice UI
            delay(150)

            // Native WebSocket Interception: Pre-execution Native App Resolution
            // Check if the user is asking to open an app (simple heuristic)
            val lowerIntent = transcript.lowercase()
            if (lowerIntent.startsWith("open ") || lowerIntent.startsWith("launch ")) {
                val targetAppName = transcript.substringAfter("open ").substringAfter("launch ").trim()
                if (targetAppName.isNotEmpty()) {
                    Log.d("AccessibilityAgent", "Intercepting app launch request locally for: $targetAppName")
                    val resolution = LauncherAppResolver.resolveApp(this@AccessibilityAgentService, targetAppName)
                    
                    if (resolution.status == LauncherAppResolver.ResolutionStatus.MATCH_FOUND) {
                        Log.d("AccessibilityAgent", "Native Interception MATCH_FOUND: ${resolution.packageName}")
                        // Mandatory 1200ms settlement window for activity inflation
                        delay(1200)
                        
                        val feedback = JSONObject().apply {
                            put("type", "USER_INTENT_FEEDBACK")
                            put("task_id", taskId)
                            put("original_intent", transcript)
                            put("action_status", "SUCCESS")
                            put("status", "SUCCESS")
                            put("action", "OPEN_APP")
                            put("target_package", resolution.packageName)
                            put("current_package_name", resolution.packageName)
                            put("timestamp", System.currentTimeMillis())
                        }
                        attachScreenContext(feedback, captureScreenStateWithRetry())
                        sendPayload(feedback)
                        return@launch
                    }
                }
            }

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
        Log.d("AccessBridgeService", "Incoming directive received: ${json.toString(2)}")
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

        // 2. Local Interception for OPEN_APP Directive
        val actionType = json.optString("action_type")
        val packageName = json.optString("package_name")
        val targetText = json.optString("target_text")
        val taskId = json.optString("task_id")

        if (actionType == "OPEN_APP") {
            val appToResolve = if (!packageName.isNullOrBlank()) packageName else targetText
            if (!appToResolve.isNullOrBlank()) {
                Log.d("AccessibilityAgent", "Intercepting OPEN_APP directive locally for: $appToResolve")
                val resolution = LauncherAppResolver.resolveApp(this, appToResolve)
                if (resolution.status == LauncherAppResolver.ResolutionStatus.MATCH_FOUND) {
                    serviceScope.launch {
                        delay(1200) // Enforce mandatory 1200ms settlement delay for inflation
                        val screenState = captureScreenStateWithRetry()
                        val feedback = JSONObject().apply {
                            put("type", "USER_INTENT_FEEDBACK")
                            put("task_id", taskId.ifEmpty { currentTaskId ?: "" })
                            put("action_status", "SUCCESS")
                            put("status", "SUCCESS")
                            put("action", "OPEN_APP")
                            put("target_package", resolution.packageName)
                            put("current_package_name", resolution.packageName)
                            put("timestamp", System.currentTimeMillis())
                        }
                        attachScreenContext(feedback, screenState)
                        sendPayload(feedback)
                    }
                    return // Intercepted successfully, do not proceed to normal execution
                }
            }
        }

        // 3. Handle non-UI directives immediately (can stay on background/caller thread)
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
                val isCompleted = json.optBoolean("is_completed", false)
                val taskId = json.optString("task_id")
                val requiresBiometric = json.optBoolean("requires_biometric", false)

                if (taskId.isNotEmpty()) {
                    currentTaskId = taskId
                }

                Log.d("AccessibilityAgent", "Processing directive: $actionType, task_id: $currentTaskId")

                // Local Loop Detection
                if (actionType == "CLICK" || actionType == "TYPE_TEXT") {
                    val currentScreenState = captureScreenStateWithRetry()
                    val elements = currentScreenState.optJSONArray("screenElements") ?: JSONArray()
                    val domHash = LoopDetector.calculateDomHash(elements)
                    
                    if (LoopDetector.isLoopDetected(packageName ?: "", domHash, actionType, targetText ?: "", elements.length())) {
                        Log.e("AccessibilityAgent", "LOOP DETECTED: Action $actionType on $targetText with same DOM state (3x sequential).")
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

                if (actionType == "FINISH" || actionType == "HARD_STOP" || actionType == "AWAITING_USER_CONFIRMATION" || error == "HARD_STOP" || isCompleted) {
                    Log.d("AccessibilityAgent", "Task finished or safety intercept: $actionType error=$error")
                    if (actionType == "HARD_STOP" || error == "HARD_STOP") {
                        voiceAssistantManager?.stopAllOperations()
                        activeDirectiveJob?.cancel()
                    }
                    currentTaskId = null
                    currentOriginalIntent = null
                    if (actionType == "HARD_STOP" || error == "HARD_STOP") {
                        SessionManager.resetSession()
                    }
                    return@launch
                }

                // 5. Execute normal action
                executeDirectiveAction(json)
            } catch (e: Exception) {
                Log.e("AccessibilityAgent", "Error parsing backend directive", e)
            }
        }
    }

    private suspend fun executeDirectiveAction(json: JSONObject) {
        val actionResult = ExecutionEngine.executeDirectiveAction(this, json)
        val actionType = json.optString("action_type")
        val targetText = json.optString("target_text")
        val packageName = json.optString("package_name")

        if (actionType != "NONE") {
            val targetNode = when (actionType) {
                "OPEN_APP" -> actionResult.packageLaunched ?: packageName
                "CLICK" -> targetText
                "TYPE_TEXT" -> targetText
                "SYSTEM_BACK", "GLOBAL_ACTION_BACK" -> "BACK_BUTTON"
                else -> targetText
            }
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

    private suspend fun sendFeedbackAfterAction(
        result: ActionResult,
        actionType: String,
        targetNode: String? = null,
        targetPackage: String? = null,
        immediate: Boolean = false,
        debounce: Boolean = false,
        capturedTaskId: String? = null,
        capturedOriginalIntent: String? = null
    ) {
        val taskId = capturedTaskId ?: currentTaskId ?: ""
        val originalIntent = capturedOriginalIntent ?: currentOriginalIntent ?: ""
        
        Log.d("AccessibilityAgent", "Sending action feedback for $actionType. Status: ${result.status}")

        // Settlement delay for screen capture
        val delayMs = when (actionType) {
            "TYPE_TEXT" -> 600L
            "OPEN_APP", "GO_HOME", "SWIPE_UP", "CLICK" -> 1200L
            else -> 800L
        }
        if (!immediate) delay(delayMs)

        val screenState = captureScreenStateWithRetry()
        val feedback = JSONObject().apply {
            put("type", "USER_INTENT_FEEDBACK")
            put("task_id", taskId)
            put("original_intent", originalIntent)
            put("action_status", if (result.success) "SUCCESS" else "FAILED")
            put("status", result.status)
            put("action", actionType)
            put("target_text", targetNode)
            put("target_package", targetPackage)
            put("current_package_name", targetPackage)
            put("failure_reason", result.failure_reason)
            put("timestamp", System.currentTimeMillis())
        }
        
        if (result.candidates != null) {
            feedback.put("candidates", JSONArray(result.candidates))
        }

        attachScreenContext(feedback, screenState)
        sendPayload(feedback)
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

    fun clickElementByText(text: String): ActionResult {
        val target = text.lowercase()
        var bestError: ActionResult? = null

        Log.d("AccessibilityAgent", "Attempting robust multi-window click search for: '$text'")

        val activeWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        for (window in activeWindows) {
            val rootNode = window.root ?: continue
            if (isSelfWindow(rootNode)) {
                rootNode.recycle()
                continue
            }
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
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
                    rootNode.recycle()
                    return result.copy(nodeContext = context)
                }
                if (bestError == null || getStatusPriority(result.status) > getStatusPriority(bestError.status)) {
                    bestError = result
                }
            }
            nodes.forEach { it.recycle() }
            
            val recursiveResult = recursiveClickSearchRich(rootNode, target)
            rootNode.recycle()
            
            if (recursiveResult.success) {
                return recursiveResult
            }
            if (bestError == null || getStatusPriority(recursiveResult.status) > getStatusPriority(bestError.status)) {
                bestError = recursiveResult
            }
        }
        return bestError ?: ActionResult(false, "NODE_MISSING", "Element with text '$text' not found.")
    }

    private fun getStatusPriority(status: String): Int = when (status) {
        "ACTION_FAILED" -> 3
        "NOT_CLICKABLE" -> 2
        "OFF_SCREEN" -> 1
        "NODE_MISSING" -> 0
        else -> -1
    }

    private fun recursiveClickSearchRich(node: AccessibilityNodeInfo, target: String): ActionResult {
        var bestError: ActionResult? = null
        var successResult: ActionResult? = null
        
        fun search(curr: AccessibilityNodeInfo): Boolean {
            val nodeText = curr.text?.toString()?.lowercase() ?: ""
            val nodeContentDesc = curr.contentDescription?.toString()?.lowercase() ?: ""
            val isMatch = nodeText.contains(target) || nodeContentDesc.contains(target)

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
                if (search(child)) { child.recycle(); return true }
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
            current = current.parent
        }
        val gestureSuccess = dispatchClickAt(rect.exactCenterX(), rect.exactCenterY())
        return if (gestureSuccess) {
            ActionResult(true, "SUCCESS", nodeContext = getNodeContext(node))
        } else {
            ActionResult(false, "NOT_CLICKABLE", "Click failed.", nodeContext = getNodeContext(node))
        }
    }

    private fun getNodeContext(node: AccessibilityNodeInfo): JSONObject {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return JSONObject().apply {
            put("text", node.text ?: "")
            put("resource_id", node.viewIdResourceName ?: "")
            put("bounds", rect.toShortString())
            put("isVisible", node.isVisibleToUser)
        }
    }

    private fun dispatchClickAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val builder = GestureDescription.Builder().addStroke(stroke)
        var success = false
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { success = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { success = false; latch.countDown() }
        }, null)
        try { latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) {}
        return success
    }

    fun openAppByPackage(packageName: String?, appName: String? = null): ActionResult {
        val targetLabel = appName ?: packageName ?: return ActionResult(false, "FAILED", "No app name or package provided.")
        val resolution = LauncherAppResolver.resolveApp(this, targetLabel)
        return when (resolution.status) {
            LauncherAppResolver.ResolutionStatus.MATCH_FOUND -> ActionResult(true, "SUCCESS", packageLaunched = resolution.packageName)
            LauncherAppResolver.ResolutionStatus.AMBIGUOUS_MATCH -> ActionResult(false, "AMBIGUOUS_MATCH", "Ambiguous match", candidates = resolution.candidates)
            LauncherAppResolver.ResolutionStatus.NO_MATCH -> ActionResult(false, "NO_MATCH", "App not found.")
        }
    }

    fun swipeUpToOpenDrawer(): Boolean {
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(metrics.widthPixels / 2f, metrics.heightPixels * 0.85f)
            lineTo(metrics.widthPixels / 2f, metrics.heightPixels * 0.15f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val builder = GestureDescription.Builder().addStroke(stroke)
        var success = false
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { success = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }, null)
        try { latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) {}
        return success
    }

    suspend fun typeTextIntoFocusedNode(textToType: String): ActionResult {
        val activeWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        var targetNode: AccessibilityNodeInfo? = null
        for (window in activeWindows) {
            val root = window.root ?: continue
            if (isSelfWindow(root)) { root.recycle(); continue }
            targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (targetNode != null && targetNode.isEditable) { root.recycle(); break }
            root.recycle()
        }
        if (targetNode == null) {
            for (window in activeWindows) {
                val root = window.root ?: continue
                if (isSelfWindow(root)) { root.recycle(); continue }
                targetNode = findFirstEditableNode(root)
                if (targetNode != null) { root.recycle(); break }
                root.recycle()
            }
        }
        return if (targetNode != null && targetNode.isEditable) {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType) }
            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (success) {
                delay(150)
                try { Runtime.getRuntime().exec("input keyevent 66").waitFor() } catch (e: Exception) {}
                val ctx = getNodeContext(targetNode); targetNode.recycle()
                ActionResult(true, "SUCCESS", nodeContext = ctx)
            } else {
                val ctx = getNodeContext(targetNode); targetNode.recycle()
                ActionResult(false, "ACTION_FAILED", "SET_TEXT failed.", nodeContext = ctx)
            }
        } else {
            ActionResult(false, "NODE_MISSING", "No editable field.")
        }
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditableNode(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    fun resolveCoordinates(x: Double, y: Double): android.graphics.Point {
        val metrics = resources.displayMetrics
        return android.graphics.Point(
            (x.coerceIn(0.0, 1.0) * metrics.widthPixels).toInt(),
            (y.coerceIn(0.0, 1.0) * metrics.heightPixels).toInt()
        )
    }

    fun tapAtCoordinates(x: Int, y: Int): Boolean = dispatchClickAt(x.toFloat(), y.toFloat())

    fun performScroll(direction: String): ActionResult {
        val root = findActiveRoot() ?: return ActionResult(false, "NODE_MISSING")
        val scrollable = findScrollableNode(root) ?: run {
            root.recycle()
            return ActionResult(false, "NODE_MISSING", "No scrollable container.")
        }
        val action = if (direction == "DOWN") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val success = scrollable.performAction(action)
        scrollable.recycle(); root.recycle()
        return ActionResult(success, if (success) "SUCCESS" else "ACTION_FAILED")
    }

    private fun findActiveRoot(): AccessibilityNodeInfo? {
        var root = rootInActiveWindow
        if (root == null || root.childCount == 0 || isSelfWindow(root)) {
            val appWindow = windows.firstOrNull { window ->
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                window.root != null &&
                !isSelfWindow(window.root!!)
            } ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null }
            
            if (appWindow != null) {
                root?.recycle()
                root = appWindow.root
            }
        }
        return root
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
        try { latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) {}
        return success
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private suspend fun captureScreenStateWithRetry(): JSONObject {
        var attempts = 0
        while (attempts < 3) {
            val targetRoot = findActiveRoot()

            if (targetRoot != null && targetRoot.childCount > 0) {
                val elements = JSONArray()
                extractElements(targetRoot, elements)
                
                if (elements.length() > 0) {
                    Log.d("AccessibilityAgent", "Target Package: ${targetRoot.packageName}, Elements Extracted: ${elements.length()}")
                    
                    val result = JSONObject().apply {
                        put("packageName", targetRoot.packageName ?: "")
                        put("screenElements", elements)
                    }
                    targetRoot.recycle()
                    return result
                }
            }
            
            targetRoot?.recycle()
            attempts++
            if (attempts < 3) {
                // On-Device Retry Before Dispatching Payload
                delay(250)
            }
        }
        Log.e("AccessibilityAgent", "Capture failed: No valid root node found after 3 attempts.")
        return JSONObject()
    }

    private fun extractElements(node: AccessibilityNodeInfo, elements: JSONArray) {
        if (isSelfWindow(node)) return

        if (node.isVisibleToUser) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val element = JSONObject().apply {
                put("text", node.text ?: "")
                put("content_description", node.contentDescription ?: "")
                put("resource_id", node.viewIdResourceName ?: "")
                put("class_name", node.className ?: "")
                put("package_name", node.packageName ?: "")
                put("bounds", rect.toShortString())
                put("clickable", node.isClickable)
            }
            elements.put(element)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractElements(child, elements)
            child.recycle()
        }
    }

    private fun attachScreenContext(payload: JSONObject, screenState: JSONObject) {
        payload.put("screen_context", screenState)
        payload.put("current_package", screenState.optString("packageName"))
    }
}

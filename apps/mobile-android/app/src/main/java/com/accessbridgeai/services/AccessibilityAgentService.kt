package com.accessbridgeai.services

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.accessbridgeai.utils.ExecutionEngine
import com.accessbridgeai.MainActivity
import com.accessbridgeai.WebSocketManager
import com.accessbridgeai.VoiceAssistantManager
import com.accessbridgeai.utils.AppResolver
import com.accessbridgeai.utils.LoopDetector
import com.accessbridgeai.data.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AccessibilityAgentService : AccessibilityService() {

    companion object {
        var instance: AccessibilityAgentService? = null
            private set

        private val _serviceInstance = MutableStateFlow<AccessibilityAgentService?>(null)
        val serviceInstance: StateFlow<AccessibilityAgentService?> = _serviceInstance
    }

    var webSocketManager: WebSocketManager? = null
    var voiceAssistantManager: VoiceAssistantManager? = null
    private lateinit var sessionManager: SessionManager

    private var currentSessionId: String? = null
    private var sessionResetJob: Job? = null
    private val sessionInactivityTimeout = 5 * 60 * 1000L // 5 minutes

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeDirectiveJob: Job? = null

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
        val failureReason: String? = null,
        val nodeContext: JSONObject? = null,
        val packageLaunched: String? = null,
        val candidates: List<String>? = null
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _serviceInstance.value = this
        Log.d("AccessibilityAgent", "Service Connected")

        sessionManager = SessionManager(this)

        // Native Accessibility Button Callback Integration (API 26+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            setupAccessibilityButton()
        }

        webSocketManager = WebSocketManager(
            serverUrl = "ws://192.168.29.217:3000/ws/agent", // Placeholder, should be configurable
            sessionManager = sessionManager,
            onMessageReceived = { json ->
                handleBackendDirective(json)
            },
            onConnectionStatusChanged = { isConnected ->
                _connectionStatus.value = isConnected
            }
        )
        webSocketManager?.connect()

        voiceAssistantManager = VoiceAssistantManager(
            context = this,
            onPartialResults = { transcript ->
                _partialTranscript.value = transcript
            },
            onSpeechRecognized = { intent ->
                sendUserIntent(intent)
            },
            onSpeechStateChanged = { state ->
                _speechState.value = state
            }
        )
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun setupAccessibilityButton() {
        val callback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController?) {
                Log.d("AccessibilityAgent", "Native accessibility button clicked.")
                startAssistant()
            }

            override fun onAvailabilityChanged(controller: AccessibilityButtonController?, available: Boolean) {
                Log.d("AccessibilityAgent", "Accessibility button availability changed: $available")
            }
        }
        accessibilityButtonController.registerAccessibilityButtonCallback(callback)
    }

    fun startAssistant() {
        refreshSession()
        voiceAssistantManager?.startListening()
    }

    private fun refreshSession() {
        if (currentSessionId == null) {
            currentSessionId = UUID.randomUUID().toString()
            Log.d("AccessibilityAgent", "Generated new global session ID: $currentSessionId")
        }
        resetSessionTimer()
    }

    private fun resetSessionTimer() {
        sessionResetJob?.cancel()
        sessionResetJob = serviceScope.launch {
            delay(sessionInactivityTimeout)
            Log.d("AccessibilityAgent", "Global session expired due to inactivity.")
            currentSessionId = null
        }
    }

    fun handleUserCancel() {
        val currentSessionId = sessionManager.getSessionId()
        val payload = JSONObject().apply {
            put("type", "HARD_STOP")
            put("session_id", currentSessionId)
            put("timestamp", System.currentTimeMillis())
        }
        sendPayload(payload)
        performTakeOverCleanup()
    }

    /**
     * Resets the agent state, stops all active voice/tasks, and rotates the session ID.
     * Called on HARD_STOP, USER_TAKE_OVER, or manual cancellation.
     */
    fun performTakeOverCleanup() {
        Log.d("AccessibilityAgent", "Performing Takeover/Cleanup: Resetting session.")
        voiceAssistantManager?.stopAllOperations()
        activeDirectiveJob?.cancel()
        currentTaskId = null
        currentOriginalIntent = null
        executionAttempts.clear()
        LoopDetector.clearHistory()
        currentSessionId = null
        sessionResetJob?.cancel()
        sessionManager.resetSession()
    }

    private fun isSelfWindow(node: AccessibilityNodeInfo): Boolean {
        return node.packageName?.toString() == packageName
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Log events if needed
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        return super.onKeyEvent(event)
    }

    fun sendPayload(payload: JSONObject, purgeQueue: Boolean = false): Boolean {
        return try {
            refreshSession() // Ensure session is active and timer is reset
            
            if (!payload.has("session_id")) {
                payload.put("session_id", currentSessionId)
            }
            
            // Enrich with task_id if not present but available
            if (!payload.has("task_id") && !currentTaskId.isNullOrEmpty()) {
                payload.put("task_id", currentTaskId)
            }
            
            // Enrich with metadata if not present
            if (!payload.has("timestamp")) {
                payload.put("timestamp", System.currentTimeMillis())
            }
            
            val success = webSocketManager?.sendPayload(payload, purgeQueue) ?: false
            if (success) {
                Log.d("AccessibilityAgent", "Payload sent: ${payload.optString("type")}")
            } else {
                Log.e("AccessibilityAgent", "Failed to send payload: ${payload.optString("type")}")
            }
            success
        } catch (e: Exception) {
            Log.e("AccessibilityAgent", "Error sending payload", e)
            false
        }
    }

    fun testOfflineDomCompression() {
        serviceScope.launch {
            val root = findActiveRoot()
            if (root != null) {
                val elements = JSONArray()
                extractElements(root, elements)
                val domHash = LoopDetector.calculateDomHash(elements)
                Log.d("AccessibilityAgent", "Offline DOM Scan: Found ${elements.length()} elements. Hash: $domHash")
                
                val testPayload = JSONObject().apply {
                    put("type", "DOM_SNAPSHOT")
                    put("elements_count", elements.length())
                    put("dom_hash", domHash)
                }
                sendPayload(testPayload)
            } else {
                Log.e("AccessibilityAgent", "Offline DOM Scan failed: No active root found.")
            }
        }
    }

    fun sendUserIntent(intent: String) {
        val cleanIntent = intent.trim()
        if (cleanIntent.isEmpty()) {
            Log.w("AccessibilityAgent", "Suppressed USER_INTENT dispatch: Empty transcript.")
            voiceAssistantManager?.stopAllOperations() // Resets UI state to IDLE without clearing session
            return
        }
        
        currentOriginalIntent = cleanIntent
        executionAttempts.clear()
        LoopDetector.clearHistory()

        serviceScope.launch {
            val resolution = AppResolver.resolveApp(this@AccessibilityAgentService, cleanIntent)
            
            // Tier 1 & 2 Local App Resolver Interceptor
            // AND MULTI_STEP_INTENT with a matched package
            val nativePackage = resolution.bestMatch?.packageName
            if (nativePackage != null && (resolution.confidence == AppResolver.Confidence.HIGH_CONFIDENCE || 
                resolution.confidence == AppResolver.Confidence.MULTI_STEP_INTENT)) {
                
                val pm = packageManager
                val launchIntent = pm.getLaunchIntentForPackage(nativePackage)
                if (launchIntent != null) {
                    try {
                        val appLabel = resolution.bestMatch.label
                        voiceAssistantManager?.speak("Opening $appLabel")
                        
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        
                        delay(1200) // Settlement delay
                        
                        // If it's HIGH_CONFIDENCE, we stop here. 
                        // If it's MULTI_STEP_INTENT, we continue to send to gateway.
                        if (resolution.confidence == AppResolver.Confidence.HIGH_CONFIDENCE) {
                            sendFeedbackAfterAction(
                                result = ActionResult(true, "SUCCESS"),
                                actionType = "OPEN_APP",
                                targetPackage = nativePackage,
                                intent = cleanIntent,
                                isCompleted = true
                            )
                            return@launch
                        }
                    } catch (e: Exception) {
                        Log.e("AccessibilityAgent", "Local launch failed for $nativePackage", e)
                    }
                }
            }

            when (resolution.confidence) {
                AppResolver.Confidence.HIGH_CONFIDENCE -> {
                    // Handled above
                }
                AppResolver.Confidence.AMBIGUOUS -> {
                    val candidateLabels = resolution.candidates.map { it.label }
                    val speech = "Did you mean ${candidateLabels.joinToString(" or ")}?"
                    voiceAssistantManager?.speak(speech)
                    
                    sendFeedbackAfterAction(
                        result = ActionResult(false, "AMBIGUOUS_MATCH", candidates = candidateLabels),
                        actionType = "OPEN_APP",
                        intent = cleanIntent
                    )
                    return@launch
                }
                AppResolver.Confidence.NOT_INSTALLED -> {
                    val appName = cleanIntent.replace(Regex("^(open|launch|start|go to|show me|run)\\s+", RegexOption.IGNORE_CASE), "").trim()
                    val speech = "$appName is not installed. Download from Play Store?"
                    voiceAssistantManager?.speak(speech)
                    
                    sendFeedbackAfterAction(
                        result = ActionResult(false, "NOT_INSTALLED"),
                        actionType = "OPEN_APP",
                        intent = cleanIntent
                    )
                    return@launch
                }
                AppResolver.Confidence.MULTI_STEP_INTENT -> {
                    Log.d("AccessibilityAgent", "Multi-step intent detected: $cleanIntent. Matched package: $nativePackage. Forwarding to gateway.")
                }
            }

            val screenState = captureScreenStateWithRetry()
            
            val payload = JSONObject().apply {
                put("type", "USER_INTENT")
                put("transcript", cleanIntent)
                put("user_id", "default_user")
                put("input_type", "TEXT")
                put("session_id", sessionManager.getSessionId())
                if (resolution.confidence == AppResolver.Confidence.MULTI_STEP_INTENT) {
                    put("package_hint", resolution.bestMatch?.packageName)
                }
                if (!currentTaskId.isNullOrEmpty()) {
                    put("task_id", currentTaskId)
                }
                put("timestamp", System.currentTimeMillis())
            }
            
            attachScreenContext(payload, screenState)
            sendPayload(payload, purgeQueue = true)
        }
    }

    private fun handleBackendDirective(json: JSONObject) {
        try {
            Log.d("AccessBridgeService", "Incoming directive received: ${json.toString(2)}")
            
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
                
                if (error == "HARD_STOP") {
                    performTakeOverCleanup()
                } else {
                    executionAttempts.clear()
                    voiceAssistantManager?.stopAllOperations()
                    activeDirectiveJob?.cancel()
                }

                serviceScope.launch {
                    try {
                        Toast.makeText(this@AccessibilityAgentService, notice, Toast.LENGTH_LONG).show()
                        voiceAssistantManager?.speak(notice)
                    } catch (e: Exception) {
                        Log.e("AccessibilityAgent", "Failed to handle backend error UI", e)
                    }
                }
                return
            }

            val audioResponse = json.optString("audio_response")
            val executionState = json.optString("execution_state")
            val speechResponse = json.optString("speech_response")
            val actionType = json.optString("action_type")
            val languageCode = json.optString("language_code", "en-IN")
            
            val isStopDirective = executionState == "USER_TAKE_OVER" || 
                                 actionType == "USER_TAKE_OVER" || 
                                 actionType == "HARD_STOP"
            
            // Step 2: Handle multi-turn speech response and mic re-activation
            val onSpeechFinished = {
                serviceScope.launch {
                    if (executionState == "PAUSE_FOR_USER_INPUT") {
                        Log.d("AccessibilityAgent", "Directive PAUSE_FOR_USER_INPUT: Re-activating mic.")
                        voiceAssistantManager?.startListening()
                    } else if (isStopDirective) {
                        Log.d("AccessibilityAgent", "Stop directive received: Performing cleanup.")
                        performTakeOverCleanup()
                    }
                }
            }

            if (audioResponse.isNotBlank()) {
                voiceAssistantManager?.playAudioResponse(audioResponse) { onSpeechFinished() }
            } else if (speechResponse.isNotBlank()) {
                voiceAssistantManager?.speak(speechResponse, languageCode) { onSpeechFinished() }
            } else {
                onSpeechFinished()
            }

            if (json.optBoolean("stop_listening", false)) {
                voiceAssistantManager?.stopAudioStreaming()
            }

            // If it's a stop directive, we already scheduled cleanup in onSpeechFinished.
            // If no speech was pending, cleanup happened immediately in the 'else' block.
            if (isStopDirective) {
                return
            }

            activeDirectiveJob?.cancel()
            activeDirectiveJob = serviceScope.launch {
                try {
                    val targetText = json.optString("target_text")
                    val packageName = json.optString("package_name")
                    val isCompleted = json.optBoolean("is_completed", false)
                    val taskId = json.optString("task_id")
                    val requiresBiometric = json.optBoolean("requires_biometric", false)

                    if (taskId.isNotEmpty()) {
                        currentTaskId = taskId
                    }

                    // PAUSE_FOR_USER_INPUT: Re-activation is handled by onSpeechFinished
                    if (executionState == "PAUSE_FOR_USER_INPUT") {
                        return@launch
                    }

                    if (actionType == "FINISH" || actionType == "AWAITING_USER_CONFIRMATION" || isCompleted) {
                        currentTaskId = null
                        currentOriginalIntent = null
                        return@launch
                    }

                    // Tier 1 & 2 Local App Resolver Interceptor
                    if (actionType == "OPEN_APP") {
                        val appToResolve = if (!packageName.isNullOrBlank()) packageName else targetText
                        if (!appToResolve.isNullOrBlank()) {
                            Log.d("AccessibilityAgent", "Intercepting OPEN_APP directive locally for: $appToResolve")
                            val resolution = AppResolver.resolveApp(this@AccessibilityAgentService, appToResolve)
                            
                            when (resolution.confidence) {
                                AppResolver.Confidence.HIGH_CONFIDENCE -> {
                                    val bestMatch = resolution.bestMatch
                                    if (bestMatch != null) {
                                        val pm = packageManager
                                        val launchIntent = pm.getLaunchIntentForPackage(bestMatch.packageName)
                                        if (launchIntent != null) {
                                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            startActivity(launchIntent)
                                            
                                            delay(1200) // Settlement delay for inflation
                                            sendFeedbackAfterAction(
                                                result = ActionResult(true, "SUCCESS"),
                                                actionType = "OPEN_APP",
                                                targetPackage = bestMatch.packageName,
                                                isCompleted = isCompleted
                                            )
                                            return@launch
                                        }
                                    }
                                }
                                AppResolver.Confidence.AMBIGUOUS -> {
                                    val candidates = resolution.candidates.map { it.label }.joinToString(" or ")
                                    try {
                                        voiceAssistantManager?.speak("Did you mean $candidates?")
                                    } catch (e: Exception) {
                                        Log.e("AccessibilityAgent", "Failed to speak ambiguous match", e)
                                    }
                                    sendFeedbackAfterAction(
                                        result = ActionResult(false, "AMBIGUOUS_MATCH", candidates = resolution.candidates.map { it.label }),
                                        actionType = "OPEN_APP"
                                    )
                                    return@launch
                                }
                                AppResolver.Confidence.NOT_INSTALLED, AppResolver.Confidence.MULTI_STEP_INTENT -> {
                                    try {
                                        voiceAssistantManager?.speak("$appToResolve is not installed. Download from Play Store?")
                                    } catch (e: Exception) {
                                        Log.e("AccessibilityAgent", "Failed to speak no match", e)
                                    }
                                    sendFeedbackAfterAction(
                                        result = ActionResult(false, "NOT_INSTALLED"),
                                        actionType = "OPEN_APP"
                                    )
                                    return@launch
                                }
                            }
                        }
                    }

                    // Local Loop Detection
                    if (actionType == "CLICK" || actionType == "TYPE_TEXT") {
                        val currentScreenState = captureScreenStateWithRetry()
                        val elements = currentScreenState.optJSONArray("screenElements") ?: JSONArray()
                        val domHash = LoopDetector.calculateDomHash(elements)
                        
                        if (LoopDetector.isLoopDetected(packageName ?: "", domHash, actionType, targetText ?: "", elements.length())) {
                            Log.e("AccessibilityAgent", "LOOP DETECTED: $actionType on $targetText")
                            sendFeedbackAfterAction(
                                result = ActionResult(false, "LOOP_DETECTED", failureReason = "Execution loop detected locally (3x sequential)."),
                                actionType = actionType
                            )
                            return@launch
                        }
                        LoopDetector.recordAction(packageName ?: "", domHash, actionType, targetText ?: "", elements.length())
                    }

                    if (requiresBiometric || actionType == "CONFIRM_BIOMETRIC") {
                        val mainActivity = MainActivity.instance
                        if (mainActivity == null) {
                            sendFeedbackAfterAction(
                                result = ActionResult(false, "FAILED", failureReason = "BIOMETRIC_FAILED"),
                                actionType = actionType
                            )
                            return@launch
                        }
                        mainActivity.showBiometricPrompt(
                            onSuccess = {
                                serviceScope.launch {
                                    if (actionType == "CONFIRM_BIOMETRIC") {
                                        sendFeedbackAfterAction(
                                            result = ActionResult(true, "SUCCESS"),
                                            actionType = "CONFIRM_BIOMETRIC"
                                        )
                                    } else {
                                        executeDirectiveAction(json)
                                    }
                                }
                            },
                            onError = {
                                serviceScope.launch {
                                    sendFeedbackAfterAction(
                                        result = ActionResult(false, "FAILED", failureReason = "BIOMETRIC_FAILED"),
                                        actionType = actionType
                                    )
                                }
                            }
                        )
                        return@launch
                    }

                    executeDirectiveAction(json)
                } catch (e: Exception) {
                    Log.e("AccessibilityAgent", "Error parsing/executing backend directive", e)
                }
            }
        } catch (e: Exception) {
            Log.e("AccessibilityAgent", "CRITICAL: handleBackendDirective crashed", e)
        }
    }

    private suspend fun executeDirectiveAction(json: JSONObject) {
        val actionType = json.optString("action_type")
        val isCompleted = json.optBoolean("is_completed", false)
        val actionResult = ExecutionEngine.executeDirectiveAction(this, json)
        val targetText = json.optString("target_text")
        val packageName = json.optString("package_name")
        val taskId = json.optString("task_id")

        // Settlement Delays: 1200ms for Launches/Clicks, 600ms for Typing, 150ms for everything else
        val delayMs = when (actionType) {
            "OPEN_APP", "CLICK", "GO_HOME", "SWIPE_UP", "SWIPE_HORIZONTAL" -> 1200L
            "TYPE_TEXT" -> 600L
            else -> 150L
        }
        delay(delayMs)

        sendFeedbackAfterAction(
            result = actionResult,
            actionType = actionType,
            targetNode = targetText,
            targetPackage = packageName,
            isCompleted = isCompleted,
            taskId = taskId
        )
    }

    private suspend fun sendFeedbackAfterAction(
        result: ActionResult,
        actionType: String,
        targetNode: String? = null,
        targetPackage: String? = null,
        intent: String? = null,
        taskId: String? = null,
        isCompleted: Boolean = false,
        extraData: Map<String, Any>? = null
    ) {
        val screenState = captureScreenStateWithRetry()
        val feedback = JSONObject().apply {
            put("type", "USER_INTENT_FEEDBACK")
            put("message_type", "USER_INTENT_FEEDBACK")
            put("user_id", "default_user")
            put("input_type", "TEXT")
            put("task_id", taskId ?: currentTaskId ?: "")
            put("session_id", sessionManager.getSessionId())
            put("action_status", if (result.success) "SUCCESS" else "FAILED")
            put("status", result.status)
            put("target_action", actionType)
            put("action_type", actionType) // Ensuring action_type is also sent
            put("target_node", targetNode)
            put("target_package", targetPackage ?: result.packageLaunched)
            put("failure_reason", result.failureReason)
            put("transcript", intent ?: currentOriginalIntent ?: "")
            put("user_prompt", intent ?: currentOriginalIntent ?: "")
            put("is_completed", isCompleted)
            put("timestamp", System.currentTimeMillis())
            
            if (result.candidates != null) {
                put("candidates", JSONArray(result.candidates))
            }
            
            extraData?.forEach { (key, value) ->
                if (value is List<*>) {
                    put(key, JSONArray(value))
                } else {
                    put(key, value)
                }
            }
        }
        
        attachScreenContext(feedback, screenState)
        sendPayload(feedback)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _serviceInstance.value = null
        serviceScope.cancel()
    }

    fun clickElementByText(text: String): ActionResult {
        val root = findActiveRoot() ?: return ActionResult(false, "NO_ROOT")
        return recursiveClickSearchRich(root, text)
    }

    private fun recursiveClickSearchRich(node: AccessibilityNodeInfo, targetText: String): ActionResult {
        var bestMatch: AccessibilityNodeInfo? = null
        var bestScore = 0.0

        fun search(n: AccessibilityNodeInfo) {
            val label = (n.text ?: n.contentDescription ?: "").toString().lowercase()
            if (label.contains(targetText.lowercase())) {
                val score = if (label == targetText.lowercase()) 1.0 else 0.5
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = n
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { search(it) }
            }
        }

        search(node)
        bestMatch?.let { return attemptClickRich(it) }
        return ActionResult(false, "ELEMENT_NOT_FOUND")
    }

    private fun attemptClickRich(node: AccessibilityNodeInfo): ActionResult {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return ActionResult(success, if (success) "SUCCESS" else "CLICK_FAILED", nodeContext = getNodeContext(node))
            }
            current = current.parent
        }
        return ActionResult(false, "NOT_CLICKABLE")
    }

    private fun getNodeContext(node: AccessibilityNodeInfo): JSONObject {
        val json = JSONObject()
        json.put("text", node.text)
        json.put("content_description", node.contentDescription)
        json.put("resource_id", node.viewIdResourceName)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        json.put("bounds", JSONObject().apply {
            put("left", bounds.left); put("top", bounds.top); put("right", bounds.right); put("bottom", bounds.bottom)
        })
        return json
    }

    fun dispatchClickAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun openAppByPackage(packageName: String?, appName: String? = null): ActionResult {
        val targetLabel = appName ?: packageName ?: return ActionResult(false, "FAILED", "No app name or package provided.")
        val resolution = AppResolver.resolveApp(this, targetLabel)
        return when (resolution.confidence) {
            AppResolver.Confidence.HIGH_CONFIDENCE -> ActionResult(true, "SUCCESS", packageLaunched = resolution.bestMatch?.packageName)
            AppResolver.Confidence.AMBIGUOUS -> ActionResult(false, "AMBIGUOUS_MATCH", "Ambiguous match", candidates = resolution.candidates.map { it.label })
            AppResolver.Confidence.NOT_INSTALLED, AppResolver.Confidence.MULTI_STEP_INTENT -> ActionResult(false, "NO_MATCH", "App not found.")
        }
    }

    fun swipeUpToOpenDrawer(): Boolean {
        val metrics = resources.displayMetrics
        val startY = metrics.heightPixels * 0.8f
        val endY = metrics.heightPixels * 0.2f
        val centerX = metrics.widthPixels / 2.0f
        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun typeTextIntoFocusedNode(text: String): ActionResult {
        val root = findActiveRoot() ?: return ActionResult(false, "NO_ROOT")
        val node = findFirstEditableNode(root) ?: return ActionResult(false, "NO_EDITABLE_NODE")
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        return ActionResult(success, if (success) "SUCCESS" else "TYPE_FAILED")
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findFirstEditableNode(it) }
            if (found != null) return found
        }
        return null
    }

    fun resolveCoordinates(x: Double, y: Double): Point {
        val metrics = resources.displayMetrics
        return Point((x * metrics.widthPixels).toInt(), (y * metrics.heightPixels).toInt())
    }

    fun tapAtCoordinates(x: Int, y: Int): Boolean = dispatchClickAt(x.toFloat(), y.toFloat())

    fun performScroll(direction: String): ActionResult {
        val root = findActiveRoot() ?: return ActionResult(false, "NO_ROOT")
        val scrollable = findScrollableNode(root) ?: return ActionResult(false, "NO_SCROLLABLE_NODE")
        val action = if (direction == "DOWN") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val success = scrollable.performAction(action)
        return ActionResult(success, if (success) "SUCCESS" else "SCROLL_FAILED")
    }

    fun findActiveRoot(): AccessibilityNodeInfo? {
        val windowsList = windows
        // 1. Try to find the primary application window that is active
        for (window in windowsList) {
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive) {
                val root = window.root
                if (root != null && !isSelfWindow(root)) return root
            }
        }
        // 2. Fallback: Search all application windows if active one is null/self
        for (window in windowsList) {
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val root = window.root
                if (root != null && !isSelfWindow(root)) return root
            }
        }
        // 3. Last resort: rootInActiveWindow
        return rootInActiveWindow?.takeIf { !isSelfWindow(it) }
    }

    fun scrollLauncherPage(direction: String): Boolean {
        val metrics = resources.displayMetrics
        val startX = if (direction == "RIGHT") metrics.widthPixels * 0.8f else metrics.widthPixels * 0.2f
        val endX = if (direction == "RIGHT") metrics.widthPixels * 0.2f else metrics.widthPixels * 0.8f
        val centerY = metrics.heightPixels / 2.0f
        val path = Path().apply {
            moveTo(startX, centerY)
            lineTo(endX, centerY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findScrollableNode(it) }
            if (found != null) return found
        }
        return null
    }

    suspend fun captureScreenStateWithRetry(): JSONObject {
        // Step 3: Ensure 250ms asynchronous settlement delay before querying window tree
        delay(250)

        var root = findActiveRoot()
        var elements = JSONArray()

        if (root != null) {
            extractElements(root, elements)
        }

        // Step 2b: If primary root yields 0 elements, specifically iterate windows for TYPE_APPLICATION
        if (elements.length() == 0) {
            val windowsList = windows
            for (window in windowsList) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val r = window.root
                    if (r != null && !isSelfWindow(r)) {
                        val tempElements = JSONArray()
                        extractElements(r, tempElements)
                        if (tempElements.length() > 0) {
                            root = r
                            elements = tempElements
                            break
                        }
                    }
                }
            }
        }

        // Tier 4 Settlement: If UI seems sparse (<= 10 nodes), wait for inflation to prevent false 'not found'
        if (elements.length() <= 10) {
            Log.d("AccessibilityAgent", "Sparse DOM detected (${elements.length()} elements). Applying 500ms settlement backoff.")
            delay(500)
            root = findActiveRoot()
            if (root != null) {
                elements = JSONArray()
                extractElements(root, elements)
            }
        }

        var attempts = 0
        while (attempts < 5 && elements.length() == 0) {
            attempts++
            delay(300)
            root = findActiveRoot()
            if (root != null) {
                elements = JSONArray()
                extractElements(root, elements)
            }
        }

        if (elements.length() > 0 && root != null) {
            val json = JSONObject()
            json.put("packageName", root.packageName)
            json.put("screenElements", elements)
            json.put("timestamp", System.currentTimeMillis())
            return json
        }

        // Final fallback: Check ANY window for elements if still empty
        if (elements.length() == 0) {
            val windowsList = windows
            for (window in windowsList) {
                val r = window.root
                if (r != null && !isSelfWindow(r)) {
                    extractElements(r, elements)
                    if (elements.length() > 0) {
                        return JSONObject().apply {
                            put("packageName", r.packageName)
                            put("screenElements", elements)
                            put("timestamp", System.currentTimeMillis())
                        }
                    }
                }
            }
        }

        return JSONObject().apply {
            put("packageName", "unknown")
            put("screenElements", JSONArray())
            put("error", "DOM_EXTRACTION_FAILED")
        }
    }

    private fun extractElements(node: AccessibilityNodeInfo, array: JSONArray) {
        if (!node.isVisibleToUser) return
        if (node.isClickable || node.isCheckable || node.isEditable || !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()) {
            val el = JSONObject()
            el.put("text", node.text)
            el.put("content_description", node.contentDescription)
            el.put("resource_id", node.viewIdResourceName)
            el.put("class", node.className)
            el.put("is_clickable", node.isClickable)
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            el.put("bounds", JSONObject().apply {
                put("left", bounds.left); put("top", bounds.top); put("right", bounds.right); put("bottom", bounds.bottom)
            })
            array.put(el)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { extractElements(it, array) }
        }
    }

    private fun attachScreenContext(payload: JSONObject, screenState: JSONObject) {
        payload.put("current_package_name", screenState.optString("packageName"))
        payload.put("screenElements", screenState.optJSONArray("screenElements"))
        payload.put("dom_hash", LoopDetector.calculateDomHash(screenState.optJSONArray("screenElements") ?: JSONArray()))
    }
}

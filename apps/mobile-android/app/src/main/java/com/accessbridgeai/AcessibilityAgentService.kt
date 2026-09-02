package com.accessbridgeai

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
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
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
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
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info

        instance = this
        Log.d("AccessibilityAgent", "Accessibility Service Connected & Active!")
        initializeComponents()
        setupAccessibilityButton()
    }

    private fun setupAccessibilityButton() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val controller = accessibilityButtonController
            controller.registerAccessibilityButtonCallback(object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController?) {
                    startAssistant()
                }

                override fun onAvailabilityChanged(controller: AccessibilityButtonController?, available: Boolean) {
                    Log.d("AccessibilityAgent", "Accessibility Button available: $available")
                }
            })
        }
    }

    private fun initializeComponents() {
        webSocketManager = WebSocketManager(
            serverUrl = "ws://192.168.29.217:3000",
            onMessageReceived = { json -> handleBackendDirective(json) },
            onConnectionStatusChanged = { connected ->
                Log.d("AccessibilityAgent", "WebSocket connected: $connected")
                _connectionStatus.value = connected
            }
        )
        webSocketManager?.connect()

        voiceAssistantManager = VoiceAssistantManager(
            context = this,
            onSpeechRecognized = { transcript ->
                Log.d("AccessibilityAgent", "Final Transcript: $transcript")
                _partialTranscript.value = transcript
                sendTranscriptToBackend(transcript)
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

    fun startAssistant() {
        voiceAssistantManager?.startListening()
    }

    fun sendPayload(payload: JSONObject) {
        val success = webSocketManager?.sendPayload(payload) ?: false
        if (success) {
            Log.d("AccessibilityAgent", "Successfully queued payload for transmission.")
        } else {
            Log.e("AccessibilityAgent", "Failed to send payload via WebSocket. Connection state: ${_connectionStatus.value}")
        }
    }

    /**
     * LOCAL DEBUG: Prints the JSON payload matching the WebSocket format directly to Logcat.
     * Allows local verification of DOM extraction and metadata enrichment.
     */
    fun testOfflineDomCompression() {
        serviceScope.launch {
            Log.d("AccessibilityAgent", "Running offline Phase 1 DOM extraction test...")
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

    private fun sendTranscriptToBackend(transcript: String) {
        val taskId = java.util.UUID.randomUUID().toString()
        currentTaskId = taskId
        currentOriginalIntent = transcript
        executionAttempts.clear()
        
        serviceScope.launch {
            val screenState = captureScreenStateWithRetry()
            val payload = JSONObject().apply {
                put("type", "USER_INTENT")
                put("task_id", taskId)
                put("transcript", transcript)
                put("timestamp", System.currentTimeMillis())
            }
            attachScreenContext(payload, screenState)
            Log.d("AccessibilityAgent", "Sending Payload: ${payload.toString(2)}")
            sendPayload(payload)
        }
    }

    private fun handleBackendDirective(json: JSONObject) {
        try {
            val speechResponse = json.optString("speech_response")
            val actionType = json.optString("action_type")
            val targetText = json.optString("target_text")
            val packageName = json.optString("package_name")
            val textToType = json.optString("text_to_type")
            val scrollDirection = json.optString("scroll_direction")
            val executionMode = json.optString("execution_mode")
            val error = json.optString("error")
            val isCompleted = json.optBoolean("is_completed", false)
            val taskId = json.optString("task_id")
            val tapX = if (json.has("tap_x") && !json.isNull("tap_x")) json.optDouble("tap_x") else Double.NaN
            val tapY = if (json.has("tap_y") && !json.isNull("tap_y")) json.optDouble("tap_y") else Double.NaN

            if (taskId.isNotEmpty()) {
                currentTaskId = taskId
            }

            Log.d("AccessibilityAgent", "Processing directive: $actionType, task_id: $currentTaskId")

            // 3. Text-to-Speech (TTS) Execution
            if (speechResponse.isNotBlank()) {
                voiceAssistantManager?.speak(speechResponse)
            }

            if (actionType == "FINISH" || actionType == "HARD_STOP" || error == "HARD_STOP" || isCompleted) {
                Log.d("AccessibilityAgent", "Task finished or safety intercept: $actionType error=$error")
                return
            }

            var actionResult: ActionResult? = null

            when (actionType) {
                "OPEN_APP" -> {
                    if (!packageName.isNullOrBlank()) {
                        actionResult = openAppByPackage(packageName, targetText)
                    } else {
                        actionResult = ActionResult(false, "INVALID_ARGUMENT", "Package name is missing in OPEN_APP directive.")
                    }
                }
                "CLICK" -> {
                    actionResult = when {
                        executionMode == "COORDINATE" && !tapX.isNaN() && !tapY.isNaN() ->
                            clickAtNormalized(tapX, tapY)
                        !targetText.isNullOrBlank() -> clickElementByText(targetText)
                        !tapX.isNaN() && !tapY.isNaN() -> clickAtNormalized(tapX, tapY)
                        else -> ActionResult(false, "INVALID_ARGUMENT", "Target text is missing for CLICK.")
                    }
                }
                "TYPE_TEXT" -> {
                    actionResult = if (!textToType.isNullOrBlank()) typeTextIntoFocusedNode(textToType)
                                 else ActionResult(false, "INVALID_ARGUMENT", "Text to type is missing for TYPE_TEXT.")
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
        } catch (e: Exception) {
            Log.e("AccessibilityAgent", "Error parsing backend directive", e)
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
            val rootNode = window.root ?: continue
            
            // 1. Fast path: Use built-in search
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
            
            // 2. Fallback: Deep recursive search within this window
            val recursiveResult = recursiveClickSearchRich(rootNode, target)
            rootNode.recycle()
            
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
            put("is_clickable", node.isClickable)
            put("is_editable", node.isEditable)
            put("is_visible", node.isVisibleToUser)
            put("is_enabled", node.isEnabled)
            put("is_focused", node.isFocused)
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

        // 4. Final Fallback: Home screen search (Triggered only if direct intent-based launches fail)
        if (!appName.isNullOrBlank()) {
            Log.d("AccessibilityAgent", "Intent-based launch failed. Attempting Home search for '$appName'.")
            
            val taskToFinalize = currentTaskId
            val originalIntentToFinalize = currentOriginalIntent

            serviceScope.launch {
                performGlobalAction(GLOBAL_ACTION_HOME)
                delay(1000) // Debounce for Home transition
                
                val clickResult = clickElementByText(appName)
                
                sendFeedbackAfterAction(
                    result = clickResult,
                    actionType = "OPEN_APP",
                    targetNode = appName,
                    targetPackage = clickResult.packageLaunched ?: targetPackage,
                    immediate = false,
                    debounce = !clickResult.success,
                    capturedTaskId = taskToFinalize,
                    capturedOriginalIntent = originalIntentToFinalize
                )
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

    // 3. Type text into the currently focused or first available editable field
    fun typeTextIntoFocusedNode(textToType: String): ActionResult {
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
                Log.d("AccessibilityAgent", "Successfully injected text. Triggering search IME action...")
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

    private fun triggerSearchAction(node: AccessibilityNodeInfo) {
        // Strategy 1: Look for a search/go/enter button in all windows
        val windows = windows
        for (window in windows) {
            val root = window.root ?: continue
            if (searchForImeButton(root)) {
                root.recycle()
                return
            }
            root.recycle()
        }
        
        // Strategy 2: If no button found, attempt to click the node again to trigger soft keyboard submission
        // (Some apps submit on Enter key press which might be triggered by a click/focus cycle)
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
            if (debounce) {
                Log.d("AccessibilityAgent", "Debouncing feedback payload by 1200ms to allow UI settlement...")
                delay(1200)
            }

            if (!immediate) {
                // Wait for UI to settle: either by event or timeout
                windowChangeDeferred = CompletableDeferred()
                withTimeoutOrNull(2000) {
                    windowChangeDeferred?.await()
                }
                windowChangeDeferred = null
                
                // Requirement: Wait at least 600ms for TYPE_WINDOW_STATE_CHANGED settlement or animations
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
        payload.put("screen_state", elements)
        payload.put("screenElements", elements)
        payload.put("current_package_name", screenState.optString("current_package_name"))
        payload.put("current_app_package", screenState.optString("current_package_name"))
        payload.put("active_input_focused", screenState.optBoolean("active_input_focused"))
        val hint = screenState.optString("focused_hint")
        if (hint.isNotBlank() && hint != "null") {
            payload.put("focused_hint", hint)
        }
        if (elements.length() == 0) {
            val screenshot = captureScreenshotBase64()
            if (!screenshot.isNullOrBlank()) {
                payload.put("screenshot_base64", screenshot)
            }
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

    private fun traverseAndExtract(rootNode: AccessibilityNodeInfo, array: JSONArray) {
        val stack = mutableListOf<AccessibilityNodeInfo>()
        stack.add(AccessibilityNodeInfo.obtain(rootNode))

        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            val nodePackage = node.packageName?.toString() ?: ""

            // 1. SYSTEM UI FILTERING - Skip nodes from SystemUI or overlays
            if (nodePackage.contains("com.android.systemui", ignoreCase = true)) {
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

            val nodeObj = JSONObject().apply {
                put("text", node.text?.toString() ?: "")
                put("content_description", node.contentDescription?.toString() ?: "")
                put("resource_id", node.viewIdResourceName ?: "")
                put("class_name", node.className?.toString() ?: "")
                put("package_name", nodePackage)
                put("is_clickable", node.isClickable)
                put("is_editable", node.isEditable)
                put("is_focused", node.isFocused)
                put("is_scrollable", node.isScrollable)
                put("is_visible", node.isVisibleToUser)
                put("is_enabled", node.isEnabled)
                put("is_checked", node.isChecked)
                put("is_selected", node.isSelected)

                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                // Use standard [left,top][right,bottom] or explicit keys for backend clarity
                put("bounds", JSONObject().apply {
                    put("left", bounds.left)
                    put("top", bounds.top)
                    put("right", bounds.right)
                    put("bottom", bounds.bottom)
                })
            }
            array.put(nodeObj)

            // Add children to stack in reverse order to maintain DFS left-to-right order if needed, 
            // but standard DFS is fine here.
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    stack.add(child)
                }
            }
            node.recycle()
        }
    }
}

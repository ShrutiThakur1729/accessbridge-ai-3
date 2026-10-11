package com.accessbridgeai.utils

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.accessbridgeai.services.AccessibilityAgentService
import org.json.JSONObject

object ExecutionEngine {

    /**
     * Executes the directive action using the provided AccessibilityAgentService.
     * Handles GO_HOME, OPEN_APP, CLICK, TYPE_TEXT, SCROLL, and gestures.
     * 
     * ALL GESTURE EXECUTION MUST USE UNIVERSAL ACCESSIBILITY APIS AND COORDINATES.
     * DO NOT HARDCODE APP-SPECIFIC UI LABELS OR IDS.
     */
    suspend fun executeDirectiveAction(service: AccessibilityAgentService, json: JSONObject): AccessibilityAgentService.ActionResult {
        val actionType = json.optString("action_type")
        val targetText = json.optString("target_text")
        val packageName = json.optString("package_name")
        val textToType = json.optString("text_to_type")
        val scrollDirection = json.optString("scroll_direction", "DOWN")
        val executionMode = json.optString("execution_mode")
        
        // 1. Universal Coordinate Tap Handling
        val tapX = if (json.has("tap_x") && !json.isNull("tap_x")) json.optDouble("tap_x") else Double.NaN
        val tapY = if (json.has("tap_y") && !json.isNull("tap_y")) json.optDouble("tap_y") else Double.NaN

        if (executionMode == "COORDINATE" || (!tapX.isNaN() && !tapY.isNaN())) {
            val point = service.resolveCoordinates(tapX, tapY)
            Log.d("ExecutionEngine", "Executing universal tap at coordinates: (${point.x}, ${point.y})")
            val success = service.tapAtCoordinates(point.x, point.y)
            return AccessibilityAgentService.ActionResult(success, if (success) "SUCCESS" else "GESTURE_FAILED")
        }

        // 2. Universal Action Primitives
        return when (actionType) {
            "SYSTEM_BACK", "GLOBAL_ACTION_BACK" -> {
                val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                AccessibilityAgentService.ActionResult(success, if (success) "SUCCESS" else "GESTURE_FAILED")
            }
            "GO_HOME" -> {
                val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                AccessibilityAgentService.ActionResult(success, if (success) "SUCCESS" else "GESTURE_FAILED")
            }
            "OPEN_APP" -> {
                val effectivePackage = if (!packageName.isNullOrBlank()) packageName else {
                    // Fallback to label-based resolution if packageName is missing from directive
                    if (!targetText.isNullOrBlank()) {
                        val resolution = AppResolver.resolveApp(service, targetText)
                        if (resolution.confidence == AppResolver.Confidence.HIGH_CONFIDENCE || 
                            resolution.confidence == AppResolver.Confidence.MULTI_STEP_INTENT) {
                            resolution.bestMatch?.packageName
                        } else null
                    } else null
                }

                if (!effectivePackage.isNullOrBlank()) {
                    val pm = service.packageManager
                    val launchIntent = pm.getLaunchIntentForPackage(effectivePackage)
                    if (launchIntent != null) {
                        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        service.startActivity(launchIntent)
                        // Post-launch delay window to allow OS window transition
                        kotlinx.coroutines.delay(800)
                        AccessibilityAgentService.ActionResult(true, "SUCCESS")
                    } else {
                        AccessibilityAgentService.ActionResult(false, "NOT_INSTALLED", "App not found or not launchable.")
                    }
                } else {
                    AccessibilityAgentService.ActionResult(false, "INVALID_ARGUMENT", "Package name or app label missing.")
                }
            }
            "CLICK" -> {
                if (!targetText.isNullOrBlank()) {
                    service.clickElementByText(targetText)
                } else {
                    AccessibilityAgentService.ActionResult(false, "INVALID_ARGUMENT", "Target text missing for CLICK.")
                }
            }
            "TYPE_TEXT" -> {
                if (!textToType.isNullOrBlank()) {
                    service.typeTextIntoFocusedNode(textToType)
                } else {
                    AccessibilityAgentService.ActionResult(false, "INVALID_ARGUMENT", "Text to type missing.")
                }
            }
            "SCROLL" -> {
                // Dynamic scroll based on direction primitive
                service.performScroll(scrollDirection)
            }
            "SWIPE_UP" -> {
                val success = service.swipeUpToOpenDrawer()
                AccessibilityAgentService.ActionResult(success, if (success) "SUCCESS" else "GESTURE_FAILED")
            }
            "SWIPE_HORIZONTAL" -> {
                val direction = json.optString("direction", "LEFT")
                val success = service.scrollLauncherPage(direction)
                AccessibilityAgentService.ActionResult(success, if (success) "SUCCESS" else "GESTURE_FAILED")
            }
            "CONFIRM_BIOMETRIC" -> {
                AccessibilityAgentService.ActionResult(true, "SUCCESS")
            }
            "NONE" -> AccessibilityAgentService.ActionResult(true, "SUCCESS")
            else -> {
                Log.w("ExecutionEngine", "Unrecognized action_type: $actionType. Defaulting to success=false.")
                AccessibilityAgentService.ActionResult(false, "UNKNOWN_ACTION", "Action $actionType not supported.")
            }
        }
    }
}

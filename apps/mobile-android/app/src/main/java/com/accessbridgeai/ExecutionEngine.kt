package com.accessbridgeai

import android.accessibilityservice.AccessibilityService
import com.accessbridgeai.services.AccessibilityAgentService
import org.json.JSONObject

object ExecutionEngine {

    /**
     * Executes the directive action using the provided AccessibilityAgentService.
     * Handles GO_HOME, OPEN_APP, CLICK, TYPE_TEXT, SCROLL, and gestures.
     */
    suspend fun executeDirectiveAction(service: AccessibilityAgentService, json: JSONObject): AccessibilityAgentService.ActionResult {
        val actionType = json.optString("action_type")
        val targetText = json.optString("target_text")
        val packageName = json.optString("package_name")
        val textToType = json.optString("text_to_type")
        val scrollDirection = json.optString("scroll_direction")
        val executionMode = json.optString("execution_mode")
        val tapX = if (json.has("tap_x") && !json.isNull("tap_x")) json.optDouble("tap_x") else Double.NaN
        val tapY = if (json.has("tap_y") && !json.isNull("tap_y")) json.optDouble("tap_y") else Double.NaN

        // 1. Coordinate Tap Handling
        if (executionMode == "COORDINATE" || (!tapX.isNaN() && !tapY.isNaN())) {
            val point = service.resolveCoordinates(tapX, tapY)
            val success = service.tapAtCoordinates(point.x, point.y)
            return AccessibilityAgentService.ActionResult(success, if (success) "SUCCESS" else "GESTURE_FAILED")
        }

        // 2. Named Action Handling
        return when (actionType) {
            "OPEN_APP" -> {
                if (!packageName.isNullOrBlank() || !targetText.isNullOrBlank()) {
                    service.openAppByPackage(packageName, targetText)
                } else {
                    AccessibilityAgentService.ActionResult(
                        false,
                        "INVALID_ARGUMENT",
                        "App identifier (package or label) is missing in OPEN_APP directive."
                    )
                }
            }
            "CLICK" -> {
                if (!targetText.isNullOrBlank()) {
                    service.clickElementByText(targetText)
                } else {
                    AccessibilityAgentService.ActionResult(
                        false,
                        "INVALID_ARGUMENT",
                        "Target text or coordinates missing for CLICK."
                    )
                }
            }
            "TYPE_TEXT" -> {
                if (!textToType.isNullOrBlank()) service.typeTextIntoFocusedNode(textToType)
                else AccessibilityAgentService.ActionResult(
                    false,
                    "INVALID_ARGUMENT",
                    "Text to type is missing for TYPE_TEXT."
                )
            }
            "SCROLL" -> {
                service.performScroll(scrollDirection)
            }
            "GO_HOME" -> {
                val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                // Settlement delay is handled in sendFeedbackAfterAction in AccessibilityAgentService
                AccessibilityAgentService.ActionResult(success, if (success) "SUCCESS" else "GESTURE_FAILED")
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
            "SYSTEM_BACK", "GLOBAL_ACTION_BACK" -> {
                val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                AccessibilityAgentService.ActionResult(success, if (success) "SUCCESS" else "GESTURE_FAILED")
            }
            "NONE" -> AccessibilityAgentService.ActionResult(true, "SUCCESS")
            "CONFIRM_BIOMETRIC" -> AccessibilityAgentService.ActionResult(true, "SUCCESS")
            else -> AccessibilityAgentService.ActionResult(false, "UNKNOWN_ACTION", "Action $actionType not recognized.")
        }
    }
}

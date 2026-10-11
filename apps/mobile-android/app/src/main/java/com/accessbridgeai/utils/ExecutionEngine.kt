package com.accessbridgeai.utils

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.accessbridgeai.services.AccessibilityAgentService
import kotlinx.coroutines.delay
import org.json.JSONObject

object ExecutionEngine {

    /**
     * Executes the directive action using the provided AccessibilityAgentService.
     * Handles GO_HOME, OPEN_APP, CLICK, TYPE_TEXT, SUBMIT_SEARCH, SCROLL, CALL_CONTACT, and gestures.
     * 
     * ALL GESTURE EXECUTION MUST USE UNIVERSAL ACCESSIBILITY APIS AND COORDINATES.
     * DO NOT HARDCODE APP-SPECIFIC UI LABELS OR IDS.
     */
    suspend fun executeDirectiveAction(service: AccessibilityAgentService, json: JSONObject): AccessibilityAgentService.ActionResult {
        val actionType = json.optString("action_type")
        val targetText = json.optString("target_text")
        val packageName = json.optString("package_name")
        val textToType = json.optString("text_to_type")
        val inputData = json.optString("input_data")
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
                    val query = when {
                        !textToType.isNullOrBlank() -> textToType
                        !inputData.isNullOrBlank() -> inputData
                        else -> ""
                    }

                    if (query.isNotBlank() && SearchIntentRegistry.hasSearchSupport(effectivePackage)) {
                        var launched: Boolean? = null
                        try {
                            launched = SearchIntentRegistry.launchInAppSearch(service, effectivePackage, query)
                        } catch (e: Exception) {
                            Log.e("ExecutionEngine", "SearchIntentRegistry launch error: ${e.message}")
                        }

                        if (launched == true) {
                            delay(1200)
                            return AccessibilityAgentService.ActionResult(true, "SUCCESS", packageLaunched = effectivePackage)
                        }

                        // Fall back to plain launcher intent if search intent returned null/false or threw exception
                        val launchIntent = pm.getLaunchIntentForPackage(effectivePackage)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            service.startActivity(launchIntent)
                            delay(1200)
                            return AccessibilityAgentService.ActionResult(
                                true,
                                "SEARCH_INTENT_UNAVAILABLE",
                                packageLaunched = effectivePackage
                            )
                        } else {
                            return AccessibilityAgentService.ActionResult(
                                false,
                                "NOT_INSTALLED",
                                "App not found or not launchable."
                            )
                        }
                    }

                    val launchIntent = pm.getLaunchIntentForPackage(effectivePackage)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        service.startActivity(launchIntent)
                        delay(1200)
                        AccessibilityAgentService.ActionResult(true, "SUCCESS", packageLaunched = effectivePackage)
                    } else {
                        AccessibilityAgentService.ActionResult(false, "NOT_INSTALLED", "App not found or not launchable.")
                    }
                } else {
                    AccessibilityAgentService.ActionResult(false, "INVALID_ARGUMENT", "Package name or app label missing.")
                }
            }
            "CALL_CONTACT", "DIAL_CONTACT" -> {
                val query = when {
                    !targetText.isNullOrBlank() -> targetText
                    !inputData.isNullOrBlank() -> inputData
                    !textToType.isNullOrBlank() -> textToType
                    else -> ""
                }
                if (query.isNotBlank()) {
                    if (ContextCompat.checkSelfPermission(
                            service,
                            Manifest.permission.READ_CONTACTS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w("ExecutionEngine", "READ_CONTACTS permission missing")
                        AccessibilityAgentService.ActionResult(
                            false,
                            "PERMISSION_REQUIRED",
                            "Contacts permission required to resolve '$query'."
                        )
                    } else {
                        val matches = ContactResolver.searchContactByName(service, query)
                        when {
                            matches.size == 1 -> {
                                val contact = matches[0]
                                Log.d("ExecutionEngine", "Resolved single contact: ${contact.displayName} -> ${contact.phoneNumber}")
                                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                                    data = Uri.parse("tel:${contact.phoneNumber}")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                try {
                                    service.startActivity(dialIntent)
                                    AccessibilityAgentService.ActionResult(
                                        true,
                                        "DIALER_OPENED",
                                        packageLaunched = "com.android.dialer"
                                    )
                                } catch (e: Exception) {
                                    AccessibilityAgentService.ActionResult(false, "CALL_FAILED", e.message)
                                }
                            }
                            matches.size > 1 -> {
                                val candidateNames = matches.map { "${it.displayName} (${it.phoneNumber})" }
                                Log.d("ExecutionEngine", "Ambiguous contact matches for '$query': $candidateNames")
                                AccessibilityAgentService.ActionResult(
                                    false,
                                    "AMBIGUOUS_CONTACT_MATCH",
                                    failureReason = "Found ${matches.size} contacts for '$query'. Please clarify.",
                                    candidates = candidateNames
                                )
                            }
                            else -> {
                                Log.w("ExecutionEngine", "No contacts matching '$query'")
                                AccessibilityAgentService.ActionResult(
                                    false,
                                    "NO_CONTACT_MATCH",
                                    failureReason = "No contact matching '$query' found in phonebook."
                                )
                            }
                        }
                    }
                } else {
                    AccessibilityAgentService.ActionResult(false, "INVALID_ARGUMENT", "Contact name missing.")
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
                val contentToType = if (!textToType.isNullOrBlank()) textToType else inputData
                if (!contentToType.isNullOrBlank()) {
                    service.typeTextIntoFocusedNode(contentToType)
                } else {
                    AccessibilityAgentService.ActionResult(false, "INVALID_ARGUMENT", "Text to type missing.")
                }
            }
            "SUBMIT_SEARCH" -> {
                val root = service.findActiveRoot()
                if (root == null) {
                    AccessibilityAgentService.ActionResult(false, "NO_ROOT", "Active window root not found.")
                } else {
                    val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    var success = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && focusedNode != null) {
                        success = focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    }

                    if (success) {
                        AccessibilityAgentService.ActionResult(true, "SUCCESS")
                    } else {
                        val clickResult = service.clickElementByText("Search")
                        if (clickResult.success) {
                            clickResult
                        } else {
                            AccessibilityAgentService.ActionResult(
                                false,
                                "IME_ENTER_FAILED",
                                "IME_ENTER action not supported or returned false."
                            )
                        }
                    }
                }
            }
            "SCROLL" -> {
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

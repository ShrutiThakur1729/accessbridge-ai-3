package com.accessbridgeai.data

/**
 * Objective 5: Telemetry
 * Represents a failure or loop event to be persisted in Databricks.
 * Note: timestamp is a String to match the backend's ISO-8601 expectation.
 */
data class TelemetryLog(
    val sessionId: String,
    val targetAppPackage: String,
    val failedAction: String,
    val targetLabel: String? = null,
    val failureReason: String,
    val timestamp: String
)
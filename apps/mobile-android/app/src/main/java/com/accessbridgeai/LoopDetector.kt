package com.accessbridgeai

import android.util.Log
import org.json.JSONArray
import java.util.ArrayDeque

object LoopDetector {

    // Buffer to store the composite tuple: "${packageName}_${domHash}_${actionType}_${targetText}"
    private val loopBuffer = ArrayDeque<String>(6)

    fun calculateDomHash(elements: JSONArray): Int {
        var sum = 0
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            sum += el.optString("resource_id", "").hashCode()
            val bounds = el.optJSONObject("bounds")
            if (bounds != null) {
                sum += bounds.optInt("left", 0) + bounds.optInt("top", 0) +
                       bounds.optInt("right", 0) + bounds.optInt("bottom", 0)
            }
        }
        return sum
    }

    /**
     * Checks if the exact same actionable intent (CLICK or TYPE_TEXT on a valid target) 
     * is executed on the exact same domHash 3 times sequentially.
     */
    fun isLoopDetected(
        packageName: String,
        domHash: Int,
        actionType: String,
        targetText: String,
        elementCount: Int
    ): Boolean {
        // NEVER add rescan_wait, SCROLL, or actions on elementCount == 0 to LoopDetector
        if (elementCount == 0 || actionType == "rescan_wait" || actionType == "SCROLL") {
            return false
        }

        // Only emit LOOP_DETECTED for interactive actionable intents (CLICK or TYPE_TEXT)
        if (actionType != "CLICK" && actionType != "TYPE_TEXT") {
            return false
        }

        val currentKey = "${packageName}_${domHash}_${actionType}_${targetText}"
        
        if (loopBuffer.size < 2) return false
        
        // Check if the exact same actionable intent is executed on the exact same domHash 3 times sequentially.
        // matchCount == 2 means 2 previous matches + current = 3.
        val iterator = loopBuffer.descendingIterator()
        var matchCount = 0
        while (iterator.hasNext() && matchCount < 2) {
            if (iterator.next() == currentKey) {
                matchCount++
            } else {
                break
            }
        }
        
        return matchCount == 2
    }

    fun recordAction(
        packageName: String, 
        domHash: Int, 
        actionType: String, 
        targetText: String, 
        elementCount: Int
    ) {
        // NEVER add rescan_wait, SCROLL, or actions on elementCount == 0 to LoopDetector
        if (elementCount == 0 || actionType == "rescan_wait" || actionType == "SCROLL") {
            return
        }

        // Only track interactive user actions (CLICK, TYPE_TEXT)
        if (actionType != "CLICK" && actionType != "TYPE_TEXT") {
            return
        }

        val currentKey = "${packageName}_${domHash}_${actionType}_${targetText}"
        if (loopBuffer.size >= 6) {
            loopBuffer.removeFirst()
        }
        loopBuffer.addLast(currentKey)
        Log.d("LoopDetector", "Recorded composite action: $currentKey. Buffer size: ${loopBuffer.size}")
    }

    fun clearHistory() {
        loopBuffer.clear()
    }
}

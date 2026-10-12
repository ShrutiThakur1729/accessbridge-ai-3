package com.accessbridgeai.utils

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class ScreenItem(
    val label: String, val id: String?,
    val clickable: Boolean, val editable: Boolean,
    val node: AccessibilityNodeInfo,
    val area: Int = 0
) {
    val isAd: Boolean get() = label.startsWith("sponsored", ignoreCase = true)
}
data class Screen(val items: List<ScreenItem>, val text: String)

object ScreenReader {
    fun ownText(n: AccessibilityNodeInfo): String? =
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun clean(s: String) = s.replace("\uFFFC", "").replace(Regex("\\s+"), " ").trim()

    /** Own text/desc if present; otherwise (clickable only) the first few descendant texts. */
    fun labelOf(n: AccessibilityNodeInfo): String {
        ownText(n)?.let { return clean(it) }
        if (!n.isClickable) return ""
        val parts = ArrayList<String>()
        fun collect(p: AccessibilityNodeInfo) {
            for (i in 0 until p.childCount) {
                if (parts.size >= 4) return
                val c = p.getChild(i) ?: continue
                val t = ownText(c)
                if (t != null) parts += clean(t) else collect(c)
            }
        }
        collect(n)
        return parts.joinToString(", ")
    }

    fun read(root: AccessibilityNodeInfo): Screen {
        val items = ArrayList<ScreenItem>()
        val seen = HashSet<String>()
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo) {
            if (n.isVisibleToUser) {
                ownText(n)?.let { sb.append(clean(it).lowercase()).append('\n') }
                if (n.isClickable || n.isEditable) {
                    val label = labelOf(n)
                    if (label.isNotEmpty()) {
                        val r = Rect(); n.getBoundsInScreen(r)
                        if (seen.add("${n.viewIdResourceName}|$r|$label"))
                           items += ScreenItem(label, n.viewIdResourceName, n.isClickable, n.isEditable, n, r.width() * r.height())
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return Screen(items, sb.toString())
    }
}

object PaymentGate {
    // DELIBERATE HARDCODING EXCEPTION (documented): payment signals, whole-word match on ALL visible text.
    private val WORDS = listOf("pay now", "to pay", "proceed to pay", "place order", "upi", "cvv", "card number", "enter otp")
    fun matched(screenText: String): String? =
        WORDS.firstOrNull { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(screenText) }
    fun isPayment(screenText: String) = matched(screenText) != null
}
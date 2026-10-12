package com.accessbridgeai.agent

import com.accessbridgeai.detect.DetectorConfig
import com.accessbridgeai.detect.ListItemDetector
import com.accessbridgeai.detect.ScreenView
import kotlin.math.abs

object Options {
    private val PRICE = Regex("[\\u20B9\\$\\u20AC\\u00A3]\\s?(\\d[\\d,]*)")
    private val ETA = Regex("(\\d+)\\s*min")
    private val DIGITS = Regex("[0-9:]+")
    private val SPACES = Regex("\\s+")

    /** Confident list items in reading order (top to bottom, then left to right). */
    fun from(view: ScreenView, max: Int = 5): List<Option> =
        ListItemDetector.detect(view).candidates
            .filter { it.score >= DetectorConfig.ACT_SCORE }
            .map { view.nodes[it.nodeId] }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .take(max)
            .map { n ->
                val label = view.label(n)
                Option(n.id, n.top, label,
                    PRICE.find(label)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull(),
                    ETA.find(label)?.groupValues?.get(1)?.toIntOrNull())
            }

    /** Labels without digits: a ride's "2:41 pm" ticks while the user thinks, its name does not. */
    private fun key(label: String) = SPACES.replace(DIGITS.replace(label, ""), " ").trim()

    /** Find the same option again on a fresh screen (node ids go stale). */
    fun refind(view: ScreenView, option: Option): Int? =
        view.nodes.filter { it.clickable && it.enabled && key(view.label(it)) == key(option.label) }
            .minWithOrNull(compareBy({ abs(it.top - option.top) }, { it.depth }))?.id

    fun spoken(label: String, maxChars: Int = 70): String {
        if (label.length <= maxChars) return label
        val cut = label.take(maxChars)
        return cut.substringBeforeLast(' ', cut).trim()
    }
}
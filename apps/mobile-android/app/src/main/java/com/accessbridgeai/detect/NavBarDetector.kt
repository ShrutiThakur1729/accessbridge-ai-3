package com.accessbridgeai.detect

import com.accessbridgeai.screen.RawNode
import kotlin.math.abs
import kotlin.math.roundToInt

object NavBarDetector {
    fun detect(screen: ScreenView): Detection {
        val w = screen.raw.screenWidth.toFloat()
        val step = 0.01f * screen.raw.screenHeight
        val pool = screen.nodes.filter {
            it.clickable && it.enabled && !it.editable &&
                    screen.centerYFraction(it) >= DetectorConfig.BOTTOM_Y &&
                    screen.areaFraction(it) <= DetectorConfig.LIST_MAX_AREA
        }
        val poolIds = pool.map { it.id }.toSet()
        val outer = pool.filterNot { screen.hasAncestorIn(it, poolIds) }
        // Group by row FIRST, so unrelated nodes can't interrupt a chain.
        val rows = outer.groupBy { Pair((it.top / step).roundToInt(), (it.bottom / step).roundToInt()) }.values

        val found = ArrayList<Candidate>()
        for (row in rows) {
            var chain = ArrayList<RawNode>()
            fun flush() {
                if (chain.size >= 3) {
                    val span = (chain.last().right - chain.first().left) / w
                    if (span >= DetectorConfig.NAV_MIN_SPAN)
                        for (n in chain) found += Candidate(Role.NAV_BAR, n.id, 0.9f, listOf("row:${chain.size}"))
                }
                chain = ArrayList()
            }
            for (n in row.sortedBy { it.left }) {
                val last = chain.lastOrNull()
                val joins = last != null &&
                        abs(n.left - last.right) <= 0.01f * w &&     // touching, no gap
                        abs(n.width - last.width) <= 0.02f * w       // same size
                if (!joins) flush()
                chain.add(n)
            }
            flush()
        }
        return Detection(Role.NAV_BAR, found.sortedBy { it.nodeId })
    }
}
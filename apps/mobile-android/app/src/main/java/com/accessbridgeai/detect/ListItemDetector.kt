package com.accessbridgeai.detect

import kotlin.math.abs

object ListItemDetector {
    private const val MIN_AREA = DetectorConfig.LIST_MIN_AREA
    private const val MAX_AREA = DetectorConfig.LIST_MAX_AREA
    private const val WIDTH_TOLERANCE = 0.05f   // as a fraction of screen width

    fun detect(screen: ScreenView): Detection {
        val base = screen.nodes.filter {
            it.clickable && it.enabled && !it.editable && it.id !in screen.navBarIds &&
                    screen.areaFraction(it) in MIN_AREA..MAX_AREA && screen.label(it).isNotBlank()
        }
        val ids = base.map { it.id }.toSet()
        val items = base.filterNot { screen.hasAncestorIn(it, ids) }   // outermost only

        val candidates = items.map { n ->
            val peers = items.count { o ->
                o.id != n.id && abs(screen.widthFraction(o) - screen.widthFraction(n)) <= WIDTH_TOLERANCE
            }
            val score = 0.3f + 0.3f * minOf(peers, 2)
            Candidate(Role.LIST_ITEM, n.id, score, listOf("clickable", "big", "peers:$peers"))
        }.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { screen.nodes[it.nodeId].top })
        return Detection(Role.LIST_ITEM, candidates)
    }
}
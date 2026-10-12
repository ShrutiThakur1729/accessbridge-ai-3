package com.accessbridgeai.detect

object AddDetector {
    private const val MAX_AREA = DetectorConfig.LIST_MIN_AREA   // "small" = smaller than a list item

    fun detect(screen: ScreenView): Detection {
        val items = ListItemDetector.detect(screen).candidates
            .filter { it.score >= DetectorConfig.ACT_SCORE }.map { it.nodeId }.toSet()
        val priced = PricedItemDetector.detect(screen).candidates.map { it.nodeId }.toSet()
        val candidates = screen.nodes
            .filter { it.clickable && it.enabled && !it.editable &&
                    screen.areaFraction(it) < MAX_AREA && screen.hasAncestorIn(it, items) }
            .map { n ->
                var score = 0.3f
                val reasons = mutableListOf("small_in_item")
                if (screen.hasAncestorIn(n, priced)) { score += 0.2f; reasons += "in_priced_item" }
                if (screen.hasWord(screen.label(n), DetectorConfig.ADD_WORDS)) { score += 0.4f; reasons += "add_word" }
                Candidate(Role.ADD, n.id, score, reasons)
            }.sortedByDescending { it.score }
        return Detection(Role.ADD, candidates)
    }
}
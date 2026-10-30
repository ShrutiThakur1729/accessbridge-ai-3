package com.accessbridgeai.detect

object EntryOpenerDetector {
    private const val MAX_AREA = 0.25f

    fun detect(screen: ScreenView): Detection {
        val candidates = screen.nodes
            .filter { it.clickable && it.enabled && !it.editable &&
                    screen.areaFraction(it) <= MAX_AREA && it.id !in screen.navBarIds }
            .map { n ->
                var score = 0.25f
                val reasons = mutableListOf("clickable")
                val label = screen.label(n)
                if (DetectorConfig.ENTRY_WORDS.any { w -> w in label }) { score += 0.45f; reasons += "entry_word" }
                if (screen.centerYFraction(n) < 0.6f) { score += 0.1f; reasons += "upper_area" }
                Candidate(Role.ENTRY_OPENER, n.id, score, reasons)
            }
            .sortedByDescending { it.score }
        return Detection(Role.ENTRY_OPENER, candidates)
    }
}
package com.accessbridgeai.detect

object CommitDetector {
    private const val MAX_AREA = 0.10f

    fun detect(screen: ScreenView): Detection {
        val items = ListItemDetector.detect(screen).candidates
            .filter { it.score >= DetectorConfig.ACT_SCORE }.map { it.nodeId }.toSet()
        val candidates = screen.nodes
            .filter { n ->
                n.clickable && n.enabled && !n.editable &&
                        screen.centerYFraction(n) >= DetectorConfig.BOTTOM_Y &&
                        screen.widthFraction(n) >= DetectorConfig.COMMIT_MIN_WIDTH &&
                        screen.areaFraction(n) <= MAX_AREA &&
                        n.id !in screen.navBarIds && n.id !in items
            }
            .map { n ->
                var score = 0.45f                     // wide button at the bottom: plausible, not confident
                val reasons = mutableListOf("bottom_wide")
                if (screen.hasWord(screen.label(n), DetectorConfig.COMMIT_WORDS)) { score += 0.45f; reasons += "commit_word" }
                Candidate(Role.COMMIT, n.id, score, reasons)
            }.sortedByDescending { it.score }
        return Detection(Role.COMMIT, candidates)
    }
}
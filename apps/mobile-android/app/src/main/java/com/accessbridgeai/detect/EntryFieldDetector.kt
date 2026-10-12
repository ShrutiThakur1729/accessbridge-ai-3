package com.accessbridgeai.detect

object EntryFieldDetector {
    fun detect(screen: ScreenView): Detection {
        val candidates = screen.nodes
            .filter { it.editable && it.enabled && !it.password }   // safety rule, not a score
            .map { n ->
                var score = 0.5f
                val reasons = mutableListOf("editable")
                if (n.focused) { score += 0.4f; reasons += "focused" }
                if (screen.widthFraction(n) >= 0.4f) { score += 0.1f; reasons += "wide" }
                Candidate(Role.ENTRY_FIELD, n.id, score, reasons)
            }
            .sortedByDescending { it.score }
        return Detection(Role.ENTRY_FIELD, candidates)
    }
}
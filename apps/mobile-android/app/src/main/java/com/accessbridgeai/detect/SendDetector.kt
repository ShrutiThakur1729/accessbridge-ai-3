package com.accessbridgeai.detect

object SendDetector {
    /** typed = the text the agent just put in the field. No typed text means no send. */
    fun detect(before: ScreenView, after: ScreenView, typed: String?): Detection {
        if (typed.isNullOrBlank()) return Detection(Role.SEND, emptyList())
        val field = after.nodes.firstOrNull {
            it.editable && it.focused && it.enabled && !it.password &&
                    after.label(it).contains(typed.trim().lowercase())
        } ?: return Detection(Role.SEND, emptyList())

        val tol = 0.02f * after.raw.screenWidth
        val candidates = ScreenDiffer.diff(before, after).appeared
            .filter { it.clickable && it.enabled && !it.editable && after.areaFraction(it) <= 0.10f }
            .map { n ->
                var score = 0.3f
                val reasons = mutableListOf("appeared")
                val centerY = (n.top + n.bottom) / 2
                if (centerY in field.top..field.bottom && n.left >= field.right - tol) {
                    score += 0.5f; reasons += "right_of_field_same_row"
                }
                if (after.hasWord(after.label(n), DetectorConfig.SEND_WORDS)) { score += 0.2f; reasons += "send_word" }
                Candidate(Role.SEND, n.id, score, reasons)
            }.sortedByDescending { it.score }
        return Detection(Role.SEND, candidates)
    }
}
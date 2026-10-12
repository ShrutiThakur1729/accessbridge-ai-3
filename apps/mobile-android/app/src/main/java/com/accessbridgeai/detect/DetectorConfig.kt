package com.accessbridgeai.detect

/** Everything you are likely to tune. Detectors read from here. */
object DetectorConfig {
    const val ACT_SCORE = 0.75f      // ladder rung 1: confident
    const val ACT_MARGIN = 0.2f      // ladder rung 1: clear winner

    // Word bonuses. A word alone must never reach ACT_SCORE (InvariantsTest checks this).
    val ENTRY_WORDS = listOf("search")
    val ADD_WORDS = listOf("add")
    val COMMIT_WORDS = listOf("book", "confirm", "pay", "order", "checkout", "proceed", "place")
    val SEND_WORDS = listOf("send")

    // Geometry as fractions of the screen
    const val LIST_MIN_AREA = 0.04f
    const val LIST_MAX_AREA = 0.25f
    const val BOTTOM_Y = 0.8f
    const val NAV_MIN_SPAN = 0.6f
    const val COMMIT_MIN_WIDTH = 0.35f
}
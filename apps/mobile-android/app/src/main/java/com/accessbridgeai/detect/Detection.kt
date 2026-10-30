package com.accessbridgeai.detect

enum class Role { ENTRY_FIELD, ENTRY_OPENER, LIST_ITEM, PRICED_ITEM, COMMIT, SEND, ADD, NAV_BAR }

data class Candidate(val role: Role, val nodeId: Int, val score: Float, val reasons: List<String>)

data class Detection(val role: Role, val candidates: List<Candidate>) {   // sorted high to low
    val top: Candidate? get() = candidates.firstOrNull()
    val margin: Float get() = (candidates.getOrNull(0)?.score ?: 0f) - (candidates.getOrNull(1)?.score ?: 0f)
}
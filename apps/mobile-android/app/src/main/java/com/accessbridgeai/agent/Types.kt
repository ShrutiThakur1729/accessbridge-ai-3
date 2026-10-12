package com.accessbridgeai.agent

import com.accessbridgeai.detect.Candidate
import com.accessbridgeai.detect.Role

/** Safety rules attach to the class of an action, not to an app. */
enum class ActionClass { READ, NAVIGATE, INPUT, COMMIT, PAYMENT }

/** Which ladder rung decided. MODEL stays unused until Phase F. */
enum class Rung { CONFIDENT, MODEL, ASK }

/** taskId = one voice command; stepId = one primitive (OPEN_ENTRY, PICK, ...). */
data class Ids(val sessionId: String, val taskId: String, val stepId: Int)

data class Decision(val ids: Ids, val role: Role, val rung: Rung, val chosen: Candidate?, val reason: String)
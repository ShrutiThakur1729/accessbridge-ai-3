package com.accessbridgeai.agent

import com.accessbridgeai.screen.RawScreen

enum class Status { DONE, PAYMENT_STOP, HANDOVER, LOST_APP }

/** One thing the user can choose between (a video, a product, a ride, a contact). */
data class Option(val nodeId: Int, val top: Int, val label: String, val price: Int?, val etaMin: Int?)

/** How a primitive ended, every decision it made, and what to tell the user. */
data class PrimitiveResult(
    val status: Status,
    val decisions: List<Decision>,
    val say: String? = null,
    val screen: RawScreen? = null,   // last screen seen; ids in `picked` refer to it
    val picked: Option? = null       // PICK only
)
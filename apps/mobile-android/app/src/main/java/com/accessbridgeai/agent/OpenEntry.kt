package com.accessbridgeai.agent

import com.accessbridgeai.detect.EntryFieldDetector
import com.accessbridgeai.detect.EntryOpenerDetector
import com.accessbridgeai.detect.ScreenDiffer
import com.accessbridgeai.detect.ScreenView
import com.accessbridgeai.screen.RawScreen
import com.accessbridgeai.utils.PaymentGate

/** OPEN_ENTRY: get text into an entry field. Only detectors and the ladder decide; no app names, ids or strings. */
class OpenEntry(private val device: Device) {
    companion object { const val MAX_TAPS = 3 }

    suspend fun run(ids: Ids, text: String, submit: Boolean = true): PrimitiveResult {
        val decisions = mutableListOf<Decision>()
        var screen = device.read() ?: return lost(decisions)
        var taps = 0
        while (true) {
            val view = ScreenView(screen)
            if (PaymentGate.isPayment(view.allText()))
                return PrimitiveResult(Status.PAYMENT_STOP, decisions, "This looks like a payment screen. I'm stopping here.", screen)

            // 1. Is a field already open?
            val field = Ladder.decide(ids, EntryFieldDetector.detect(view)).also { decisions += it }
            if (field.rung == Rung.CONFIDENT) return fill(decisions, screen, field.chosen!!.nodeId, text, submit)

            // 2. Otherwise open one, but only if sure
            val opener = Ladder.decide(ids, EntryOpenerDetector.detect(view)).also { decisions += it }
            if (opener.rung != Rung.CONFIDENT)
                return handover(decisions, screen, "I can't tell which button opens the search box. Please tap it yourself.")
            if (taps >= MAX_TAPS)
                return handover(decisions, screen, "I tried $MAX_TAPS times but couldn't open the search box.")
            if (!device.tap(opener.chosen!!.nodeId))
                return handover(decisions, screen, "I couldn't tap the search button.")
            taps++

            // 3. Verify by effect: the screen must change
            val after = device.settle(screen) ?: return lost(decisions)
            val diff = ScreenDiffer.diff(view, ScreenView(after))
            if (diff.appeared.isEmpty() && diff.disappeared.isEmpty())
                return handover(decisions, after, "I tapped it but nothing happened.")
            screen = after
        }
    }

    private suspend fun fill(decisions: List<Decision>, screen: RawScreen, fieldId: Int, text: String, submit: Boolean): PrimitiveResult {
        if (!device.type(fieldId, text)) return handover(decisions, screen, "I couldn't type into the search box.")
        val typed = device.read() ?: return lost(decisions)
        val tv = ScreenView(typed)
        val wanted = text.trim().lowercase()
        val field = tv.nodes.firstOrNull { it.editable && tv.label(it).contains(wanted) }
            ?: return handover(decisions, typed, "I typed it, but the text didn't appear.")
        if (!submit) return PrimitiveResult(Status.DONE, decisions, null, typed)
        if (!device.submit(field.id)) return handover(decisions, typed, "I typed it but couldn't press search.")
        val results = device.settle(typed) ?: return lost(decisions)
        return PrimitiveResult(Status.DONE, decisions, null, results)
    }

    private fun lost(d: List<Decision>) = PrimitiveResult(Status.LOST_APP, d, "I lost the app, so I'm stopping.")
    private fun handover(d: List<Decision>, s: RawScreen, say: String) = PrimitiveResult(Status.HANDOVER, d, say, s)
}
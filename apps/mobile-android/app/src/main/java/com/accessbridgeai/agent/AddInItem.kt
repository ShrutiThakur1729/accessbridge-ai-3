package com.accessbridgeai.agent

import com.accessbridgeai.detect.AddDetector
import com.accessbridgeai.detect.Detection
import com.accessbridgeai.detect.Role
import com.accessbridgeai.detect.ScreenDiffer
import com.accessbridgeai.detect.ScreenView
import com.accessbridgeai.utils.PaymentGate

/** ADD, but only INSIDE the chosen item: a results page can show many Add buttons. */
class AddInItem(private val device: Device) {
    suspend fun run(ids: Ids, item: Option): PrimitiveResult {
        val decisions = mutableListOf<Decision>()
        val screen = device.read() ?: return PrimitiveResult(Status.LOST_APP, decisions, "I lost the app, so I'm stopping.")
        val view = ScreenView(screen)
        if (PaymentGate.isPayment(view.allText()))
            return PrimitiveResult(Status.PAYMENT_STOP, decisions, "This looks like a payment screen. I'm stopping here.", screen)
        val itemId = Options.refind(view, item)
            ?: return PrimitiveResult(Status.HANDOVER, decisions, "That item is no longer on the screen.", screen)

        val inside = AddDetector.detect(view).candidates.filter { view.hasAncestorIn(view.nodes[it.nodeId], setOf(itemId)) }
        val decision = Ladder.decide(ids, Detection(Role.ADD, inside)).also { decisions += it }
        if (decision.rung != Rung.CONFIDENT)
            return PrimitiveResult(Status.HANDOVER, decisions, "I couldn't find an add button on that item.", screen)
        if (!device.tap(decision.chosen!!.nodeId))
            return PrimitiveResult(Status.HANDOVER, decisions, "I couldn't tap the add button.", screen)

        val after = device.settle(screen) ?: return PrimitiveResult(Status.LOST_APP, decisions, "I lost the app, so I'm stopping.")
        val diff = ScreenDiffer.diff(view, ScreenView(after))
        if (diff.appeared.isEmpty() && diff.disappeared.isEmpty())
            return PrimitiveResult(Status.HANDOVER, decisions, "I tapped add but nothing changed.", after)
        return PrimitiveResult(Status.DONE, decisions, null, after)
    }
}
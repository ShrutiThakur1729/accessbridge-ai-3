package com.accessbridgeai.agent

import com.accessbridgeai.detect.Candidate
import com.accessbridgeai.detect.ListItemDetector
import com.accessbridgeai.detect.Role
import com.accessbridgeai.detect.ScreenView
import com.accessbridgeai.utils.PaymentGate

/** PICK: read the options, understand the answer, return the chosen option. It does not tap. */
class Pick(private val device: Device, private val user: User) {
    suspend fun run(ids: Ids, intro: String): PrimitiveResult {
        val decisions = mutableListOf<Decision>()
        val screen = device.read() ?: return PrimitiveResult(Status.LOST_APP, decisions, "I lost the app, so I'm stopping.")
        val view = ScreenView(screen)
        if (PaymentGate.isPayment(view.allText()))
            return PrimitiveResult(Status.PAYMENT_STOP, decisions, "This looks like a payment screen. I'm stopping here.", screen)

        val options = Options.from(view)
        if (options.isEmpty())
            return PrimitiveResult(Status.HANDOVER, decisions, "I couldn't find any options on this screen.", screen)

        val items = ListItemDetector.detect(view).candidates
        fun record(o: Option, rung: Rung, why: String) {
            val c = items.firstOrNull { it.nodeId == o.nodeId } ?: Candidate(Role.LIST_ITEM, o.nodeId, 0f, emptyList())
            decisions += Decision(ids, Role.LIST_ITEM, rung, c, why)
        }

        var chosen: Option? = null
        if (options.size == 1) {
            chosen = options[0]
            record(chosen, Rung.CONFIDENT, "only one option")
            user.say("$intro ${Options.spoken(chosen.label)}.")
        } else {
            val list = options.mapIndexed { i, o -> "${i + 1}. ${Options.spoken(o.label)}" }.joinToString(". ")
            var question = "$intro $list. Which one?"
            for (attempt in 1..2) {
                val reply = user.ask(question)
                    ?: return PrimitiveResult(Status.HANDOVER, decisions, "I didn't hear an answer. Stopping.", screen)
                val idx = Resolver.resolve(reply, options)
                if (idx != null) { chosen = options[idx]; record(chosen, Rung.ASK, "user said '$reply'"); break }
                question = "Sorry, I couldn't tell which one. Please say first, second, or the name."
            }
        }
        val picked = chosen ?: return PrimitiveResult(Status.HANDOVER, decisions, "I still couldn't tell which one. Stopping.", screen)

        // The user took time to answer; re-read and find the same option again
        val fresh = device.read() ?: return PrimitiveResult(Status.LOST_APP, decisions, "I lost the app, so I'm stopping.")
        val freshId = Options.refind(ScreenView(fresh), picked)
            ?: return PrimitiveResult(Status.HANDOVER, decisions, "That option is no longer on the screen.", fresh)
        return PrimitiveResult(Status.DONE, decisions, null, fresh, picked.copy(nodeId = freshId))
    }
}
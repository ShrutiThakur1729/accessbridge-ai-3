package com.accessbridgeai.agent

import com.accessbridgeai.detect.ScreenView
import com.accessbridgeai.screen.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PickTest {
    private val ids = Ids("s", "t", 2)

    private fun optionsOf(name: String) = Options.from(ScreenView(Fixtures.load(name).screen))

    private fun pick(screen: String, vararg replies: String?): Triple<PrimitiveResult, ScriptedUser, ScriptedDevice> {
        val dev = ScriptedDevice(listOf(Fixtures.load(screen).screen))
        val user = ScriptedUser(replies.toList())
        return Triple(runBlocking { Pick(dev, user).run(ids, "I found these.") }, user, dev)
    }

    @Test fun zeptoOptionsAreInReadingOrder() {
        assertEquals(listOf(34, 62, 91, 122, 150), optionsOf("zepto_03_results").map { it.nodeId })
    }
    @Test fun ordinalPicksTheThirdProduct() {
        val (r, _, dev) = pick("zepto_03_results", "the third one")
        assertEquals(Status.DONE, r.status)
        assertEquals(optionsOf("zepto_03_results")[2].nodeId, r.picked?.nodeId)
        assertTrue(dev.actions.isEmpty())                       // PICK never taps
    }
    @Test fun aWordFromTheLabelPicksTheProduct() {
        assertEquals(62, pick("zepto_03_results", "the taaza one").first.picked?.nodeId)
    }
    @Test fun anAmbiguousReplyIsAskedAgain() {
        // On this ride screen several options tie on price, so "cheapest" must not guess.
        val (r, user, _) = pick("ola_03_result", "cheapest", "the third one")
        assertEquals(2, user.asked.size)
        assertEquals(Status.DONE, r.status)
        assertEquals(optionsOf("ola_03_result")[2].nodeId, r.picked?.nodeId)
    }
    @Test fun silenceHandsOver() {
        val (r, _, _) = pick("ola_03_result", null)
        assertEquals(Status.HANDOVER, r.status); assertNull(r.picked)
    }
    @Test fun paymentScreensNeverAsk() {
        val (r, user, _) = pick("zepto_04_cart", "first")
        assertEquals(Status.PAYMENT_STOP, r.status); assertTrue(user.asked.isEmpty())
    }
    @Test fun noListMeansHandover() {
        assertEquals(Status.HANDOVER, pick("youtube_02_search_open", "first").first.status)
    }
}
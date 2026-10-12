package com.accessbridgeai.agent

import com.accessbridgeai.detect.ScreenView
import com.accessbridgeai.screen.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AddInItemTest {
    private val ids = Ids("s", "t", 3)
    private val results = Fixtures.load("zepto_03_results").screen
    private val options = Options.from(ScreenView(results), max = 6)

    private fun add(card: Int): Pair<PrimitiveResult, ScriptedDevice> {
        val dev = ScriptedDevice(listOf(results, Fixtures.load("zepto_04_cart").screen))   // any different screen works as "after"
        return runBlocking { AddInItem(dev).run(ids, options.first { it.nodeId == card }) } to dev
    }

    @Test fun eachCardsOwnAddButtonIsTapped() {
        for ((card, button) in listOf(34 to 48, 91 to 104, 150 to 162, 171 to 179)) {
            val (r, dev) = add(card)
            assertEquals("card $card", Status.DONE, r.status)
            assertEquals("card $card", listOf("tap $button"), dev.actions)
        }
    }
    @Test fun aCardWithoutAnAddButtonIsLeftAlone() {
        val (r, dev) = add(62)                                  // this card already shows a quantity stepper
        assertEquals(Status.HANDOVER, r.status); assertTrue(dev.actions.isEmpty())
    }
}
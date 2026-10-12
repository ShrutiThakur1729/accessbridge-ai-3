package com.accessbridgeai.detect

import com.accessbridgeai.screen.Fixtures
import org.junit.Assert.*
import org.junit.Test

class RoleDetectorsTest {
    private fun screen(name: String) = ScreenView(Fixtures.load(name).screen)
    private fun confident(d: Detection) =
        d.candidates.filter { it.score >= DetectorConfig.ACT_SCORE }.map { it.nodeId }.toSet()
    private fun cases(role: String) = Expectations.load().filter { it.value.containsKey(role) }

    @Test fun navBarsAreFoundAndLookalikesAreNot() {
        val c = cases("NAV_BAR"); assertTrue(c.isNotEmpty())
        for ((name, roles) in c) {
            val found = confident(NavBarDetector.detect(screen(name)))
            assertTrue("$name: expected ${roles["NAV_BAR"]}, found $found", found.containsAll(roles["NAV_BAR"]!!))
            val banned = roles["NOT_NAV_BAR"] ?: emptyList()
            assertTrue("$name: $banned must not be nav items, found $found", banned.none { it in found })
        }
    }

    @Test fun addButtonsMatchExactly() {
        val c = cases("ADD"); assertTrue(c.isNotEmpty())
        for ((name, roles) in c) {
            val found = confident(AddDetector.detect(screen(name)))
            assertEquals("$name: ADD", roles["ADD"]!!.toSet(), found)
        }
    }

    @Test fun commitButtonsAreFoundAndNothingElseIsConfident() {
        val c = cases("COMMIT"); assertTrue(c.isNotEmpty())
        for ((name, roles) in c) {
            val ids = roles["COMMIT"]!!
            val found = confident(CommitDetector.detect(screen(name)))
            assertTrue("$name: confident $found must all be in $ids", found.all { it in ids })
            if (ids.isNotEmpty()) assertTrue("$name: nothing confident, expected one of $ids", found.isNotEmpty())
        }
    }

    // after-screen -> (before-screen, text that was typed). 'hrllo' is what was typed when the fixture was captured.
    private val sendPairs = mapOf("whatsapp_04_chat_open_typed" to Pair("whatsapp_03_chat_open_Empty", "hrllo"))

    @Test fun sendIsFoundAfterTyping() {
        for ((afterName, roles) in cases("SEND")) {
            val (beforeName, typed) = sendPairs.getValue(afterName)
            val d = SendDetector.detect(screen(beforeName), screen(afterName), typed)
            assertTrue("$afterName: picked ${d.top?.nodeId} ${d.top?.reasons}", d.top?.nodeId in roles["SEND"]!!)
            assertTrue("margin ${d.margin}", d.top!!.score >= DetectorConfig.ACT_SCORE && d.margin >= DetectorConfig.ACT_MARGIN)
        }
    }

    @Test fun noSendWithoutTypedTextInTheField() {
        val before = screen("whatsapp_03_chat_open_Empty"); val after = screen("whatsapp_04_chat_open_typed")
        assertTrue(SendDetector.detect(before, after, null).candidates.isEmpty())
        assertTrue(SendDetector.detect(before, after, "zzz").candidates.isEmpty())
        // The YouTube mic appears exactly where Send would: it must not count.
        assertTrue(SendDetector.detect(screen("youtube_01_home"), screen("youtube_02_search_open"), "cat videos").candidates.isEmpty())
    }
}
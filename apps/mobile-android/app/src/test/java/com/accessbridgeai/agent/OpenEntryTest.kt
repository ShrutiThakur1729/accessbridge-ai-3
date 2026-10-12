package com.accessbridgeai.agent

import com.accessbridgeai.detect.Role
import com.accessbridgeai.screen.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OpenEntryTest {
    private val ids = Ids("s", "t", 1)

    private fun open(vararg names: String, text: String = "milk", submit: Boolean = false): Pair<PrimitiveResult, ScriptedDevice> {
        val dev = ScriptedDevice(names.map { Fixtures.load(it).screen })
        return runBlocking { OpenEntry(dev).run(ids, text, submit) } to dev
    }

    @Test fun youtubeOpensTypesAndSubmits() {
        val (r, dev) = open("youtube_01_home", "youtube_02_search_open", "youtube_03_results", text = "cat videos", submit = true)
        assertEquals(Status.DONE, r.status)
        assertEquals(listOf("tap 27", "type 22 'cat videos'", "submit 22"), dev.actions)
        assertEquals(Fixtures.load("youtube_03_results").screen, r.screen)
        assertEquals(listOf(Role.ENTRY_FIELD to Rung.ASK, Role.ENTRY_OPENER to Rung.CONFIDENT, Role.ENTRY_FIELD to Rung.CONFIDENT),
            r.decisions.map { it.role to it.rung })
    }

    @Test fun otherAppsUseTheSameCode() {
        assertEquals(listOf("tap 18", "type 20 'siddhi'"), open("whatsapp_01_home", "whatsapp_02_search", text = "siddhi").second.actions)
        assertEquals(listOf("tap 180", "type 104 'milk'"), open("zepto_01_home", "zepto_02_search_open").second.actions)
        assertEquals(listOf("tap 70", "type 49 'thane'"), open("ola_01_home", "ola_02_destination_entry", text = "thane").second.actions)
    }

    @Test fun anAlreadyOpenFieldIsUsedWithoutTappingAnything() {
        assertEquals(listOf("type 22 'cat videos'"), open("youtube_02_search_open", text = "cat videos").second.actions)
    }

    @Test fun paymentScreensStopBeforeAnyAction() {
        val (r, dev) = open("zepto_04_cart")
        assertEquals(Status.PAYMENT_STOP, r.status); assertTrue(dev.actions.isEmpty())
    }

    @Test fun unsureMeansNoTapAtAll() {
        val (r, dev) = open("ola_03_result")
        assertEquals(Status.HANDOVER, r.status); assertTrue(dev.actions.isEmpty())
    }

    @Test fun aTapThatChangesNothingStopsInsteadOfRepeating() {
        val (r, dev) = open("youtube_01_home")        // only one screen: the tap changes nothing
        assertEquals(Status.HANDOVER, r.status)
        assertEquals(listOf("tap 27"), dev.actions)
        assertTrue(r.say!!.contains("nothing happened"))
    }
}
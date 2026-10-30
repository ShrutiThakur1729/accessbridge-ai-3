package com.accessbridgeai.agent

import com.accessbridgeai.screen.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScriptedDeviceTest {
    @Test fun tapAdvancesAndTypeEditsTheCurrentScreen() {
        val home = Fixtures.load("youtube_01_home").screen
        val open = Fixtures.load("youtube_02_search_open").screen
        val dev = ScriptedDevice(listOf(home, open))
        runBlocking {
            assertTrue(dev.tap(27))
            assertEquals(open, dev.read())
            assertTrue(dev.type(22, "cat videos"))
            assertEquals("cat videos", dev.read()!!.nodes[22].text)
            assertFalse(dev.tap(9999))
        }
        assertEquals(listOf("tap 27", "type 22 'cat videos'", "tap 9999 (rejected)"), dev.actions)
    }
}
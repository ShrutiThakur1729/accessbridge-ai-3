package com.accessbridgeai.detect

import com.accessbridgeai.screen.Fixtures
import org.junit.Assert
import org.junit.Test
import kotlin.collections.iterator

class EntryOpenerDetectorTest {
    @Test
    fun picksTheExpectedOpenerOnEveryLabelledFixture() {
        var checked = 0
        for ((name, roles) in Expectations.load()) {
            val ids = roles["ENTRY_OPENER"] ?: continue
            val d = EntryOpenerDetector.detect(ScreenView(Fixtures.load(name).screen))
            val top = d.top
            Assert.assertNotNull("$name: no candidate", top)
            Assert.assertTrue(
                "$name: picked ${top!!.nodeId}, expected $ids, reasons ${top.reasons}",
                top.nodeId in ids
            )
            Assert.assertTrue("$name: margin ${d.margin} too small", d.margin >= 0.2f)
            checked++
        }
        Assert.assertTrue("no ENTRY_OPENER expectations found", checked > 0)
    }
}
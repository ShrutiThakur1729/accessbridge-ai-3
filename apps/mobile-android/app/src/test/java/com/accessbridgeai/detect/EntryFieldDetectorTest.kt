package com.accessbridgeai.detect

import com.accessbridgeai.screen.Fixtures
import com.accessbridgeai.screen.RawNode
import com.accessbridgeai.screen.RawScreen
import org.junit.Assert.*
import org.junit.Test

class EntryFieldDetectorTest {
    @Test fun picksTheExpectedFieldOnEveryLabelledFixture() {
        var checked = 0
        for ((name, roles) in Expectations.load()) {
            val ids = roles["ENTRY_FIELD"] ?: continue
            val d = EntryFieldDetector.detect(ScreenView(Fixtures.load(name).screen))
            val top = d.top
            assertNotNull("$name: no candidate", top)
            assertTrue("$name: picked ${top!!.nodeId}, expected $ids, reasons ${top.reasons}", top.nodeId in ids)
            assertTrue("$name: margin ${d.margin} too small", d.margin >= 0.2f)
            checked++
        }
        assertTrue("no ENTRY_FIELD expectations found", checked > 0)
    }

    @Test fun passwordFieldIsNeverACandidate() {
        val pwd = RawNode(0, null, 0, "android.widget.EditText", "", "",
            clickable = true, editable = true, focused = true, enabled = true,
            password = true, scrollable = false, left = 0, top = 0, right = 900, bottom = 100)
        val d = EntryFieldDetector.detect(ScreenView(RawScreen(1080, 2340, listOf(pwd))))
        assertTrue(d.candidates.isEmpty())
    }
}
package com.accessbridgeai.detect

import com.accessbridgeai.screen.RawNode
import com.accessbridgeai.screen.RawScreen
import org.junit.Assert.*
import org.junit.Test

class ScreenDifferTest {
    private fun n(id: Int, desc: String, top: Int = 0) =
        RawNode(id, null, 0, "android.view.View", "", desc, clickable = true, editable = false,
            focused = false, enabled = true, password = false, scrollable = false,
            left = 0, top = top, right = 10, bottom = top + 10)
    private fun view(vararg nodes: RawNode) = ScreenView(RawScreen(1080, 2340, nodes.toList()))

    @Test fun newNodeAppearsEvenWhenLayoutShifts() {
        val before = view(n(0, "attach", 2000), n(1, "mic", 2000))
        val after = view(n(0, "attach", 1100), n(1, "send", 1100), n(2, "mic", 1100))
        val d = ScreenDiffer.diff(before, after)
        assertEquals(listOf("send"), d.appeared.map { it.desc })
        assertTrue(d.disappeared.isEmpty())
    }

    @Test fun repeatedIdenticalNodesAreCountedNotCollapsed() {
        val d = ScreenDiffer.diff(view(n(0, "x"), n(1, "x")), view(n(0, "x"), n(1, "x"), n(2, "x")))
        assertEquals(1, d.appeared.size)
    }
}
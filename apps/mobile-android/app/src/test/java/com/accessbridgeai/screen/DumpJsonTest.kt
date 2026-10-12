package com.accessbridgeai.screen

import org.junit.Assert.*
import org.junit.Test

class DumpJsonTest {
    private fun node(id: Int, parent: Int?, text: String = "", clickable: Boolean = false) =
        RawNode(id = id, parentId = parent, depth = 0, className = "android.widget.TextView",
            text = text, desc = "", clickable = clickable, editable = false, focused = false,
            enabled = true, password = false, scrollable = false,
            left = 0, top = 0, right = 100, bottom = 100)

    private val original = DumpFile(
        DumpMeta(DumpJson.FORMAT_VERSION, "com.example.app", 1234L),
        RawScreen(1080, 2340, listOf(node(0, null), node(1, 0, text = "Add ₹249 \"deal\"", clickable = true)))
    )

    @Test fun writeThenReadGivesTheSameObject() {
        assertEquals(original, DumpJson.read(DumpJson.write(original)))
    }

    @Test fun rootKeepsNullParent_andDerivedValuesAreNotStored() {
        val json = DumpJson.write(original)
        assertNull(DumpJson.read(json).screen.nodes[0].parentId)
        assertFalse(json.contains("\"width\""))
    }
}
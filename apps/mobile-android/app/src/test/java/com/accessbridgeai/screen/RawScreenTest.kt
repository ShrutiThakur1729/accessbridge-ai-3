package com.accessbridgeai.screen
import org.junit.Assert.assertEquals
import org.junit.Test

class RawScreenTest {
    @Test
    fun widthAndHeightComeFromBounds() {
        val n = RawNode(0, null, 0, "android.widget.EditText", "", "",
            clickable = true, editable = true, focused = false, enabled = true,
            password = false, scrollable = false,
            left = 90, top = 200, right = 990, bottom = 320)
        assertEquals(900, n.width)
        assertEquals(120, n.height)
    }
}
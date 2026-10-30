package com.accessbridgeai.detect

import com.accessbridgeai.screen.RawNode
import com.accessbridgeai.screen.RawScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.*
import org.junit.Test

class ScreenViewTest {
    private fun node(id: Int, parent: Int?, text: String = "", desc: String = "", clickable: Boolean = false) =
        RawNode(
            id, parent, 0, "android.view.View", text, desc, clickable = clickable,
            editable = false, focused = false, enabled = true, password = false,
            scrollable = false, left = 0, top = 0, right = 100, bottom = 100
        )

    @Test
    fun labelJoinsOwnAndDescendantText() {
        val screen = ScreenView(
            RawScreen(
                1080, 2340, listOf(
                    node(0, null, clickable = true),
                    node(1, 0, desc = "Search Icon"),
                    node(2, 0, text = "Enter destination")
                )
            )
        )
        assertEquals("search icon enter destination", screen.label(screen.nodes[0]))
    }
    @Test fun labelDropsInvisibleCharactersAndExtraSpaces() {
        val screen = ScreenView(RawScreen(1080, 2340, listOf(node(0, null, text = "\u200Bsid  \uFFFC thane"))))
        assertEquals("sid thane", screen.label(screen.nodes[0]))
    }
}
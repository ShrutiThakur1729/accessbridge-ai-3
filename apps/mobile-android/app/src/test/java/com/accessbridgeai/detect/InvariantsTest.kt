package com.accessbridgeai.detect

import com.accessbridgeai.screen.RawNode
import com.accessbridgeai.screen.RawScreen
import org.junit.Assert.assertTrue
import org.junit.Test

class InvariantsTest {
    private fun node(desc: String, top: Int, bottom: Int, right: Int) =
        RawNode(0, null, 0, "android.view.View", "", desc, clickable = true, editable = false,
            focused = false, enabled = true, password = false, scrollable = false,
            left = 0, top = top, right = right, bottom = bottom)
    private fun view(n: RawNode) = ScreenView(RawScreen(1000, 2000, listOf(n)))

    @Test fun aWordAloneNeverMakesAnOpenerConfident() {
        val d = EntryOpenerDetector.detect(view(node("Search", 1700, 1800, 300)))   // bottom area: no position bonus
        assertTrue(d.top!!.score < DetectorConfig.ACT_SCORE)
    }
    @Test fun aCommitWordOutsideTheBottomAreaIsNotEvenACandidate() {
        assertTrue(CommitDetector.detect(view(node("Book now", 200, 300, 800))).candidates.isEmpty())
    }
    @Test fun aBottomButtonWithoutAWordIsNotConfident() {
        val d = CommitDetector.detect(view(node("Continue", 1800, 1900, 800)))
        assertTrue(d.top!!.score < DetectorConfig.ACT_SCORE)
    }
}
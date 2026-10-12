package com.accessbridgeai.agent

import com.accessbridgeai.detect.CommitDetector
import com.accessbridgeai.detect.EntryFieldDetector
import com.accessbridgeai.detect.EntryOpenerDetector
import com.accessbridgeai.detect.ScreenView
import com.accessbridgeai.screen.Fixtures
import org.junit.Assert
import org.junit.Test

class LadderTest {
    private val ids = Ids("session", "task", 1)
    private fun view(name: String) = ScreenView(Fixtures.load(name).screen)

    @Test
    fun clearWinnersAreActedOn() {
        val cases = listOf(
            Triple(EntryOpenerDetector.detect(view("youtube_01_home")), 27, "youtube opener"),
            Triple(EntryFieldDetector.detect(view("ola_02_destination_entry")), 49, "ola field (2 editable boxes, only 1 focused)"),
            Triple(CommitDetector.detect(view("ola_03_result")), 84, "ola book button"))
        for ((d, id, why) in cases) {
            val dec = Ladder.decide(ids, d)
            Assert.assertEquals(why, Rung.CONFIDENT, dec.rung)
            Assert.assertEquals(why, id, dec.chosen?.nodeId)
        }
    }

    @Test
    fun tiesAndWeakCandidatesAskInstead() {
        // two identical "voice search" mics
        Assert.assertEquals(
            Rung.ASK,
            Ladder.decide(ids, EntryOpenerDetector.detect(view("youtube_02_search_open"))).rung
        )
        // zepto cart has two commit buttons: genuinely ambiguous
        Assert.assertEquals(
            Rung.ASK,
            Ladder.decide(ids, CommitDetector.detect(view("zepto_04_cart"))).rung
        )
        // an unfocused search box: plausible, not confident
        Assert.assertEquals(
            Rung.ASK,
            Ladder.decide(ids, EntryFieldDetector.detect(view("zepto_03_results"))).rung
        )
    }

    @Test
    fun noCandidateMeansAskWithNothingChosen() {
        val dec = Ladder.decide(ids, EntryFieldDetector.detect(view("ola_01_home")))
        Assert.assertEquals(Rung.ASK, dec.rung)
        Assert.assertEquals(null, dec.chosen)
    }
}
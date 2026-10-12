package com.accessbridgeai.detect

import com.accessbridgeai.screen.Fixtures
import org.junit.Assert.assertTrue
import org.junit.Test

class ListDetectorsTest {
    private fun screen(name: String) = ScreenView(Fixtures.load(name).screen)

    @Test fun expectedItemIsAConfidentListItem() {
        var checked = 0
        for ((name, roles) in Expectations.load()) {
            val ids = roles["LIST_ITEM"] ?: continue
            val confident = ListItemDetector.detect(screen(name)).candidates
                .filter { it.score >= 0.75f }.map { it.nodeId }
            assertTrue("$name: expected one of $ids among confident items $confident", ids.any { it in confident })
            checked++
        }
        assertTrue("no LIST_ITEM expectations", checked > 0)
    }

    @Test fun pricedItemsMatchExpectation() {
        var checked = 0
        for ((name, roles) in Expectations.load()) {
            val ids = roles["PRICED_ITEM"] ?: continue
            val found = PricedItemDetector.detect(screen(name)).candidates.map { it.nodeId }
            if (ids.isEmpty()) assertTrue("$name: expected none, found $found", found.isEmpty())
            else assertTrue("$name: expected one of $ids among $found", ids.any { it in found })
            val banned = roles["NOT_PRICED_ITEM"] ?: emptyList()
            assertTrue("$name: $banned must not be priced items, found $found", banned.none { it in found })
            checked++
        }
        assertTrue("no PRICED_ITEM expectations", checked > 0)
    }
}
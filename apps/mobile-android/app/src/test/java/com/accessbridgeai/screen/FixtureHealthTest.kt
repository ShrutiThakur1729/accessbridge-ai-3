package com.accessbridgeai.screen

import org.junit.Assert.*
import org.junit.Test

class FixtureHealthTest {
    @Test
    fun everyFixtureIsStructurallySound() {
        val names = Fixtures.allNames()
        assertTrue("no fixtures found", names.isNotEmpty())
        for (name in names) {
            val nodes = Fixtures.load(name).screen.nodes
            assertTrue("$name: empty", nodes.isNotEmpty())
            nodes.forEachIndexed { i, n ->
                assertEquals("$name: ids must be 0..n-1 (node $i)", i, n.id)
                val p = n.parentId
                if (p == null) {
                    assertEquals("$name: only node 0 may be a root", 0, i)
                } else {
                    assertTrue("$name: parent must come before child (node $i)", p < i)
                    assertEquals("$name: depth must be parent+1 (node $i)", nodes[p].depth + 1, n.depth)
                }
                assertTrue("$name: inverted bounds (node $i)", n.right >= n.left && n.bottom >= n.top)
            }
        }
    }
}
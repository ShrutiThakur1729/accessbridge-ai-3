package com.accessbridgeai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolverTest {
    private fun o(i: Int, label: String, price: Int? = null, eta: Int? = null) = Option(i, i * 100, label, price, eta)
    private val opts = listOf(o(0, "mini 3 min", 485, 3), o(1, "prime suv 2 min", 525, 2), o(2, "auto 5 min", 120, 5))

    @Test fun ordinals() {
        assertEquals(1, Resolver.resolve("the second one", opts))
        assertEquals(2, Resolver.resolve("last", opts))
        assertEquals(0, Resolver.resolve("number 1 please", opts))
    }
    @Test fun cheapestAndFastestUseParsedNumbers() {
        assertEquals(2, Resolver.resolve("the cheapest", opts))
        assertEquals(1, Resolver.resolve("fastest please", opts))
    }
    @Test fun wordsInTheLabelMatch() = assertEquals(1, Resolver.resolve("prime suv", opts))

    @Test fun tiesAndNonsenseNeverGuess() {
        val tied = listOf(o(0, "a", 100), o(1, "b", 100))
        assertNull(Resolver.resolve("cheapest", tied))
        assertNull(Resolver.resolve("cheapest", listOf(o(0, "a"), o(1, "b"))))   // no prices known
        assertNull(Resolver.resolve("banana", opts))
    }
}
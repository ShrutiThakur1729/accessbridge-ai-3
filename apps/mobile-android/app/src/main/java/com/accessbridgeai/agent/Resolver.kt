package com.accessbridgeai.agent

object Resolver {
    private val ORDINALS = mapOf(
        "first" to 0, "1" to 0, "1st" to 0, "second" to 1, "2" to 1, "2nd" to 1, "two" to 1,
        "third" to 2, "3" to 2, "3rd" to 2, "three" to 2, "fourth" to 3, "4" to 3, "4th" to 3, "four" to 3,
        "fifth" to 4, "5" to 4, "5th" to 4, "five" to 4)
    private val CHEAPEST = setOf("cheapest", "cheap", "lowest")
    private val FASTEST = setOf("fastest", "quickest", "soonest", "earliest")
    private val STOP = setOf("the", "one", "please", "option", "number", "that", "this", "with", "and", "for")

    fun resolve(reply: String, options: List<Option>): Int? {
        if (options.isEmpty()) return null
        val words = reply.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if ("last" in words) return options.lastIndex
        words.firstNotNullOfOrNull { ORDINALS[it] }?.let { if (it in options.indices) return it }
        if (words.any { it in CHEAPEST }) return uniqueMin(options) { it.price }
        if (words.any { it in FASTEST }) return uniqueMin(options) { it.etaMin }
        val scores = options.map { o -> words.count { w -> w.length >= 3 && w !in STOP && w in o.label } }
        val best = scores.max()
        return if (best > 0 && scores.count { it == best } == 1) scores.indexOf(best) else null
    }

    /** The option with the smallest value, but only if exactly one has it. Ties never guess. */
    private fun uniqueMin(options: List<Option>, key: (Option) -> Int?): Int? {
        val withKey = options.mapIndexedNotNull { i, o -> key(o)?.let { i to it } }
        if (withKey.isEmpty()) return null
        val min = withKey.minOf { it.second }
        val winners = withKey.filter { it.second == min }
        return if (winners.size == 1) winners[0].first else null
    }
}
package com.accessbridgeai.detect

import com.accessbridgeai.screen.RawNode
import com.accessbridgeai.screen.RawScreen

class ScreenView(val raw: RawScreen) {
    private val invisible = Regex("[\\u200B-\\u200D\\uFEFF\\uFFFC]")
    private val spaces = Regex("\\s+")
    private fun normalize(s: String) = spaces.replace(invisible.replace(s, ""), " ").trim().lowercase()
    val nodes: List<RawNode> get() = raw.nodes

    // parent id -> child ids. Relies on id == index, which FixtureHealthTest checks.
    private val children: Map<Int, List<Int>> =
        raw.nodes.filter { it.parentId != null }.groupBy({ it.parentId!! }, { it.id })

    fun widthFraction(n: RawNode): Float = n.width.toFloat() / raw.screenWidth
    fun areaFraction(n: RawNode): Float =
        (n.width.toLong() * n.height).toFloat() / (raw.screenWidth.toLong() * raw.screenHeight)
    fun centerYFraction(n: RawNode): Float = (n.top + n.bottom) / 2f / raw.screenHeight

    /** Own text and desc first, then every descendant's, lower-cased, in tree order. */
    fun label(n: RawNode): String {
        val parts = ArrayList<String>()
        fun collect(id: Int) {
            val node = raw.nodes[id]
            if (node.text.isNotBlank()) parts += node.text.trim()
            if (node.desc.isNotBlank()) parts += node.desc.trim()
            children[id]?.forEach { collect(it) }
        }
        collect(n.id)
        return normalize(parts.joinToString(" "))
    }
    private val currency = Regex("[\\u20B9\\$\\u20AC\\u00A3]|\\brs\\.?\\s?\\d")
    fun hasCurrency(n: RawNode): Boolean = currency.containsMatchIn(label(n))

    /** Clickable nodes that share a parent, in groups of 3 or more (candidate lists). */
    fun clickableGroups(minSize: Int = 3): List<List<RawNode>> =
        raw.nodes.filter { it.clickable && it.enabled && it.parentId != null }
            .groupBy { it.parentId }.values.filter { it.size >= minSize }

    fun hasAncestorIn(n: RawNode, ids: Set<Int>): Boolean {
        var p = n.parentId
        while (p != null) {
            if (p in ids) return true
            p = raw.nodes[p].parentId
        }
        return false
    }
    val navBarIds: Set<Int> by lazy { NavBarDetector.detect(this).candidates.map { it.nodeId }.toSet() }

    /** Whole-word match, so "add" does not match "address". */
    fun hasWord(label: String, words: List<String>): Boolean =
        words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(label) }

    /** All visible own text and descriptions, lower-cased, one per line. Input for the payment guard. */
    fun allText(): String =
        raw.nodes.flatMap { listOf(it.text, it.desc) }.filter { it.isNotBlank() }.joinToString("\n") { it.lowercase() }
}
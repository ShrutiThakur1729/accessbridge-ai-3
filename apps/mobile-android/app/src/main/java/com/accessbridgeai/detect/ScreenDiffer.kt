package com.accessbridgeai.detect

import com.accessbridgeai.screen.RawNode

data class ScreenDiff(val appeared: List<RawNode>, val disappeared: List<RawNode>)

object ScreenDiffer {
    // Position is deliberately NOT in the signature: a keyboard opening shifts the whole layout.
    private fun sig(n: RawNode) = "${n.className}|${n.text}|${n.desc}|${n.clickable}"

    private fun surplus(from: List<RawNode>, other: List<RawNode>): List<RawNode> {
        val otherCount = other.groupingBy { sig(it) }.eachCount()
        val seen = HashMap<String, Int>()
        return from.filter {
            val s = sig(it)
            val k = (seen[s] ?: 0) + 1
            seen[s] = k
            k > (otherCount[s] ?: 0)      // more of these than before = new
        }
    }

    fun diff(before: ScreenView, after: ScreenView) =
        ScreenDiff(appeared = surplus(after.nodes, before.nodes), disappeared = surplus(before.nodes, after.nodes))
}
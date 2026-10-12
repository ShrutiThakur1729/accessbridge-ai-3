package com.accessbridgeai.agent

import com.accessbridgeai.screen.RawScreen

/** Replays saved screens. A tap moves to the next screen; typing edits the current one. */
class ScriptedDevice(screens: List<RawScreen>) : Device {
    private val current = screens.toMutableList()
    private var cursor = 0
    val actions = mutableListOf<String>()

    override suspend fun read(): RawScreen? = current[cursor]

    override suspend fun tap(nodeId: Int): Boolean {
        val node = current[cursor].nodes.getOrNull(nodeId)
        if (node == null || !node.clickable) { actions += "tap $nodeId (rejected)"; return false }
        actions += "tap $nodeId"
        if (cursor < current.lastIndex) cursor++
        return true
    }

    override suspend fun type(nodeId: Int, text: String): Boolean {
        val s = current[cursor]
        val node = s.nodes.getOrNull(nodeId)
        if (node == null || !node.editable) { actions += "type $nodeId (rejected)"; return false }
        actions += "type $nodeId '$text'"
        current[cursor] = s.copy(nodes = s.nodes.map { if (it.id == nodeId) it.copy(text = text) else it })
        return true
    }

    override suspend fun settle(before: RawScreen): RawScreen? = current[cursor]
    override suspend fun submit(nodeId: Int): Boolean {
        val node = current[cursor].nodes.getOrNull(nodeId)
        if (node == null || !node.editable) { actions += "submit $nodeId (rejected)"; return false }
        actions += "submit $nodeId"
        if (cursor < current.lastIndex) cursor++
        return true
    }
}
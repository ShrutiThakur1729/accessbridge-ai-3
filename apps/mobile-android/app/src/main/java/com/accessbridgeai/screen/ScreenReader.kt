package com.accessbridgeai.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object ScreenReader {
    private const val MAX_NODES = 3000
    private const val MAX_DEPTH = 80

    /** handles (optional): receives the live node for every RawNode, at the same index as its id. */
    fun read(
        root: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int,
        handles: MutableList<AccessibilityNodeInfo>? = null
    ): RawScreen {
        val out = ArrayList<RawNode>()
        walk(root, null, 0, out, handles)
        return RawScreen(screenWidth, screenHeight, out)
    }

    private fun walk(
        node: AccessibilityNodeInfo, parentId: Int?, depth: Int,
        out: MutableList<RawNode>, handles: MutableList<AccessibilityNodeInfo>?
    ) {
        if (out.size >= MAX_NODES || depth > MAX_DEPTH) return
        if (!node.isVisibleToUser) return            // hidden subtree: skip entirely
        val id = out.size
        val r = Rect().also { node.getBoundsInScreen(it) }
        out.add(RawNode(
            id = id, parentId = parentId, depth = depth,
            className = node.className?.toString() ?: "",
            text = if (node.isPassword) "" else node.text?.toString() ?: "",
            desc = node.contentDescription?.toString() ?: "",
            clickable = node.isClickable, editable = node.isEditable, focused = node.isFocused,
            enabled = node.isEnabled, password = node.isPassword, scrollable = node.isScrollable,
            left = r.left, top = r.top, right = r.right, bottom = r.bottom
        ))
        handles?.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, id, depth + 1, out, handles) }
        }
    }
}
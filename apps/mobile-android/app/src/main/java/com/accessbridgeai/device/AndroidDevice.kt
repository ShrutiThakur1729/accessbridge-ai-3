package com.accessbridgeai.device

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.accessbridgeai.agent.Device
import com.accessbridgeai.detect.ScreenDiffer
import com.accessbridgeai.detect.ScreenView
import com.accessbridgeai.screen.RawScreen
import com.accessbridgeai.screen.ScreenReader
import kotlinx.coroutines.delay

class AndroidDevice(
    private val pkg: String,
    private val getRoot: () -> AccessibilityNodeInfo?,
    private val screenSize: () -> Pair<Int, Int>,
    private val tapXY: (Int, Int) -> Boolean
) : Device {
    companion object {
        const val TAG = "AgentDevice"
        const val SETTLE_STEP_MS = 400L
        const val SETTLE_TRIES = 12      // up to about 4.8 s waiting for a change
    }

    // Live nodes of the LAST successful read(); handles[id] is the node for RawNode id.
    private var handles: List<AccessibilityNodeInfo> = emptyList()

    override suspend fun read(): RawScreen? {
        val root = getRoot() ?: return null
        if (root.packageName?.toString() != pkg) return null      // the target app is not in front
        val live = ArrayList<AccessibilityNodeInfo>()
        val (w, h) = screenSize()
        val screen = ScreenReader.read(root, w, h, live)
        if (screen.nodes.isEmpty()) return null
        handles = live
        return screen
    }

    override suspend fun tap(nodeId: Int): Boolean {
        val node = handles.getOrNull(nodeId) ?: return false
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 4) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "tap id=$nodeId via click (hops=$hops)"); return true
            }
            n = n.parent; hops++
        }
        val r = Rect(); node.getBoundsInScreen(r)
        Log.d(TAG, "tap id=$nodeId via coordinates ${r.centerX()},${r.centerY()}")
        return tapXY(r.centerX(), r.centerY())
    }

    override suspend fun type(nodeId: Int, text: String): Boolean {
        val node = handles.getOrNull(nodeId) ?: return false
        if (node.isPassword) { Log.w(TAG, "refusing to type into a password field"); return false }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "type id=$nodeId ok=$ok")
        delay(400)                                                // let the field show the text
        return ok
    }

    override suspend fun submit(nodeId: Int): Boolean {
        val node = handles.getOrNull(nodeId) ?: return false
        if (Build.VERSION.SDK_INT < 30) return false              // IME Enter needs API 30
        val ok = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        Log.d(TAG, "submit id=$nodeId ok=$ok")
        return ok
    }

    override suspend fun settle(before: RawScreen): RawScreen? {
        val beforeView = ScreenView(before)
        var latest: RawScreen? = null
        repeat(SETTLE_TRIES) {
            delay(SETTLE_STEP_MS)
            val now = read() ?: return@repeat
            latest = now
            val d = ScreenDiffer.diff(beforeView, ScreenView(now))
            if (d.appeared.isNotEmpty() || d.disappeared.isNotEmpty()) {
                delay(300)                                        // let a changing screen finish inflating
                return read() ?: now
            }
        }
        return latest                                             // unchanged (or null if the app vanished)
    }
}
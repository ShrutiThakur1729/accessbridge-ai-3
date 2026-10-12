package com.accessbridgeai.screen

data class RawNode(
    val id: Int,              // index within THIS capture only
    val parentId: Int?,
    val depth: Int,
    val className: String,    // raw, e.g. "android.widget.EditText"
    val text: String,
    val desc: String,         // contentDescription
    val clickable: Boolean, val editable: Boolean, val focused: Boolean,
    val enabled: Boolean, val password: Boolean, val scrollable: Boolean,
    val left: Int, val top: Int, val right: Int, val bottom: Int   // raw pixels
) {
    val width get() = right - left
    val height get() = bottom - top
    /** One readable line, used for Logcat and test failure messages. */
    fun summary(): String {
        val flags = buildString {
            if (clickable) append("CLICK ")
            if (editable) append("EDIT ")
            if (focused) append("FOCUS ")
            if (scrollable) append("SCROLL ")
            if (password) append("PWD ")
            if (!enabled) append("DISABLED ")
        }
        return "id=$id p=${parentId ?: "-"} d=$depth ${className.substringAfterLast('.')} " +
                "$flags[$left,$top,$right,$bottom] text='$text' desc='$desc'"
    }
}

data class RawScreen(val screenWidth: Int, val screenHeight: Int, val nodes: List<RawNode>)
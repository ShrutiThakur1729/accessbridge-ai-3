package com.accessbridgeai.services

import android.R
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs

class FloatingMicController(
    private val service: AccessibilityService,
    private val onTap: () -> Unit
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = service.getSharedPreferences("floating_mic", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val size = (56 * density).toInt()
    private val margin = (8 * density).toInt()
    private val slop = ViewConfiguration.get(service).scaledTouchSlop

    private var root: FrameLayout? = null
    private var bg: GradientDrawable? = null
    private var lp: WindowManager.LayoutParams? = null

    @Volatile var state: String = "IDLE"
        private set

    @SuppressLint("ClickableViewAccessibility")
    fun show() = main.post {
        if (root != null) return@post
        val dm = service.resources.displayMetrics
        bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_IDLE)
            setStroke((2 * density).toInt(), Color.WHITE)
        }
        val icon = ImageView(service).apply {
            setImageResource(R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
            val pad = (13 * density).toInt()
            setPadding(pad, pad, pad, pad)
            contentDescription = "AccessBridge microphone"
        }
        val view = FrameLayout(service).apply {
            background = bg
            elevation = 8 * density
            alpha = 0.75f
            addView(icon, FrameLayout.LayoutParams(size, size))
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", dm.widthPixels - size - margin)
            y = prefs.getInt("y", (dm.heightPixels * 0.4).toInt())
        }
        view.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0; var startY = 0
            var downX = 0f; var downY = 0f
            var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val p = lp ?: return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = p.x; startY = p.y; downX = e.rawX; downY = e.rawY; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                        if (moved) {
                            p.x = (startX + dx).toInt(); p.y = (startY + dy).toInt()
                            runCatching { wm.updateViewLayout(root, p) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) snapAndSave(p) else { v.performClick(); onTap() }
                    }
                }
                return true
            }
        })
        root = view; lp = params
        runCatching { wm.addView(view, params) }
    }

    private fun snapAndSave(p: WindowManager.LayoutParams) {
        val dm = service.resources.displayMetrics
        p.x = if (p.x + size / 2 < dm.widthPixels / 2) margin else dm.widthPixels - size - margin
        p.y = p.y.coerceIn(0, dm.heightPixels - size)
        runCatching { wm.updateViewLayout(root, p) }
        prefs.edit().putInt("x", p.x).putInt("y", p.y).apply()
    }

    /** Safe to call from any thread. */
    fun setState(newState: String) = main.post {
        state = newState
        val color = when {
            newState == "LISTENING" -> COLOR_LISTENING
            newState == "THINKING" -> COLOR_THINKING
            newState == "SPEAKING" -> COLOR_SPEAKING
            else -> COLOR_IDLE
        }
        bg?.setColor(color)
        root?.alpha = if (newState == "IDLE") 0.75f else 1f
        root?.animate()?.scaleX(if (newState == "LISTENING") 1.15f else 1f)
            ?.scaleY(if (newState == "LISTENING") 1.15f else 1f)?.setDuration(150)?.start()
    }

    fun destroy() = main.post {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    companion object {
        private val COLOR_IDLE = Color.parseColor("#3B82F6")
        private val COLOR_LISTENING = Color.parseColor("#EF4444")
        private val COLOR_THINKING = Color.parseColor("#F59E0B")
        private val COLOR_SPEAKING = Color.parseColor("#10B981")
    }
}

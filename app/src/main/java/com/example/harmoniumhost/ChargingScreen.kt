package com.example.harmoniumhost

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen "it's charging" confirmation shown for a few seconds after the remote lands on the
 * cradle. Static drawing (no animation), so it costs nothing while shown. Tap or any key hides it.
 */
class ChargingScreen(context: Context) : FrameLayout(context) {

    private val battery = BatteryDrawing(context)
    private val percent = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 44f
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        gravity = Gravity.CENTER
    }
    private val label = TextView(context).apply {
        setTextColor(0xFF4ADE80.toInt())
        textSize = 18f
        gravity = Gravity.CENTER
    }
    private val hide = Runnable { visibility = GONE }

    init {
        setBackgroundColor(0xF2000000.toInt())
        visibility = GONE
        setOnClickListener { dismiss() }
        isFocusable = false   // clickable views are focusable on API 26+; keys must stay on the WebView
        val dp = resources.displayMetrics.density
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(battery, LinearLayout.LayoutParams((120 * dp).toInt(), (60 * dp).toInt()))
            addView(percent, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (12 * dp).toInt() })
            addView(label)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun show(level: Int, full: Boolean, forMs: Long) {
        battery.level = level
        battery.invalidate()
        percent.text = if (level >= 0) "$level%" else ""
        label.text = if (full) "Fully charged" else "Charging"
        removeCallbacks(hide)
        visibility = VISIBLE
        postDelayed(hide, forMs)
    }

    fun dismiss() {
        removeCallbacks(hide)
        visibility = GONE
    }

    private class BatteryDrawing(context: Context) : View(context) {
        var level = -1
        private val dp = context.resources.displayMetrics.density
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = Color.WHITE
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4ADE80.toInt() }
        private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val body = RectF()
        private val tip = RectF()
        private val juice = RectF()
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val s = outline.strokeWidth
            val tipW = w * 0.06f
            body.set(s / 2, s / 2, w - tipW - s, h - s / 2)
            tip.set(w - tipW - s / 2, h * 0.3f, w, h * 0.7f)
            canvas.drawRoundRect(body, 6 * dp, 6 * dp, outline)
            canvas.drawRoundRect(tip, 2 * dp, 2 * dp, outline)

            val pad = s * 1.8f
            val frac = (level.coerceIn(0, 100)) / 100f
            juice.set(body.left + pad, body.top + pad,
                body.left + pad + (body.width() - 2 * pad) * frac, body.bottom - pad)
            canvas.drawRoundRect(juice, 3 * dp, 3 * dp, fill)

            // lightning bolt, centred
            val cx = body.centerX()
            val cy = body.centerY()
            val bh = body.height() * 0.62f
            path.reset()
            path.moveTo(cx + bh * 0.10f, cy - bh / 2)
            path.lineTo(cx - bh * 0.28f, cy + bh * 0.06f)
            path.lineTo(cx - bh * 0.02f, cy + bh * 0.06f)
            path.lineTo(cx - bh * 0.10f, cy + bh / 2)
            path.lineTo(cx + bh * 0.28f, cy - bh * 0.06f)
            path.lineTo(cx + bh * 0.02f, cy - bh * 0.06f)
            path.close()
            canvas.drawPath(path, bolt)
        }
    }
}

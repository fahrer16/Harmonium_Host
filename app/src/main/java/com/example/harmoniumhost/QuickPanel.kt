package com.example.harmoniumhost

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import com.example.harmoniumhost.HarmoniumStyle as S

/**
 * The pull-down panel, like the stock Astrion launcher's: pulled from the top edge (MainActivity
 * feeds it the drag), it follows the finger and settles open or closed. Quick status (Wi-Fi,
 * Home Assistant, battery), brightness and volume, and a few buttons; "Settings" opens the full
 * settings screen. Swipe it up, tap below it, or press Back to close it.
 */
class QuickPanel(context: Context, private val actions: Actions) : FrameLayout(context) {

    interface Actions {
        fun reload()
        fun screensaver()
        fun openSettings()
        /** A setting changed here (keep-awake): re-apply. */
        fun settingsChanged()
    }

    private companion object {
        const val AUTO_CLOSE_MS = 30_000L
        const val ANIM_MS = 180L
    }

    private val dp = resources.displayMetrics.density
    private val prefs = HostApp.of(context).prefs
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val autoClose = Runnable { close() }
    private var downY = 0f
    private var downX = 0f
    private var draggingUp = false
    private var askedWriteSettings = false

    private val scrim = View(context).apply {
        setBackgroundColor(0x99000000.toInt())
        setOnClickListener { close() }
        isFocusable = false
    }
    private val sheet = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(12), px(8), px(12), px(6))
        background = GradientDrawable().apply {
            setColor(S.BG)
            val r = 18 * dp
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        }
        isClickable = true                         // touches on the sheet never reach the scrim
    }
    private val battery = text(13f, S.TEXT)
    private val netTitle = text(15f, S.TEXT, bold = true)
    private val netSub = text(12f, S.DIM)
    private val haSub = text(12f, S.DIM)
    private val brightness = slider { v -> setBrightness(v) }
    private val volume = slider { v -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
    private val stayAwake = button("◐", "Stay on") { toggleStayAwake() }

    val isOpen get() = visibility == VISIBLE

    init {
        visibility = GONE
        addView(scrim, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(sheet, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        // Status line: time, battery.
        sheet.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextClock(context).apply {
                format12Hour = "h:mm"
                format24Hour = "H:mm"
                textSize = 13f
                setTextColor(S.TEXT)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(battery)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(8) })

        // Two tiles: the network, Home Assistant.
        sheet.addView(LinearLayout(context).apply {
            addView(tile(netTitle, netSub), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(tile(text(15f, S.TEXT, bold = true).apply { text = "Home Assistant" }, haSub),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = px(8) })
        })

        sheet.addView(sliderRow("☀", brightness), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(8) })
        sheet.addView(sliderRow("🔊", volume), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(8) })

        sheet.addView(LinearLayout(context).apply {
            addView(button("⟳", "Reload") { close(); actions.reload() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(stayAwake, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = px(8) })
            addView(button("◷", "Clock") { close(); actions.screensaver() },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = px(8) })
            addView(button("⚙", "Settings") { close(); actions.openSettings() },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = px(8) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(10) })

        // The handle: a hint that it slides back up.
        sheet.addView(View(context).apply { background = S.rounded(S.FAINT, 2 * dp) },
            LinearLayout.LayoutParams(px(36), px(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = px(10)
            })
    }

    // ---------- opening and closing ----------

    /** A pull from the top edge has started: show the panel, still hidden above the screen. */
    fun beginDrag() {
        refresh()
        visibility = VISIBLE
        sheet.animate().cancel()
        sheet.translationY = -sheetHeight()
        scrim.alpha = 0f
    }

    /** The finger is [pulled] px below where the pull started. */
    fun dragTo(pulled: Float) {
        val h = sheetHeight()
        sheet.translationY = (pulled - h).coerceIn(-h, 0f)
        scrim.alpha = 1f + sheet.translationY / h
    }

    /** The finger lifted: settle open if pulled past a third, else back up. */
    fun release() = if (sheet.translationY > -sheetHeight() * 2 / 3) open() else close()

    fun open() {
        if (!isOpen) beginDrag()
        sheet.animate().translationY(0f).setDuration(ANIM_MS).start()
        scrim.animate().alpha(1f).setDuration(ANIM_MS).start()
        poke()
    }

    fun close() {
        if (!isOpen) return
        removeCallbacks(autoClose)
        sheet.animate().translationY(-sheetHeight()).setDuration(ANIM_MS).withEndAction { visibility = GONE }.start()
        scrim.animate().alpha(0f).setDuration(ANIM_MS).start()
    }

    /** Someone is using it: restart the auto-close timer. */
    fun poke() {
        removeCallbacks(autoClose)
        postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    private fun sheetHeight(): Float {
        if (sheet.height > 0) return sheet.height.toFloat()
        sheet.measure(MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return sheet.measuredHeight.toFloat().coerceAtLeast(1f)
    }

    // A swipe up on the sheet pushes it back up (sliders keep sideways drags).
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; draggingUp = false; poke() }
            MotionEvent.ACTION_MOVE -> if (downY - ev.y > 16 * dp && downY - ev.y > abs(ev.x - downX)) draggingUp = true
        }
        return draggingUp
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!draggingUp) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                sheet.translationY = (ev.y - downY).coerceIn(-sheetHeight(), 0f)
                scrim.alpha = 1f + sheet.translationY / sheetHeight()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingUp = false
                if (sheet.translationY < -sheetHeight() / 4) close() else open()
            }
        }
        return true
    }

    // ---------- contents ----------

    /** Re-reads everything shown (on open, and when HostState changes while open). */
    fun refresh() {
        val level = HostState.batteryLevel
        battery.text = when {
            level < 0 -> ""
            HostState.batteryFull -> "⚡ $level%  full"
            HostState.charging -> "⚡ $level%  charging"
            else -> "$level%"
        }
        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo
        val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        val ip = info?.ipAddress ?: 0
        netTitle.text = ssid ?: "Wi-Fi"
        netSub.text = if (ip == 0) "not connected"
            else "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
        haSub.text = when {
            HostState.voiceReady -> "Connected · voice ready"
            HostState.haConnected -> "Connected"
            else -> "Not connected"
        }
        haSub.setTextColor(if (HostState.haConnected) S.OK else S.DANGER)
        brightness.max = 255
        brightness.progress = try { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (e: Exception) { 128 }
        volume.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val on = prefs.keepAwake == HostPrefs.KEEP_ALWAYS
        stayAwake.background = S.focusable(if (on) S.ACCENT else S.TILE, dp, pressed = if (on) S.ACCENT else S.TILE_HI,
            ringColor = if (on) S.TEXT else S.ACCENT)
        (stayAwake.getChildAt(0) as TextView).setTextColor(if (on) S.ACCENT_INK else S.TEXT)
        (stayAwake.getChildAt(1) as TextView).setTextColor(if (on) S.ACCENT_INK else S.DIM)
    }

    private fun setBrightness(v: Int) {
        if (!Settings.System.canWrite(context)) {
            if (!askedWriteSettings) {
                askedWriteSettings = true
                Toast.makeText(context, "Allow \"Modify system settings\" to change the brightness", Toast.LENGTH_LONG).show()
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        val cr = context.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(1, 255))
    }

    /** "Stay on": keep the screen on everywhere; off again restores the previous choice. */
    private fun toggleStayAwake() {
        if (prefs.keepAwake == HostPrefs.KEEP_ALWAYS) {
            val before = prefs.rawString("keep_awake_before_stay_on") ?: HostPrefs.KEEP_CRADLE
            prefs.edit { putString("keep_awake", if (before == HostPrefs.KEEP_ALWAYS) HostPrefs.KEEP_CRADLE else before) }
        } else {
            prefs.edit { putString("keep_awake_before_stay_on", prefs.keepAwake).putString("keep_awake", HostPrefs.KEEP_ALWAYS) }
        }
        actions.settingsChanged()
        refresh()
    }

    // ---------- tiny builders ----------

    private fun px(v: Int) = (v * dp).toInt()

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
    }

    private fun tile(title: TextView, sub: TextView) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = S.rounded(S.TILE, S.RADIUS_DP * dp)
        setPadding(px(12), px(10), px(12), px(10))
        addView(title)
        addView(sub)
    }

    private fun slider(onChange: (Int) -> Unit) = SeekBar(context).apply {
        progressTintList = android.content.res.ColorStateList.valueOf(S.ACCENT)
        thumbTintList = android.content.res.ColorStateList.valueOf(S.ACCENT)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(S.HAIRLINE)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { if (fromUser) { onChange(v); poke() } }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun sliderRow(icon: String, bar: SeekBar) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = S.rounded(S.TILE, S.RADIUS_DP * dp)
        setPadding(px(12), px(10), px(4), px(10))
        addView(text(16f, S.DIM).apply { text = icon })
        addView(bar, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun button(icon: String, label: String, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = S.focusable(S.TILE, dp)
        setPadding(0, px(8), 0, px(8))
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        addView(text(20f, S.TEXT).apply { text = icon; gravity = Gravity.CENTER })
        addView(text(11f, S.DIM).apply { text = label; gravity = Gravity.CENTER })
    }
}

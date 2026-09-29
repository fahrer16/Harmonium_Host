package com.example.harmoniumhost

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Window
import android.view.WindowManager

/**
 * Keeps the screen on, dimmed, while it matters, so the first key press always acts.
 *
 * Why: Android spends the key that wakes a sleeping screen on waking it; the app never sees that
 * press. A dim screen is still "interactive", so every key reaches Harmonium. Rules:
 *  - hold the screen on while [HostPrefs.keepAwake] says so (default: an activity is running)
 *  - dim to [HostPrefs.dimLevelPct] after [HostPrefs.dimAfterSec] without a press
 *  - give up after [HostPrefs.awakeLimitMin] without a press (not on the cradle, by default),
 *    then Android's own screen timeout turns it off
 * Any key, touch, screen-on, proximity or unplug counts as a press ([interaction]).
 * Timers are single postDelayed calls; nothing polls.
 */
class ScreenKeeper(private val window: Window, private val prefs: HostPrefs) {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { evaluate() }
    private var lastInteraction = SystemClock.elapsedRealtime()
    private var holding = false
    private var dimmed = false

    fun interaction() {
        lastInteraction = SystemClock.elapsedRealtime()
        setDimmed(false)
        evaluate()
    }

    /** Re-applies the rules; call after HostState or settings change. */
    fun evaluate() {
        handler.removeCallbacks(tick)
        val idle = SystemClock.elapsedRealtime() - lastInteraction
        val wanted = when (prefs.keepAwake) {
            HostPrefs.KEEP_ALWAYS -> true
            HostPrefs.KEEP_ACTIVITY -> HostState.activityRunning
            else -> false
        }
        val limitMs = prefs.awakeLimitMin * 60_000L
        val limited = limitMs > 0 && !(HostState.charging && prefs.cradleNoLimit)
        val hold = wanted && (!limited || idle < limitMs)
        setHolding(hold)
        if (!hold) return        // Android's own timeout (and dimming) is in charge

        var next = Long.MAX_VALUE
        val dimMs = prefs.dimAfterSec * 1000L
        if (dimMs > 0) {
            if (idle >= dimMs) setDimmed(true) else next = dimMs - idle
        }
        if (limited) next = minOf(next, limitMs - idle)
        if (next != Long.MAX_VALUE) handler.postDelayed(tick, next)
    }

    fun release() {
        handler.removeCallbacks(tick)
        setHolding(false)
    }

    private fun setHolding(on: Boolean) {
        if (on == holding) return
        holding = on
        Log.i(TAG, "keep screen on: $on")
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setDimmed(on: Boolean) {
        if (on == dimmed) return
        dimmed = on
        val lp = window.attributes
        // Android clamps this to the panel's minimum, so the screen stays on and readable.
        lp.screenBrightness = if (on) (prefs.dimLevelPct.coerceIn(0, 100) / 100f).coerceAtLeast(0.01f)
                              else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private companion object { const val TAG = "HarmoniumHost" }
}

package com.example.harmoniumhost

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Replaces Key Mapper: turns physical keys into the keys Harmonium's keymap expects.
 * Harmonium fires taps on keydown and leaves hold gestures to the shell, so a key with a
 * long-press rule sends its short-press key on release (if released early) or its long-press
 * key once held for [longPressMs]. A key without a long-press rule is translated 1:1, repeats
 * included (volume keeps repeating while held).
 *
 * Rules are text, edited in Settings, one per line: `<key> <short press> [<long press>]`.
 * Names are KeyEvent names without the KEYCODE_ prefix (or numbers); `-` means "send nothing".
 */
class KeyRemap(private val sink: (KeyEvent) -> Unit) {

    private class Rule(val tap: Int?, val hold: Int?)

    private val handler = Handler(Looper.getMainLooper())
    private var rules: Map<Int, Rule> = emptyMap()
    private var longPressMs = 600L
    private var heldKey = -1
    private var holdFired = false
    private val holdRunnable = Runnable {
        holdFired = true
        rules[heldKey]?.hold?.let { press(it) }
    }

    fun configure(text: String, longPressMs: Int) {
        rules = parse(text).first
        this.longPressMs = longPressMs.coerceIn(200, 3000).toLong()
    }

    /** Returns true when the event was taken (translated or swallowed). */
    fun handle(e: KeyEvent): Boolean {
        val rule = rules[e.keyCode] ?: return false
        if (rule.hold == null) {
            rule.tap?.let { sink(KeyEvent(e.downTime, e.eventTime, e.action, it, e.repeatCount, e.metaState)) }
            return true
        }
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                handler.removeCallbacks(holdRunnable)
                heldKey = e.keyCode
                holdFired = false
                handler.postDelayed(holdRunnable, longPressMs)
            }
            KeyEvent.ACTION_UP -> if (e.keyCode == heldKey) {
                handler.removeCallbacks(holdRunnable)
                if (!holdFired) rule.tap?.let { press(it) }
                heldKey = -1
            }
        }
        return true
    }

    private fun press(code: Int) {
        val t = SystemClock.uptimeMillis()
        sink(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        sink(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    companion object {
        private const val TAG = "HarmoniumHost"

        /** Parses rule text. Returns the rules plus a list of problems for Settings to show. */
        private fun parse(text: String): Pair<Map<Int, Rule>, List<String>> {
            val out = HashMap<Int, Rule>()
            val errors = ArrayList<String>()
            text.lines().forEachIndexed { i, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val parts = line.split(Regex("\\s+"))
                val from = code(parts[0])
                val tap = parts.getOrNull(1)?.let { if (it == "-") null else code(it) }
                val hold = parts.getOrNull(2)?.let { if (it == "-") null else code(it) }
                when {
                    parts.size !in 2..3 -> errors += "line ${i + 1}: expected 2 or 3 names"
                    from == null -> errors += "line ${i + 1}: unknown key ${parts[0]}"
                    tap == null && parts[1] != "-" -> errors += "line ${i + 1}: unknown key ${parts[1]}"
                    hold == null && parts.size == 3 && parts[2] != "-" ->
                        errors += "line ${i + 1}: unknown key ${parts[2]}"
                    else -> out[from] = Rule(tap, hold)
                }
            }
            errors.forEach { Log.w(TAG, "key rules: $it") }
            return out to errors
        }

        fun problems(text: String) = parse(text).second

        private fun code(name: String): Int? {
            name.toIntOrNull()?.let { return it.takeIf { c -> c > 0 } }
            val n = name.uppercase().removePrefix("KEYCODE_")
            return KeyEvent.keyCodeFromString("KEYCODE_$n").takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
        }
    }
}

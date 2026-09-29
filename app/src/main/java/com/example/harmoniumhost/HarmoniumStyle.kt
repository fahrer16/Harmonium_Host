package com.example.harmoniumhost

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/**
 * Harmonium's look (its src/styles/tokens.css "skin tokens"), so the native screens sit next to
 * the web UI without a seam: near-black canvas, dark slate cards, amber accent, 12 px corners,
 * and a 2 px amber ring on whatever the D-pad has focused.
 */
object HarmoniumStyle {
    const val BG = 0xFF0A0B0D.toInt()          // --bg
    const val TILE = 0xFF171E27.toInt()        // --tile
    const val TILE_HI = 0xFF222B36.toInt()     // --tile-hi (controls)
    const val HAIRLINE = 0xFF2A3340.toInt()    // --track-hairline
    const val TEXT = 0xFFF2F5F8.toInt()        // --text
    const val DIM = 0xFF98A2AE.toInt()         // --dim
    const val FAINT = 0xFF5B6674.toInt()       // --faint
    const val ACCENT = 0xFFFFB020.toInt()      // --accent
    const val ACCENT_INK = 0xFF14181D.toInt()  // --accent-ink
    const val OK = 0xFF4ADE80.toInt()          // --ok
    const val DANGER = 0xFFE05252.toInt()      // --danger
    const val RADIUS_DP = 12f

    fun rounded(color: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

    /** A surface that shows the amber focus ring when the D-pad lands on it. */
    fun focusable(color: Int, density: Float, pressed: Int = TILE_HI, ringColor: Int = ACCENT): Drawable {
        val r = RADIUS_DP * density
        val ring = (2 * density).toInt()
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(color, r, ring, ringColor))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, r))
            addState(intArrayOf(), rounded(color, r))
        }
    }

    val switchThumb = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ACCENT, DIM))
    val switchTrack = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0x66FFB020, HAIRLINE))
}

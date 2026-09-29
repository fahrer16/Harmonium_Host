package com.example.harmoniumhost

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.Random

/**
 * Full-screen screensaver: black, clock, or clock + weather (from an HA weather entity the
 * ESPHome link forwards). TextClock ticks once a minute; the content drifts a little each minute
 * so nothing sits in one place. A tap hides it; keys hide it AND still reach Harmonium.
 */
class Screensaver(context: Context) : FrameLayout(context) {

    private val dp = resources.displayMetrics.density
    private val random = Random()
    private val time = TextClock(context).apply {
        format12Hour = "h:mm"
        format24Hour = "H:mm"
        textSize = 64f
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        setTextColor(HarmoniumStyle.TEXT)
        gravity = Gravity.CENTER
    }
    private val date = TextClock(context).apply {
        format12Hour = "EEEE, MMMM d"
        format24Hour = "EEEE, d MMMM"
        textSize = 16f
        setTextColor(HarmoniumStyle.DIM)
        gravity = Gravity.CENTER
    }
    private val weather = TextView(context).apply {
        textSize = 20f
        setTextColor(HarmoniumStyle.TEXT)
        gravity = Gravity.CENTER
        setPadding(0, (18 * dp).toInt(), 0, 0)
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(time)
        addView(date)
        addView(weather)
    }
    private val drift = object : Runnable {
        override fun run() {
            val range = 24 * dp
            content.translationX = (random.nextFloat() * 2 - 1) * range
            content.translationY = (random.nextFloat() * 2 - 1) * range
            postDelayed(this, 60_000)
        }
    }

    init {
        setBackgroundColor(0xFF000000.toInt())
        visibility = GONE
        setOnClickListener { hide() }
        isFocusable = false   // clickable views are focusable on API 26+; keys must stay on the WebView
        addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    val showing get() = visibility == VISIBLE

    fun show(mode: String) {
        content.visibility = if (mode == "black") View.GONE else View.VISIBLE
        weather.visibility = if (mode == "weather") View.VISIBLE else View.GONE
        updateWeather()
        if (!showing) {
            visibility = VISIBLE
            removeCallbacks(drift)
            post(drift)
        }
        HostState.screensaverOn = true
    }

    fun hide() {
        if (!showing) return
        visibility = GONE
        removeCallbacks(drift)
        HostState.screensaverOn = false
        HostApp.of(context).esp.refresh()
    }

    fun updateWeather() {
        val condition = HostState.weatherCondition
        weather.text = if (condition.isEmpty()) {
            "Set a weather entity in Settings"
        } else {
            val temp = HostState.weatherTemperature.takeIf { it.isNotEmpty() }
                ?.let { "  ${it.toFloatOrNull()?.let { t -> Math.round(t).toString() } ?: it}${HostState.weatherUnit}" } ?: ""
            "${icon(condition)}  ${label(condition)}$temp"
        }
    }

    private fun label(c: String) = when (c) {
        "partlycloudy" -> "Partly cloudy"
        "clear-night" -> "Clear"
        "lightning-rainy" -> "Thunderstorms"
        "snowy-rainy" -> "Sleet"
        "windy-variant" -> "Windy"
        else -> c.replace('-', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun icon(c: String) = when (c) {
        "sunny" -> "☀"
        "clear-night" -> "☾"
        "partlycloudy" -> "⛅"
        "cloudy", "fog" -> "☁"
        "rainy", "pouring" -> "☔"
        "snowy", "snowy-rainy", "hail" -> "❄"
        "lightning", "lightning-rainy" -> "⚡"
        "windy", "windy-variant" -> "〰"
        else -> ""
    }
}

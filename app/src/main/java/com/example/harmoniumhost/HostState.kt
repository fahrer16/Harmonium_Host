package com.example.harmoniumhost

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide state shared by HostService (which senses it), MainActivity (which shows it) and
 * the ESPHome entities (which report it). Writes and callbacks happen on the main thread; setters
 * called elsewhere are posted. Reads from other threads see a recent value, which is all the
 * entities need.
 */
object HostState {

    enum class Event {
        SCREEN_ON, PROXIMITY, PLUGGED, UNPLUGGED,
        // commands from Home Assistant, carried out by MainActivity
        RELOAD, CLEAR_CACHE, SCREENSAVER_ON, SCREENSAVER_OFF,
        /** "Screen off" without device-admin rights: a black screensaver at minimum brightness. */
        SCREEN_BLACK,
    }

    interface Listener {
        fun onHostState() {}
        fun onHostEvent(event: Event) {}
    }

    /** Current option of the activity select; null until HA has told us (or the entity is missing). */
    @Volatile var activity: String? = null
        private set
    @Volatile var activityRunning = false
        private set
    @Volatile var charging = false
        private set
    @Volatile var batteryFull = false
        private set
    @Volatile var batteryLevel = -1
        private set
    /** Home Assistant is connected to this remote's ESPHome API. */
    @Volatile var haConnected = false
        private set
    /** HA has subscribed to the voice assistant, so push-to-talk can run. */
    @Volatile var voiceReady = false
        private set
    @Volatile var lastProximity = ""
        private set

    // Written by MainActivity / HostService, read by the ESPHome entities.
    @Volatile var pageUrl = ""
    @Volatile var lastInteractionAt = System.currentTimeMillis()
    @Volatile var screensaverOn = false
    @Volatile var appInForeground = false
    @Volatile var lux: Float? = null
    @Volatile var weatherCondition = ""
    @Volatile var weatherTemperature = ""
    @Volatile var weatherUnit = ""
    /** Set by MainActivity: a JPEG of the screen, taken on the main thread. */
    @Volatile var screenshot: (() -> ByteArray?)? = null

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun changed() = listeners.forEach { it.onHostState() }

    fun setActivity(state: String?, idle: Set<String>) = onMain {
        val running = state != null && state.lowercase() !in idle
        if (state != activity || running != activityRunning) {
            activity = state
            activityRunning = running
            changed()
        }
    }

    fun setBattery(level: Int, charging: Boolean, full: Boolean) = onMain {
        if (level != batteryLevel || charging != this.charging || full != batteryFull) {
            batteryLevel = level
            this.charging = charging
            batteryFull = full
            changed()
        }
    }

    fun setHaConnected(connected: Boolean) = onMain {
        if (connected != haConnected) { haConnected = connected; changed() }
    }

    fun setVoiceReady(ready: Boolean) = onMain {
        if (ready != voiceReady) { voiceReady = ready; changed() }
    }

    fun setWeather(condition: String? = null, temperature: String? = null, unit: String? = null) = onMain {
        condition?.let { weatherCondition = it }
        temperature?.let { weatherTemperature = it }
        unit?.let { weatherUnit = it }
        changed()
    }

    fun proximity(near: Boolean, at: String) = onMain {
        lastProximity = "${if (near) "near" else "far"} at $at"
        fire(Event.PROXIMITY)
    }

    fun fire(event: Event) = onMain { listeners.forEach { it.onHostEvent(event) } }
}

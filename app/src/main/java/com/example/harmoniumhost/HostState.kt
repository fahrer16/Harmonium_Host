package com.example.harmoniumhost

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide state shared by HostService (which senses it) and MainActivity (which shows it).
 * All reads, writes and callbacks happen on the main thread; setters called elsewhere are posted.
 */
object HostState {

    enum class Event { SCREEN_ON, PROXIMITY, PLUGGED, UNPLUGGED }

    interface Listener {
        fun onHostState() {}
        fun onHostEvent(event: Event) {}
    }

    /** Current option of the activity select; null until HA has told us (or the entity is missing). */
    var activity: String? = null
        private set
    var activityRunning = false
        private set
    var charging = false
        private set
    var batteryFull = false
        private set
    var batteryLevel = -1
        private set
    /** Home Assistant is connected to this remote's ESPHome API. */
    var haConnected = false
        private set
    /** HA has subscribed to the voice assistant, so push-to-talk can run. */
    var voiceReady = false
        private set
    var lastProximity = ""
        private set

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

    fun proximity(near: Boolean, at: String) = onMain {
        lastProximity = "${if (near) "near" else "far"} at $at"
        fire(Event.PROXIMITY)
    }

    fun fire(event: Event) = onMain { listeners.forEach { it.onHostEvent(event) } }
}

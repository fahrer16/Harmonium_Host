package com.example.harmoniumhost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Always-running companion to MainActivity:
 *  - battery and cradle state for the status line and the charging screen
 *  - on the cradle: wake briefly so the charging screen is seen
 *  - off the cradle: wake immediately (you're about to use it)
 *  - proximity (the device's only wake-up sensor): wake when a hand comes near
 *  - ambient light, read only while the screen is on
 *  - the ESPHome API server: voice satellite, controls and diagnostics for Home Assistant
 *  - while the screen is on: Wi-Fi out of power save
 */
class HostService : Service() {

    companion object {
        /** Sent by Settings after a save. */
        const val ACTION_RELOAD = "com.example.harmoniumhost.RELOAD"
        private const val EXTRA_ESP_CHANGED = "esp_changed"
        private const val TAG = "HarmoniumHost"
        private const val CHANNEL = "host"
        private const val PROXIMITY_WAKE_GAP_MS = 3_000L
        private const val LUX_REPORT_GAP_MS = 5_000L

        fun reload(context: Context, espChanged: Boolean = false) = ContextCompat.startForegroundService(
            context, Intent(context, HostService::class.java).setAction(ACTION_RELOAD)
                .putExtra(EXTRA_ESP_CHANGED, espChanged))
    }

    private lateinit var app: HostApp
    private lateinit var power: PowerManager
    private lateinit var sensors: SensorManager
    private lateinit var device: RemoteDevice
    private var wifiLock: WifiManager.WifiLock? = null
    private var proximity: Sensor? = null
    private var lastProximityWake = 0L
    private var lastLuxReport = 0L
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Must be registered at runtime: manifest receivers don't get these since Android 8.0.
    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    HostState.fire(HostState.Event.PLUGGED)
                    if (app.prefs.chargeScreenSec > 0) wakeScreen(app.prefs.chargeScreenSec * 1000L)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    HostState.fire(HostState.Event.UNPLUGGED)
                    wakeScreen(10_000)
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                    HostState.setBattery(pct, plugged, plugged && status == BatteryManager.BATTERY_STATUS_FULL)
                    app.esp.refresh()        // queued on the ESPHome writer thread, never the main thread
                }
                Intent.ACTION_SCREEN_ON -> screen(true)
                Intent.ACTION_SCREEN_OFF -> screen(false)
            }
        }
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val near = e.values[0] < (proximity?.maximumRange ?: 5f)
            val interactive = power.isInteractive
            Log.i(TAG, "proximity ${if (near) "NEAR" else "FAR"} (${e.values[0]}) screen=${if (interactive) "on" else "off"}")
            HostState.proximity(near, clock.format(Date()))
            val now = SystemClock.elapsedRealtime()
            if (!interactive && now - lastProximityWake > PROXIMITY_WAKE_GAP_MS) {
                lastProximityWake = now
                Log.i(TAG, "proximity wake")
                wakeScreen(1_000)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Non-wake-up, so registered only while the screen is on. Reports at most every 5 s, on real changes. */
    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val lux = e.values[0]
            val prev = HostState.lux
            HostState.lux = lux
            val now = SystemClock.elapsedRealtime()
            val big = prev == null || abs(lux - prev) > maxOf(5f, prev * 0.1f)
            if (big && now - lastLuxReport > LUX_REPORT_GAP_MS) {
                lastLuxReport = now
                app.esp.refresh()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { device.networkUpSince = SystemClock.elapsedRealtime() }
    }

    override fun onCreate() {
        super.onCreate()
        app = HostApp.of(this)
        power = getSystemService(POWER_SERVICE) as PowerManager
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        startForeground(1, notification())

        Log.i(TAG, "Harmonium Host ${app.versionName} service starting")
        device = RemoteDevice(this, app.esp)
        app.device = device
        app.esp.entities = device.entities
        app.esp.haSubscriptions = { device.haSubscriptions() }
        app.esp.onHaState = { id, attr, state -> device.onHaState(id, attr, state) }
        app.esp.start()

        val sticky = ContextCompat.registerReceiver(this, events, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        // BATTERY_CHANGED is sticky: the current state comes back from registerReceiver.
        sticky?.let { events.onReceive(this, it) }
        (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(network)

        // High-perf only while the screen is on (keys land fast); normal power save while it's off.
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "harmoniumhost:wifi")
            .apply { setReferenceCounted(false) }

        screen(power.isInteractive)
        applySettings()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELOAD) {
            applySettings()
            if (intent.getBooleanExtra(EXTRA_ESP_CHANGED, false)) app.esp.reload()
            app.esp.refresh()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(events)
        sensors.unregisterListener(proximityListener)
        sensors.unregisterListener(lightListener)
        try {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(network)
        } catch (e: Exception) {}
        wifiLock?.release()
        app.esp.stop()
        super.onDestroy()
    }

    private fun screen(on: Boolean) {
        Log.i(TAG, "screen ${if (on) "on" else "off"}")
        if (on) {
            wifiLock?.acquire()
            sensors.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
                sensors.registerListener(lightListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            HostState.fire(HostState.Event.SCREEN_ON)
        } else {
            wifiLock?.release()
            sensors.unregisterListener(lightListener)
        }
        app.esp.refresh()
    }

    // ---------- proximity ----------

    private fun applySettings() {
        val want = app.prefs.proximityWake
        if (want && proximity == null) {
            // The only wake-up sensor on the HA100, and on-change: costs almost nothing idle.
            proximity = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
                ?: sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            val p = proximity
            if (p == null) {
                Log.w(TAG, "no proximity sensor")
            } else {
                sensors.registerListener(proximityListener, p, SensorManager.SENSOR_DELAY_NORMAL)
                Log.i(TAG, "proximity wake on: ${p.name}, wakeUp=${p.isWakeUpSensor}, range=${p.maximumRange}")
            }
        } else if (!want && proximity != null) {
            sensors.unregisterListener(proximityListener)
            proximity = null
            Log.i(TAG, "proximity wake off")
        }
    }

    // ---------- helpers ----------

    /** Lights the screen; after [holdMs] the normal screen timeout takes over again. */
    @Suppress("DEPRECATION") // still the simplest way to light the screen from a service on Android 8.1
    private fun wakeScreen(holdMs: Long) {
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "harmoniumhost:wake"
        ).acquire(holdMs)
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Remote host", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Harmonium Host")
            .build()
    }
}

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
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Always-running companion to MainActivity:
 *  - on the cradle: wake briefly and show a charging screen
 *  - off the cradle: wake immediately (you're about to use it)
 *  - pickup detection: wake before your thumb reaches a button
 *  - Wi-Fi lock: keep the HA connection warm through screen-off
 */
class HostService : Service() {

    companion object {
        const val ACTION_CHARGING = "com.example.harmoniumhost.CHARGING"
        const val ACTION_UNPLUGGED = "com.example.harmoniumhost.UNPLUGGED"
        const val EXTRA_LEVEL = "level"
        private const val TAG = "HarmoniumHost"
        private const val CHANNEL = "host"
        private const val PICKUP_THRESHOLD = 2.5f   // m/s², summed across axes. Tune on the device.
    }

    private lateinit var power: PowerManager
    private var wifiLock: WifiManager.WifiLock? = null
    private var sensors: SensorManager? = null

    // Must be registered at runtime: since Android 8.0, manifest receivers don't get POWER_CONNECTED.
    private val powerEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> { wakeScreen(4_000); broadcast(ACTION_CHARGING) }
                Intent.ACTION_POWER_DISCONNECTED -> { wakeScreen(15_000); broadcast(ACTION_UNPLUGGED) }
            }
        }
    }

    private val pickup = object : SensorEventListener {
        private val last = FloatArray(3)
        private var primed = false
        override fun onSensorChanged(e: SensorEvent) {
            val delta = abs(e.values[0] - last[0]) + abs(e.values[1] - last[1]) + abs(e.values[2] - last[2])
            e.values.copyInto(last, endIndex = 3)
            if (primed && delta > PICKUP_THRESHOLD && !power.isInteractive) {
                Log.i(TAG, "pickup detected (delta=$delta), waking")
                wakeScreen(10_000)
            }
            primed = true
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        power = getSystemService(POWER_SERVICE) as PowerManager
        startForeground(1, notification())

        ContextCompat.registerReceiver(this, powerEvents, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Keeps Wi-Fi out of power-save so the websocket survives screen-off. Measure battery impact.
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "harmoniumhost:wifi")
            .apply { setReferenceCounted(false); acquire() }

        // EXPERIMENTAL pickup-to-wake. Only a *wake-up* accelerometer delivers events while the SoC
        // sleeps. It costs battery (it wakes the CPU to deliver each batch), so measure it and consider
        // enabling it only while an activity is running and the remote is off the cradle.
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        if (accel != null) {
            sensors?.registerListener(pickup, accel, SensorManager.SENSOR_DELAY_NORMAL, 500_000)
            Log.i(TAG, "pickup wake using ${accel.name}")
        } else {
            Log.w(TAG, "no wake-up accelerometer; check `dumpsys sensorservice` for a tilt/pickup sensor")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(powerEvents)
        sensors?.unregisterListener(pickup)
        wifiLock?.release()
        super.onDestroy()
    }

    @Suppress("DEPRECATION") // still the simplest way to light the screen from a service on Android 8.1
    private fun wakeScreen(holdMs: Long) {
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "harmoniumhost:wake"
        ).acquire(holdMs)
    }

    private fun broadcast(action: String) {
        val level = (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        sendBroadcast(Intent(action).setPackage(packageName).putExtra(EXTRA_LEVEL, level))
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

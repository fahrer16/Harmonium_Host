package com.example.harmoniumhost

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/** Lets Home Assistant really turn the screen off (DevicePolicyManager.lockNow). Enabled in Settings. */
class ScreenOffAdmin : DeviceAdminReceiver()

/**
 * Everything this remote exposes to Home Assistant besides the voice satellite: controls, the
 * screenshot camera, the remote's settings (the same values as its Settings screen), and diagnostics. Keys are stable ids; don't renumber them (HA keys its
 * entities by object_id, but the key has to stay consistent within a connection).
 */
class RemoteDevice(private val context: Context, private val esp: EspServer) {

    private companion object {
        const val TAG = "HarmoniumHost"
        const val MB = 1024f * 1024f
        const val GB = MB * 1024f
    }

    private val prefs = HostApp.of(context).prefs
    private val main = Handler(Looper.getMainLooper())
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val activities = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val policy = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(context, ScreenOffAdmin::class.java)
    private val startedAt = SystemClock.elapsedRealtime()
    /** Set by HostService when Wi-Fi comes up. */
    @Volatile var networkUpSince = SystemClock.elapsedRealtime()
    private var cpuLast: Pair<Long, Long>? = null   // (busy, total) jiffies, or app cpu ms / wall ms

    /** New versions from GitHub, offered as HA's update entity. */
    val updater = AppUpdater(context) { esp.refresh() }

    /** The whole screen (any app) when Android's capture was allowed, else this app's own window. */
    val screenshotCamera = EspCamera(30, "screenshot", "Screenshot", "mdi:cellphone-screenshot",
        capture = { ScreenCapture.capture(context) ?: HostState.screenshot?.invoke() })

    /** HA calls this back with its weather and activity entities (see [requestEntityLists]). */
    val services = listOf(EspService(200, "entity_lists", listOf("weather", "activity")) { args ->
        gotEntityLists(args["weather"].orEmpty(), args["activity"].orEmpty())
    })

    val entities: List<EspEntity> = listOf(
        // ---- controls ----
        EspSwitch(10, "screen", "Screen", "mdi:tablet-dashboard",
            read = { power.isInteractive }, write = { on -> main.post { if (on) wake() else screenOff() } }),
        EspSwitch(11, "screensaver", "Screensaver", "mdi:image-filter-hdr",
            read = { HostState.screensaverOn },
            write = { on -> HostState.fire(if (on) HostState.Event.SCREENSAVER_ON else HostState.Event.SCREENSAVER_OFF) }),
        settingSelect(12, "screensaver_mode", "screensaver_mode", "Screensaver mode", "mdi:palette",
            { HostPrefs.SCREENSAVER_MODES }) { prefs.screensaverMode },
        EspSwitch(13, "adaptive_brightness", "Adaptive brightness", "mdi:brightness-auto", EspEntity.CAT_CONFIG,
            read = { systemInt(Settings.System.SCREEN_BRIGHTNESS_MODE)?.let { it == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC } },
            write = { on -> putSystemInt(Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) }),
        EspNumber(14, "brightness", "Screen brightness", "mdi:brightness-6", EspEntity.CAT_CONFIG,
            min = 1f, max = 100f, step = 1f, unit = "%",
            read = { systemInt(Settings.System.SCREEN_BRIGHTNESS)?.let { (it * 100f / 255f).roundToInt().toFloat() } },
            write = { pct -> putSystemInt(Settings.System.SCREEN_BRIGHTNESS, (pct.coerceIn(1f, 100f) * 255f / 100f).roundToInt()) }),
        EspNumber(15, "volume", "Volume", "mdi:volume-high", EspEntity.CAT_CONFIG,
            min = 0f, max = 100f, step = 1f, unit = "%",
            read = {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt().toFloat()
            },
            write = { pct ->
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (pct.coerceIn(0f, 100f) * max / 100f).roundToInt(), 0)
            }),
        EspButton(20, "bring_to_front", "Bring to front", "mdi:open-in-app", press = { bringToFront() }),
        EspButton(21, "reload", "Reload page", "mdi:reload", press = { HostState.fire(HostState.Event.RELOAD) }),
        EspButton(22, "clear_cache", "Clear cache", "mdi:broom", EspEntity.CAT_CONFIG,
            press = { HostState.fire(HostState.Event.CLEAR_CACHE) }),
        EspButton(23, "restart_app", "Restart app", "mdi:restart", EspEntity.CAT_CONFIG, press = { restartApp() }),
        EspButton(24, "take_screenshot", "Take screenshot", "mdi:camera", press = { takeScreenshot() }),
        EspButton(25, "open_settings", "Open settings on the remote", "mdi:cog", EspEntity.CAT_CONFIG,
            press = { main.post { openSettings() } }),
        screenshotCamera,

        // ---- sensors ----
        EspSensor(40, "battery", "Battery", unit = "%", deviceClass = "battery",
            read = { HostState.batteryLevel.takeIf { it >= 0 }?.toFloat() }),
        EspBinarySensor(41, "charging", "Charging", deviceClass = "battery_charging", read = { HostState.charging }),
        EspBinarySensor(44, "activity_running", "Activity running", "mdi:remote-tv", read = { HostState.activityRunning }),

        // ---- diagnostics ----
        EspTextSensor(60, "android_version", "Android version", "mdi:android", EspEntity.CAT_DIAGNOSTIC,
            read = { "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" }),
        EspTextSensor(61, "app_version", "App version", "mdi:information-outline", EspEntity.CAT_DIAGNOSTIC,
            read = { HostApp.of(context).versionName }),
        EspSensor(62, "app_uptime", "App uptime", "mdi:timer-outline", EspEntity.CAT_DIAGNOSTIC,
            unit = "s", deviceClass = "duration", read = { (SystemClock.elapsedRealtime() - startedAt) / 1000f }),
        EspBinarySensor(63, "connected", "Connected", category = EspEntity.CAT_DIAGNOSTIC,
            deviceClass = "connectivity", read = { wifi.connectionInfo?.networkId?.let { it != -1 } }),
        EspSensor(64, "cpu", "CPU usage", "mdi:cpu-32-bit", EspEntity.CAT_DIAGNOSTIC, unit = "%", read = { cpuPercent() }),
        EspTextSensor(65, "page_url", "Current page", "mdi:web", EspEntity.CAT_DIAGNOSTIC, read = { HostState.pageUrl }),
        EspTextSensor(66, "device_name", "Device name", "mdi:remote", EspEntity.CAT_DIAGNOSTIC,
            read = { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }),
        EspTextSensor(67, "foreground_app", "Foreground app", "mdi:application", EspEntity.CAT_DIAGNOSTIC,
            read = { foregroundApp() }),
        EspSensor(68, "storage_free", "Internal storage free", "mdi:harddisk", EspEntity.CAT_DIAGNOSTIC,
            unit = "GB", deviceClass = "data_size", decimals = 2, read = { storage().first }),
        EspSensor(69, "storage_total", "Internal storage total", "mdi:harddisk", EspEntity.CAT_DIAGNOSTIC,
            unit = "GB", deviceClass = "data_size", decimals = 2, read = { storage().second }),
        EspTextSensor(70, "ip", "IPv4 address", "mdi:ip-network", EspEntity.CAT_DIAGNOSTIC, read = { ipAddress() }),
        EspSensor(72, "network_uptime", "Network uptime", "mdi:wifi", EspEntity.CAT_DIAGNOSTIC,
            unit = "s", deviceClass = "duration", read = { (SystemClock.elapsedRealtime() - networkUpSince) / 1000f }),
        EspSensor(73, "ram_available", "RAM available", "mdi:memory", EspEntity.CAT_DIAGNOSTIC,
            unit = "MB", deviceClass = "data_size", read = { memory().availMem / MB }),
        EspSensor(74, "ram_total", "RAM total", "mdi:memory", EspEntity.CAT_DIAGNOSTIC,
            unit = "MB", deviceClass = "data_size", read = { memory().totalMem / MB }),
        EspSensor(75, "wifi_signal", "Wi-Fi signal", category = EspEntity.CAT_DIAGNOSTIC,
            unit = "dBm", deviceClass = "signal_strength", read = { wifi.connectionInfo?.rssi?.toFloat() }),
        // Java heap in use: Android caps this app at 128 MB on the HA100; steady growth = a leak.
        EspSensor(76, "app_memory", "App memory", "mdi:memory", EspEntity.CAT_DIAGNOSTIC, unit = "MB",
            deviceClass = "data_size", read = { Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / MB } }),

        // ---- settings: the Settings screen's values, editable from HA (not the token, device names or key rules) ----
        EspText(80, "ha_url", "Home Assistant URL", "mdi:home-assistant", EspEntity.CAT_CONFIG,
            read = { prefs.haUrl },
            write = { v -> saveSetting(reloadsPage = true) {
                putOrRemove("ha_url", v)
                putBoolean("harmonium_provisioned", false)       // hand host/token to Harmonium again
            } }),
        settingText(81, "harmonium_path", "harmonium_path", "Harmonium page path", "mdi:file-link",
            reloadsPage = true) { prefs.harmoniumPath },
        settingSelect(82, "harmonium_profile", "harmonium_profile", "Remote profile", "mdi:remote",
            { listOf("astrion", "astrion2", "none") }, reloadsPage = true) { prefs.harmoniumProfile.ifEmpty { "none" } },
        settingText(83, "start_page", "start_page", "Start page", "mdi:home-outline",
            reloadsPage = true) { prefs.startPage },
        settingSelect(84, "activity_entity", "activity_entity", "Activity entity", "mdi:remote-tv",
            { withCurrent(prefs.haActivityEntities, prefs.activityEntity) }) { prefs.activityEntity },
        settingText(85, "idle_states", "idle_states", "Idle activity states", "mdi:power-sleep") {
            prefs.rawString("idle_states") ?: "off" },
        settingSelect(86, "weather_entity", "weather_entity", "Weather entity", "mdi:weather-partly-cloudy",
            { listOf("none") + withCurrent(prefs.haWeatherEntities, prefs.weatherEntity) }) {
            prefs.weatherEntity.ifEmpty { "none" } },
        settingSelect(87, "keep_awake", "keep_awake", "Keep screen on", "mdi:cellphone-lock",
            { HostPrefs.KEEP_MODES }) { prefs.keepAwake },
        settingNumber(88, "dim_after", "dim_after_s", "Dim after", "mdi:brightness-4",
            0, 3600, unit = "s") { prefs.dimAfterSec },
        settingNumber(89, "dim_level", "dim_level_pct", "Dimmed brightness", "mdi:brightness-5",
            0, 100, unit = "%", mode = 2) { prefs.dimLevelPct },
        settingNumber(90, "keep_on_limit", "awake_limit_min", "Keep-on limit", "mdi:timer-sand",
            0, 1440, unit = "min") { prefs.awakeLimitMin },
        settingSwitch(91, "cradle_no_limit", "cradle_no_limit", "No keep-on limit on the cradle",
            "mdi:power-plug") { prefs.cradleNoLimit },
        settingSwitch(92, "proximity_wake", "proximity_wake", "Proximity wake", "mdi:hand-wave") { prefs.proximityWake },
        settingSwitch(100, "lift_wake", "lift_wake", "Wake when picked up", "mdi:hand-back-right") { prefs.liftWake },
        EspSwitch(101, "wireless_adb", "Wireless ADB", "mdi:android-debug-bridge", EspEntity.CAT_CONFIG,
            read = { WirelessAdb.on }, write = { on -> WirelessAdb.set(on) }),
        EspUpdate(110, "app_update", "Harmonium Host", "mdi:update", EspEntity.CAT_CONFIG,
            read = { updater.info() }, command = { updater.command(it) }),
        settingSwitch(93, "screensaver_when_dimmed", "screensaver_when_dimmed", "Screensaver when dimmed",
            "mdi:image-filter-hdr") { prefs.screensaverWhenDimmed },
        settingNumber(94, "charging_screen", "charge_screen_s", "Charging screen", "mdi:battery-charging",
            0, 60, unit = "s") { prefs.chargeScreenSec },
        settingSwitch(95, "battery_readout", "status_show", "Battery readout", "mdi:battery") { prefs.statusShow },
        settingSelect(96, "battery_readout_position", "status_pos", "Battery readout position",
            "mdi:format-align-center", { listOf("center", "left", "right") }) { prefs.statusPos },
        settingNumber(97, "battery_readout_size", "status_size_sp", "Battery readout size", "mdi:format-size",
            6, 24, unit = "sp") { prefs.statusSizeSp },
        settingNumber(98, "long_press", "long_press_ms", "Long press", "mdi:gesture-tap-hold",
            200, 3000, step = 50, unit = "ms") { prefs.longPressMs },
        EspNumber(99, "screen_timeout", "Android screen timeout", "mdi:timer-outline", EspEntity.CAT_CONFIG,
            min = 5f, max = 3600f, step = 1f, unit = "s", mode = 1,
            read = { systemInt(Settings.System.SCREEN_OFF_TIMEOUT)?.let { it / 1000f } },
            write = { s -> putSystemInt(Settings.System.SCREEN_OFF_TIMEOUT, s.roundToInt().coerceIn(5, 3600) * 1000) }),
    )

    /** Entity states HA should forward to the remote: the activity select and the weather. */
    fun haSubscriptions(): List<Pair<String, String>> = buildList {
        add(prefs.activityEntity to "")
        prefs.weatherEntity.takeIf { it.isNotEmpty() }?.let {
            add(it to "")
            add(it to "temperature")
            add(it to "temperature_unit")
        }
    }

    fun onHaState(entityId: String, attribute: String, state: String) {
        when {
            entityId == prefs.activityEntity && attribute.isEmpty() -> {
                Log.i(TAG, "activity: $state")
                HostState.setActivity(state, prefs.idleStates)
            }
            entityId == prefs.weatherEntity -> when (attribute) {
                "" -> HostState.setWeather(condition = state)
                "temperature" -> HostState.setWeather(temperature = state)
                "temperature_unit" -> HostState.setWeather(unit = state)
            }
        }
    }

    // ---------- settings ----------

    /** Saves, then has the service and the activity re-apply everything (and reload the page if asked). */
    private fun saveSetting(reloadsPage: Boolean = false, change: SharedPreferences.Editor.() -> Unit) {
        prefs.edit {
            change()
            if (reloadsPage) putBoolean("reload_pending", true)
        }
        Log.i(TAG, "setting changed from Home Assistant")
        main.post {
            HostService.reload(context)
            HostState.fire(HostState.Event.SETTINGS_CHANGED)
        }
    }

    /** Blank = back to the default, like an empty field in Settings. */
    private fun SharedPreferences.Editor.putOrRemove(pref: String, value: String) {
        value.trim().let { if (it.isEmpty()) remove(pref) else putString(pref, it) }
    }

    private fun settingText(key: Int, id: String, pref: String, name: String, icon: String,
                            reloadsPage: Boolean = false, read: () -> String) =
        EspText(key, id, name, icon, EspEntity.CAT_CONFIG, read = read,
            write = { v -> saveSetting(reloadsPage) { putOrRemove(pref, v) } })

    private fun settingSelect(key: Int, id: String, pref: String, name: String, icon: String,
                              options: () -> List<String>, reloadsPage: Boolean = false, read: () -> String) =
        EspSelect(key, id, name, icon, EspEntity.CAT_CONFIG, options, read = read,
            write = { v -> if (v in options()) saveSetting(reloadsPage) { putString(pref, v) } })

    /** An entity list from HA, plus the current value if HA didn't list it (so it stays selectable). */
    private fun withCurrent(list: List<String>, current: String) =
        if (current.isEmpty() || current in list) list else list + current

    /** [mode]: 1 = a box to type in, 2 = a slider. */
    private fun settingNumber(key: Int, id: String, pref: String, name: String, icon: String,
                              min: Int, max: Int, step: Int = 1, unit: String, mode: Int = 1, read: () -> Int) =
        EspNumber(key, id, name, icon, EspEntity.CAT_CONFIG, min.toFloat(), max.toFloat(), step.toFloat(), unit,
            mode = mode, read = { read().toFloat() },
            write = { v -> saveSetting { putInt(pref, v.roundToInt().coerceIn(min, max)) } })

    private fun settingSwitch(key: Int, id: String, pref: String, name: String, icon: String, read: () -> Boolean) =
        EspSwitch(key, id, name, icon, EspEntity.CAT_CONFIG, read = read,
            write = { on -> saveSetting { putBoolean(pref, on) } })

    // ---------- entity lists from HA ----------

    /**
     * The ESPHome API can't list HA's entities, but HA renders templates in actions a device asks
     * for. So on each connection the remote asks HA to call its own `entity_lists` service with the
     * weather entities and activity selects; HA's dropdowns are built from what comes back. Needs
     * the device option "Allow the device to perform Home Assistant actions" (off: HA raises a
     * repair, and the dropdowns only offer the current value).
     */
    fun requestEntityLists() {
        esp.callHaAction("esphome." + prefs.espName.replace('-', '_') + "_entity_lists", mapOf(
            "weather" to "{{ states.weather | map(attribute='entity_id') | join(',') }}",
            "activity" to "{{ states.select | map(attribute='entity_id') | select('search', '_activity\$') | join(',') }}",
        ))
    }

    private fun gotEntityLists(weather: String, activity: String) {
        fun parse(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        val w = parse(weather)
        val a = parse(activity)
        Log.i(TAG, "entity lists from HA: ${w.size} weather, ${a.size} activity")
        if (w == prefs.haWeatherEntities && a == prefs.haActivityEntities) return
        prefs.haWeatherEntities = w
        prefs.haActivityEntities = a
        // Select options are sent when HA lists the entities; reconnecting makes HA list them again.
        main.postDelayed({ esp.reload() }, 1_000)
    }

    // ---------- actions ----------

    private fun takeScreenshot() {
        if (ScreenCapture.ready) esp.pushImage(screenshotCamera)
        else main.post { ScreenCapture.requestConsent(context) { esp.pushImage(screenshotCamera) } }
    }

    @Suppress("DEPRECATION") // simplest way to light the screen from a service on 8.1
    fun wake() {
        power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "harmoniumhost:ha").acquire(5_000)
    }

    val canTurnScreenOff get() = policy.isAdminActive(admin)

    private fun screenOff() {
        if (canTurnScreenOff) policy.lockNow()
        else HostState.fire(HostState.Event.SCREEN_BLACK)   // best effort without device-admin rights
    }

    private fun bringToFront() {
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun openSettings() {
        Log.i(TAG, "opening Settings (from Home Assistant)")
        wake()
        try {
            context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "can't open Settings: $e")
        }
    }

    private fun restartApp() {
        val intent = PendingIntent.getActivity(context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .set(AlarmManager.RTC, System.currentTimeMillis() + 1_500, intent)
        Log.i(TAG, "restarting")
        main.postDelayed({ Process.killProcess(Process.myPid()) }, 300)
    }

    // ---------- readings ----------

    private fun systemInt(name: String): Int? =
        try { Settings.System.getInt(context.contentResolver, name) } catch (e: Exception) { null }

    private fun putSystemInt(name: String, value: Int) {
        if (Settings.System.canWrite(context)) Settings.System.putInt(context.contentResolver, name, value)
        else Log.w(TAG, "can't change $name: allow \"Modify system settings\" in the remote's Settings")
    }

    private fun memory() = ActivityManager.MemoryInfo().also { activities.getMemoryInfo(it) }

    private fun storage(): Pair<Float, Float> {
        val fs = StatFs(Environment.getDataDirectory().path)
        return fs.availableBytes / GB to fs.totalBytes / GB
    }

    @Suppress("DEPRECATION")
    private fun ipAddress(): String? {
        val ip = wifi.connectionInfo?.ipAddress ?: 0
        if (ip == 0) return null
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    /** Whole-device CPU from /proc/stat when readable; Android 8 usually blocks it, so fall back to this app's share. */
    private fun cpuPercent(): Float? {
        val sample = try {
            RandomAccessFile("/proc/stat", "r").use { f ->
                val v = f.readLine().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                val idle = v.getOrElse(3) { 0 } + v.getOrElse(4) { 0 }
                (v.sum() - idle) to v.sum()
            }
        } catch (e: Exception) {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            Process.getElapsedCpuTime() to SystemClock.elapsedRealtime() * cores
        }
        val last = cpuLast
        cpuLast = sample
        if (last == null) return null
        val total = sample.second - last.second
        return if (total > 0) ((sample.first - last.first) * 100f / total).coerceIn(0f, 100f) else null
    }

    private var foreground: String? = null
    private var foregroundCheckedTo = 0L

    /**
     * The package in front, from Android's usage events (needs "Usage access"; the remote asks
     * for it). Only the events since the last check are read, so each check is cheap; the first
     * looks back a day, since the app in front may have been opened long ago. Writer thread only.
     */
    private fun foregroundApp(): String {
        if (!SystemAccess.hasUsageAccess(context)) {
            return if (HostState.appInForeground) context.packageName else "unknown (allow Usage access on the remote)"
        }
        try {
            val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usage.queryEvents(if (foregroundCheckedTo == 0L) now - 24 * 3600_000L else foregroundCheckedTo, now)
            foregroundCheckedTo = now
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) foreground = e.packageName
            }
        } catch (e: Exception) {
            Log.w(TAG, "usage events: $e")
        }
        return foreground ?: if (HostState.appInForeground) context.packageName else "unknown"
    }
}

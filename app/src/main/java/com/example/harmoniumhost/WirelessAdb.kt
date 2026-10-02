package com.example.harmoniumhost

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Wireless ADB on/off, like Harmonium's Key Mapper setup (Blue key): sets adbd's TCP port and
 * restarts it. Normally that needs root; it works here because the HA100 firmware runs SELinux
 * in permissive mode. It doesn't survive a reboot (the port setting clears), so Settings can
 * turn it back on whenever the app starts. Call off the main thread.
 *
 * Anyone on the network can then use ADB on the remote: install apps, run commands.
 */
object WirelessAdb {
    const val PORT = 5555
    private const val TAG = "HarmoniumHost"

    /** The TCP port adbd listens on, or -1 (USB only). */
    fun port(): Int = prop("service.adb.tcp.port").toIntOrNull() ?: -1
    val on get() = port() > 0

    /** USB debugging is the master switch: without it there is no adbd to listen at all. */
    fun usbDebugging(c: Context) =
        Settings.Global.getInt(c.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    /** Returns whether the change took. */
    fun set(on: Boolean): Boolean {
        if (on == this.on) return true
        sh("setprop service.adb.tcp.port ${if (on) PORT else -1}")
        sh("setprop ctl.restart adbd")
        Thread.sleep(500)
        val ok = this.on == on
        Log.i(TAG, "wireless ADB ${if (on) "on" else "off"}: ${if (ok) "done" else "refused by this firmware"}")
        return ok
    }

    private fun prop(key: String): String = try {
        // SystemProperties is hidden but readable on Android 8.1; cheaper than starting a process.
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            .invoke(null, key, "") as String
    } catch (e: Exception) {
        sh("getprop $key").trim()
    }

    private fun sh(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    } catch (e: Exception) {
        Log.w(TAG, "$cmd: $e"); ""
    }
}

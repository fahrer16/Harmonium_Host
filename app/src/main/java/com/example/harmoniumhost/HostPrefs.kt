package com.example.harmoniumhost

import android.content.Context
import android.content.Intent

/**
 * Per-remote settings. No settings UI yet: provision each remote over adb, e.g.
 *
 *   adb shell am start -n com.example.harmoniumhost/.MainActivity \
 *     --es token "<long-lived token>" --es room great_room --es apple_tv great_room
 *
 * Any subset of keys can be sent later to change just those.
 */
class HostPrefs(context: Context) {
    private val sp = context.getSharedPreferences("host", Context.MODE_PRIVATE)

    private fun str(key: String, default: String = "") =
        sp.getString(key, null)?.takeIf { it.isNotBlank() } ?: default

    val haUrl get() = str("ha_url", "http://192.168.100.10:8123").trimEnd('/')
    val token get() = str("token")
    val room get() = str("room", "great_room")

    /** Name or identifier from the Apple TV's "Voice Url" sensor: /api/appletv_siri/audio/<this> */
    val appleTv get() = str("apple_tv", room)

    /** Assist pipeline id for the non-Siri route. Blank = HA's preferred pipeline. */
    val pipelineId get() = str("pipeline")

    /** Same path Fully Kiosk loads today, without the host. */
    val harmoniumPath get() = str("harmonium_path", "/local/harmonium/main/index.html")

    /** The "bit" HA flips to send this remote's voice to Siri. */
    val siriEntity get() = str("siri_entity", "input_boolean.remote_siri_$room")

    var harmoniumProvisioned: Boolean
        get() = sp.getBoolean("harmonium_provisioned", false)
        set(v) { sp.edit().putBoolean("harmonium_provisioned", v).apply() }

    /** Absorbs `--es key value` extras from an adb launch. Returns true if anything changed. */
    fun absorbProvisioning(intent: Intent?): Boolean {
        val extras = intent?.extras ?: return false
        val keys = listOf("ha_url", "token", "room", "apple_tv", "pipeline", "harmonium_path", "siri_entity")
        val edit = sp.edit()
        var changed = false
        for (k in keys) extras.getString(k)?.let { edit.putString(k, it); changed = true }
        if (extras.containsKey("token") || extras.containsKey("ha_url")) {
            edit.putBoolean("harmonium_provisioned", false)   // re-hand credentials to Harmonium
        }
        edit.apply()
        return changed
    }
}

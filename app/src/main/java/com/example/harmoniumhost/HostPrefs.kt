package com.example.harmoniumhost

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.KeyEvent
import java.util.Random

/**
 * Per-remote settings, edited in SettingsActivity and saved on the remote. Nothing here needs a
 * rebuild: the defaults below only apply until a value is saved.
 *
 * adb provisioning still works for the string keys in [ADB_KEYS], e.g.
 *
 *   adb shell am start -n com.example.harmoniumhost/.MainActivity --es room great_room
 */
class HostPrefs(context: Context) {

    companion object {
        const val KEEP_NEVER = "never"
        const val KEEP_ACTIVITY = "activity"
        const val KEEP_ALWAYS = "always"

        /**
         * What Key Mapper's "FullyKiosk" group emits today (harmonium repo,
         * remotes/astrion/keymapper/v2), so an existing Harmonium config works unchanged.
         * Columns: physical key, short press, long press. "-" = nothing.
         */
        val DEFAULT_KEY_RULES = """
            # key         short press     long press
            BACK          LEFT_BRACKET    RIGHT_BRACKET
            MENU          POUND           AT
            VOLUME_UP     PLUS
            VOLUME_DOWN   MINUS
            VOLUME_MUTE   GRAVE
            DPAD_LEFT     DPAD_LEFT       COMMA
            DPAD_RIGHT    DPAD_RIGHT      PERIOD
            PAGE_UP       PAGE_UP         APOSTROPHE
            PAGE_DOWN     PAGE_DOWN       SLASH
            F1            F1              EQUALS
            F2            F2              F12
        """.trimIndent()

        private val ADB_KEYS = listOf(
            "ha_url", "token", "room", "harmonium_path", "harmonium_profile", "start_page",
            "activity_entity", "esp_name", "esp_friendly_name",
        )
    }

    private val sp = context.getSharedPreferences("host", Context.MODE_PRIVATE)

    private fun str(key: String, default: String = "") =
        sp.getString(key, null)?.takeIf { it.isNotBlank() }?.trim() ?: default
    private fun int(key: String, default: Int) = sp.getInt(key, default)
    private fun bool(key: String, default: Boolean) = sp.getBoolean(key, default)

    fun edit(block: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()
    /** The saved value, or null when the default is in use (Settings shows the default as a hint). */
    fun rawString(key: String) = sp.getString(key, null)

    // ---- Home Assistant ----
    val haUrl get() = str("ha_url", "http://192.168.100.10:8123").trimEnd('/')
    /** Optional: handed to Harmonium once (its own pairing works too). Not used by the native side. */
    val token get() = str("token")
    val room get() = str("room", "great_room")

    // ---- Harmonium ----
    /** Same path Fully Kiosk loads today, without the host. */
    val harmoniumPath get() = str("harmonium_path", "/local/harmonium/main/index.html")
    /** Harmonium remote profile (config.json `remotes.<id>`), passed as `#device=` on every load. */
    val harmoniumProfile get() = str("harmonium_profile", "astrion").let { if (it == "none") "" else it }
    /** Harmonium page id to open on every load (`#page=`). Blank = Harmonium's home. */
    val startPage get() = str("start_page")
    var harmoniumProvisioned: Boolean
        get() = bool("harmonium_provisioned", false)
        set(v) = edit { putBoolean("harmonium_provisioned", v) }
    /** Set by Settings when the page has to be reloaded to pick up a change. */
    var reloadPending: Boolean
        get() = bool("reload_pending", false)
        set(v) = edit { putBoolean("reload_pending", v) }

    /** The Harmonium activity select this remote follows. */
    val activityEntity get() = str("activity_entity", "select.harmonium_${room}_activity")
    /** States of [activityEntity] that mean "nothing is running". unknown/unavailable are always idle. */
    val idleStates: Set<String>
        get() = (str("idle_states", "off").split(',') + listOf("unknown", "unavailable"))
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    // ---- Home Assistant device (ESPHome native API) ----
    /** ESPHome node name: lowercase letters, digits and dashes, at most 31 characters. */
    val espName: String
        get() = str("esp_name", "harmonium-remote-$room").lowercase()
            .replace(Regex("[^a-z0-9-]"), "-").take(31)
    val espFriendlyName get() = str("esp_friendly_name",
        "Harmonium Remote " + room.split('_', '-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
    /** A made-up, stable MAC: HA identifies ESPHome devices by it (apps can't read the real one). */
    val espMac: String
        get() = sp.getString("esp_mac", null) ?: run {
            val b = ByteArray(6).also { Random().nextBytes(it) }
            b[0] = ((b[0].toInt() and 0xFC) or 0x02).toByte()   // locally administered, unicast
            b.joinToString(":") { "%02X".format(it) }.also { mac -> edit { putString("esp_mac", mac) } }
        }

    // ---- Voice ----
    val micKeyCode get() = int("mic_keycode", KeyEvent.KEYCODE_F3)
    val micScanCode get() = int("mic_scancode", -1)

    // ---- Screen and wake ----
    val keepAwake get() = str("keep_awake", KEEP_ACTIVITY)
    val dimAfterSec get() = int("dim_after_s", 15)
    val dimLevelPct get() = int("dim_level_pct", 1)
    /** Keep-awake gives up after this long without a press (0 = never). */
    val awakeLimitMin get() = int("awake_limit_min", 20)
    /** On the cradle, battery isn't a concern: ignore [awakeLimitMin] there. */
    val cradleNoLimit get() = bool("cradle_no_limit", true)
    val proximityWake get() = bool("proximity_wake", true)

    // ---- Charging and status line ----
    val chargeScreenSec get() = int("charge_screen_s", 4)
    val statusShow get() = bool("status_show", true)
    /** left | center | right, along the top edge. */
    val statusPos get() = str("status_pos", "center")
    val statusSizeSp get() = int("status_size_sp", 9)

    // ---- Keys ----
    val keyRules get() = sp.getString("key_rules", null) ?: DEFAULT_KEY_RULES
    val longPressMs get() = int("long_press_ms", 600)

    var setupDone: Boolean
        get() = bool("setup_done", false)
        set(v) = edit { putBoolean("setup_done", v) }

    /** Absorbs `--es key value` extras from an adb launch. Returns true if anything changed. */
    fun absorbProvisioning(intent: Intent?): Boolean {
        val extras = intent?.extras ?: return false
        val edit = sp.edit()
        var changed = false
        for (k in ADB_KEYS) extras.getString(k)?.let { edit.putString(k, it); changed = true }
        if (extras.containsKey("token") || extras.containsKey("ha_url")) {
            edit.putBoolean("harmonium_provisioned", false)   // re-hand credentials to Harmonium
        }
        if (changed) edit.putBoolean("setup_done", true)
        edit.apply()
        return changed
    }
}

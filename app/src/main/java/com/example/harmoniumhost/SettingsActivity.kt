package com.example.harmoniumhost

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Every setting the remote needs, saved on the remote (SharedPreferences via [HostPrefs]).
 * Open it by holding the battery readout at the top of the screen for 3 s, from the launcher
 * ("Harmonium settings"), or with `adb shell am start -n com.example.harmoniumhost/.SettingsActivity`.
 * Plain views built in code: nothing to inflate, and fields are one line each.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var app: HostApp
    private lateinit var prefs: HostPrefs
    private lateinit var list: LinearLayout
    private val savers = ArrayList<(SharedPreferences.Editor) -> Unit>()

    private lateinit var urlField: EditText
    private lateinit var pathField: EditText
    private lateinit var tokenField: EditText
    private lateinit var rulesField: EditText
    private lateinit var timeoutField: EditText
    private lateinit var micLabel: TextView
    private lateinit var statusText: TextView
    private var micCode = 0
    private var micScan = -1
    private var learningMic = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = HostApp.of(this)
        prefs = app.prefs
        micCode = prefs.micKeyCode
        micScan = prefs.micScanCode
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // don't sleep mid-typing
        val pad = dp(16)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 3)
        }
        setContentView(ScrollView(this).apply { addView(list, MATCH_PARENT, WRAP_CONTENT) })

        heading("Harmonium Host ${app.versionName}", 22f)
        buttons("Save" to ::save, "Test connection" to ::test, "Close" to ::finish)
        buttons("Android settings" to { startActivity(Intent(Settings.ACTION_SETTINGS)) },
            "Wi-Fi" to { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) })

        heading("Status")
        statusText = label("")

        heading("Harmonium")
        urlField = text("ha_url", "Home Assistant URL (use the IP address)", prefs.haUrl,
            InputType.TYPE_TEXT_VARIATION_URI)
        pathField = text("harmonium_path", "Harmonium page path", prefs.harmoniumPath)
        choice("harmonium_profile", "Remote profile (Harmonium's remotes.<id>)",
            listOf("astrion" to "astrion", "astrion2" to "astrion2 (transport keys)",
                "none" to "none (Harmonium decides)"),
            prefs.rawString("harmonium_profile") ?: "astrion")
        text("start_page", "Start page (Harmonium page id, e.g. great_room)",
            prefs.rawString("start_page") ?: "", hint = "blank = Harmonium's home")
        text("room", "Room id (as in select.harmonium_<room>_activity)", prefs.room)
        text("activity_entity", "Activity entity", prefs.rawString("activity_entity") ?: "",
            hint = "select.harmonium_<room>_activity")
        text("idle_states", "States that mean \"no activity\" (comma separated)",
            prefs.rawString("idle_states") ?: "off")
        tokenField = field("Token for Harmonium (optional)",
            if (prefs.token.isEmpty()) "not set: Harmonium's own pairing is fine" else "saved (type here to replace)",
            "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        heading("Voice assistant (Home Assistant device)")
        note("This remote is an ESPHome device. In Home Assistant: Settings → Devices & services. " +
            "Accept the discovered \"${prefs.espFriendlyName}\", or Add integration → ESPHome → " +
            "host ${ipAddress() ?: "<this remote's IP>"}, port ${EspServer.PORT}. Then open the device " +
            "to set its area and its Assistant (pipeline). Hold the mic button and speak.")
        text("esp_name", "Device name (a-z, 0-9, -)", prefs.rawString("esp_name") ?: "",
            hint = prefs.espName)
        text("esp_friendly_name", "Friendly name", prefs.rawString("esp_friendly_name") ?: "",
            hint = prefs.espFriendlyName)
        micLabel = label("")
        showMic()
        buttons("Learn mic button" to {
            learningMic = true
            micLabel.text = "Press the mic button now…"
        })

        heading("Screen and wake")
        choice("keep_awake", "Keep the screen on (dimmed) so the first press always works",
            listOf(HostPrefs.KEEP_ACTIVITY to "While an activity is running",
                HostPrefs.KEEP_ALWAYS to "Always",
                HostPrefs.KEEP_NEVER to "Never (Android's timeout)"),
            prefs.keepAwake)
        number("dim_after_s", "Dim after (seconds without a press, 0 = never dim)", prefs.dimAfterSec)
        number("dim_level_pct", "Dimmed brightness (%)", prefs.dimLevelPct)
        number("awake_limit_min", "Stop keeping it on after (minutes without a press, 0 = no limit)",
            prefs.awakeLimitMin)
        toggle("cradle_no_limit", "No limit while on the cradle", prefs.cradleNoLimit)
        toggle("proximity_wake", "Wake when a hand comes near (proximity sensor)", prefs.proximityWake)
        timeoutField = field("Android screen timeout (seconds; applies when not kept on)", "",
            systemTimeoutSec()?.toString() ?: "", InputType.TYPE_CLASS_NUMBER)

        heading("Charging and battery")
        number("charge_screen_s", "Charging screen (seconds, 0 = off)", prefs.chargeScreenSec)
        toggle("status_show", "Battery % at the top of the screen", prefs.statusShow)
        choice("status_pos", "Position", listOf("center" to "Top centre", "left" to "Top left",
            "right" to "Top right (overlaps Harmonium's ⓘ)"), prefs.statusPos)
        number("status_size_sp", "Text size (sp)", prefs.statusSizeSp)

        heading("Keys")
        note("One rule per line: key, short press, optional long press. Key names are Android " +
            "KeyEvent names without KEYCODE_. Keys not listed pass through unchanged. " +
            "Defaults match the Key Mapper setup Harmonium documents for the Astrion.")
        rulesField = field("", "", prefs.keyRules,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setHorizontallyScrolling(true)
            minLines = 6
        }
        savers += { it.putString("key_rules", rulesField.text.toString()) }
        buttons("Restore default keys" to { rulesField.setText(HostPrefs.DEFAULT_KEY_RULES) })
        number("long_press_ms", "Long press (ms)", prefs.longPressMs)

        heading("More")
        buttons("Reload Harmonium" to {
            prefs.reloadPending = true
            finish()
        }, "Android settings" to {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }, "Stock remote app" to {
            packageManager.getLaunchIntentForPackage("com.aiks.HaRemote")?.let { startActivity(it) }
                ?: toast("com.aiks.HaRemote isn't installed")
        })

        heading("")
        buttons("Save" to ::save, "Close" to ::finish)
    }

    override fun onResume() {
        super.onResume()
        showStatus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (learningMic && event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) {
            learningMic = false
            micCode = if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN) 0 else event.keyCode
            micScan = event.scanCode
            showMic()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- actions ----------

    private fun save() {
        val problems = KeyRemap.problems(rulesField.text.toString())
        if (problems.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("Key rules")
                .setMessage(problems.joinToString("\n") + "\n\nThose lines will be ignored.")
                .setPositiveButton("Save anyway") { _, _ -> commit() }
                .setNegativeButton("Edit", null)
                .show()
        } else commit()
    }

    private fun commit() {
        val before = harmoniumInputs()
        val espBefore = espInputs()
        prefs.edit {
            savers.forEach { it(this) }
            tokenField.text.toString().trim().takeIf { it.isNotEmpty() }?.let {
                putString("token", it)
                putBoolean("harmonium_provisioned", false)    // hand it to Harmonium on the next load
            }
            putInt("mic_keycode", micCode).putInt("mic_scancode", micScan)
            putBoolean("setup_done", true)
        }
        if (harmoniumInputs() != before) {
            if (harmoniumInputs()[0] != before[0]) prefs.harmoniumProvisioned = false   // new host
            prefs.reloadPending = true
        }
        setSystemTimeout()
        HostService.reload(this, espChanged = espInputs() != espBefore)
        toast("Saved")
        finish()
    }

    private fun harmoniumInputs() =
        listOf(prefs.haUrl, prefs.harmoniumProfile, prefs.harmoniumPath, prefs.startPage, prefs.token)
    private fun espInputs() = listOf(prefs.espName, prefs.espFriendlyName, prefs.activityEntity)

    /** Checks that the Harmonium page loads from the URL typed so far. */
    private fun test() {
        val url = urlField.text.toString().trim().trimEnd('/').ifEmpty { prefs.haUrl } +
            pathField.text.toString().trim().ifEmpty { prefs.harmoniumPath }
        statusText.text = "Testing $url …"
        thread(name = "settings-test") {
            val line = try {
                app.http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (r.isSuccessful) "✓ Harmonium page loads ($url)"
                        else "✗ $url returned ${r.code}. Check the URL and page path."
                    }
            } catch (ex: Exception) {
                "✗ Can't reach $url: ${ex.message}"
            }
            runOnUiThread { statusText.text = line + "\n\n" + statusSummary() }
        }
    }

    private fun systemTimeoutSec(): Int? = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) / 1000
    } catch (e: Exception) { null }

    private fun setSystemTimeout() {
        val want = timeoutField.text.toString().trim().toIntOrNull() ?: return
        if (want <= 0 || want == systemTimeoutSec()) return
        if (Settings.System.canWrite(this)) {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, want * 1000)
        } else {
            toast("Allow \"modify system settings\", then save again")
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun showMic() {
        micLabel.text = "Mic button: " + when {
            micCode > 0 -> KeyEvent.keyCodeToString(micCode).removePrefix("KEYCODE_") + " ($micCode)"
            micScan > 0 -> "scan code $micScan"
            else -> "not set"
        }
    }

    private fun showStatus() { statusText.text = statusSummary() }

    private fun statusSummary(): String {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val rules = KeyRemap.problems(prefs.keyRules)
        return listOf(
            "Battery: ${HostState.batteryLevel}%" + if (HostState.charging) ", charging" else "",
            "Home Assistant (ESPHome): " + when {
                HostState.voiceReady -> "connected, voice ready"
                HostState.haConnected -> "connected, voice not subscribed yet"
                else -> "not connected. Add the device in HA (see Voice assistant below)."
            },
            "This remote: ${ipAddress() ?: "no Wi-Fi address"}, port ${EspServer.PORT}, name ${prefs.espName}",
            "Activity (${prefs.activityEntity}): ${HostState.activity ?: "unknown"}" +
                if (HostState.activityRunning) " → keeping screen on" else "",
            "Last proximity event: ${HostState.lastProximity.ifEmpty { "none yet" }}",
            "Microphone permission: " + if (mic) "granted" else "NOT granted",
            if (rules.isEmpty()) "Key rules: OK" else "Key rules: ${rules.size} problem(s)",
        ).joinToString("\n")
    }

    @Suppress("DEPRECATION") // WifiInfo.ipAddress is the simple way on API 27
    private fun ipAddress(): String? {
        val ip = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).connectionInfo?.ipAddress ?: 0
        if (ip == 0) return null
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    // ---------- tiny view builders ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun heading(text: String, size: Float = 17f) {
        list.addView(TextView(this).apply {
            this.text = text
            textSize = size
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(4))
        })
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, dp(4), 0, dp(4))
    }.also { list.addView(it) }

    private fun note(text: String) {
        list.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            alpha = 0.7f
            setPadding(0, 0, 0, dp(6))
        })
    }

    private fun field(title: String, hint: String, value: String, type: Int): EditText {
        if (title.isNotEmpty()) list.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })
        return EditText(this).apply {
            inputType = type
            this.hint = hint
            setText(value)
        }.also { list.addView(it, MATCH_PARENT, WRAP_CONTENT) }
    }

    /** A string setting. Blank saves as "use the default". */
    private fun text(key: String, title: String, value: String,
                     type: Int = InputType.TYPE_CLASS_TEXT, hint: String = ""): EditText {
        val f = field(title, hint, value, InputType.TYPE_CLASS_TEXT or type)
        savers += { e -> f.text.toString().trim().let { if (it.isEmpty()) e.remove(key) else e.putString(key, it) } }
        return f
    }

    private fun number(key: String, title: String, value: Int) {
        val f = field(title, "", value.toString(), InputType.TYPE_CLASS_NUMBER)
        savers += { e -> f.text.toString().trim().toIntOrNull()?.let { e.putInt(key, it) } }
    }

    private fun toggle(key: String, title: String, value: Boolean) {
        val s = Switch(this).apply {
            text = title
            isChecked = value
            setPadding(0, dp(8), 0, dp(8))
        }
        list.addView(s, MATCH_PARENT, WRAP_CONTENT)
        savers += { it.putBoolean(key, s.isChecked) }
    }

    private fun choice(key: String, title: String, options: List<Pair<String, String>>, value: String) {
        list.addView(TextView(this).apply { text = title; textSize = 13f; setPadding(0, dp(8), 0, 0) })
        var selected = options.indexOfFirst { it.first == value }.coerceAtLeast(0)
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                options.map { it.second })
            setSelection(selected)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selected = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        list.addView(spinner, MATCH_PARENT, WRAP_CONTENT)
        savers += { it.putString(key, options[selected].first) }
    }

    private fun buttons(vararg items: Pair<String, () -> Unit>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in items) {
            row.addView(Button(this).apply {
                text = label
                setAllCaps(false)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        list.addView(row, MATCH_PARENT, WRAP_CONTENT)
    }
}

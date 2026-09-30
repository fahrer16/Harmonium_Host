package com.example.harmoniumhost

import android.Manifest
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import com.example.harmoniumhost.HarmoniumStyle as S

/**
 * Every setting the remote needs, saved on the remote (SharedPreferences via [HostPrefs]).
 * Open it by touching and holding near the top edge of the screen for a second, from Home
 * Assistant (the "Open settings on the remote" button), from the launcher ("Harmonium settings"), or with `adb shell am start -n com.example.harmoniumhost/.SettingsActivity`.
 * Styled like Harmonium (see [HarmoniumStyle]); every control is D-pad reachable and shows the
 * amber focus ring. Plain views built in code: nothing to inflate, and fields are one line each.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var app: HostApp
    private lateinit var prefs: HostPrefs
    private lateinit var page: LinearLayout
    /** The card currently being filled. */
    private lateinit var card: LinearLayout
    private val savers = ArrayList<(SharedPreferences.Editor) -> Unit>()

    private lateinit var urlField: EditText
    private lateinit var pathField: EditText
    private lateinit var tokenField: EditText
    private lateinit var rulesField: EditText
    private lateinit var timeoutField: EditText
    private lateinit var micLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var permissionsText: TextView
    private var micCode = 0
    private var micScan = -1
    private var learningMic = false

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES     // dialogs match the dark page
        super.onCreate(savedInstanceState)
        app = HostApp.of(this)
        prefs = app.prefs
        micCode = prefs.micKeyCode
        micScan = prefs.micScanCode
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // don't sleep mid-typing
        window.statusBarColor = S.BG
        window.navigationBarColor = S.BG
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(S.BG)
            isFillViewport = true
            addView(page, MATCH_PARENT, WRAP_CONTENT)
        })

        title()
        buttons(Triple("Save", true, ::save), Triple("Test", false, ::test), Triple("Close", false, ::finish))

        section("Status")
        statusText = body("")

        section("Harmonium")
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

        section("Voice assistant and Home Assistant device")
        note("This remote is an ESPHome device. In Home Assistant: Settings → Devices & services. " +
            "Accept the discovered \"${prefs.espFriendlyName}\", or Add integration → ESPHome → " +
            "host ${ipAddress() ?: "<this remote's IP>"}, port ${EspServer.PORT}. Then open the device " +
            "to set its area and its Assistant (pipeline). Hold the mic button and speak. " +
            "Most settings on this screen are also on that device page (Configuration), so they can be " +
            "changed from Home Assistant too.")
        text("esp_name", "Device name (a-z, 0-9, -)", prefs.rawString("esp_name") ?: "", hint = prefs.espName)
        text("esp_friendly_name", "Friendly name", prefs.rawString("esp_friendly_name") ?: "",
            hint = prefs.espFriendlyName)
        micLabel = body("")
        showMic()
        buttons(Triple("Learn mic button", false) {
            learningMic = true
            micLabel.text = "Press the mic button now…"
        })

        section("Screen and wake")
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

        section("Screensaver")
        choice("screensaver_mode", "Style", HostPrefs.SCREENSAVER_MODES.map { it to it.replaceFirstChar { c -> c.uppercase() } },
            prefs.screensaverMode)
        toggle("screensaver_when_dimmed", "Show it when the screen dims", prefs.screensaverWhenDimmed)
        text("weather_entity", "Weather entity (for the weather style)", prefs.rawString("weather_entity") ?: "",
            hint = "weather.home")
        note("Home Assistant can also turn the screensaver on and off (the Screensaver switch on the device).")

        section("Charging and battery")
        number("charge_screen_s", "Charging screen (seconds, 0 = off)", prefs.chargeScreenSec)
        toggle("status_show", "Battery % at the top of the screen", prefs.statusShow)
        choice("status_pos", "Position", listOf("center" to "Top centre", "left" to "Top left",
            "right" to "Top right (overlaps Harmonium's ⓘ)"), prefs.statusPos)
        number("status_size_sp", "Text size (sp)", prefs.statusSizeSp)

        section("Keys")
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
            gravity = Gravity.TOP or Gravity.START
        }
        savers += { it.putString("key_rules", rulesField.text.toString()) }
        buttons(Triple("Restore default keys", false) { rulesField.setText(HostPrefs.DEFAULT_KEY_RULES) })
        number("long_press_ms", "Long press (ms)", prefs.longPressMs)

        section("Permissions (for Home Assistant controls)")
        permissionsText = body("")
        buttons(Triple("Screen off", false) {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, ScreenOffAdmin::class.java))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Lets Home Assistant turn this remote's screen off."))
        }, Triple("System settings", false) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }, Triple("Usage access", false) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })

        section("More")
        buttons(Triple("Reload Harmonium", false) {
            prefs.reloadPending = true
            finish()
        }, Triple("Android settings", false) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        })
        buttons(Triple("Wi-Fi", false) {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }, Triple("Stock remote app", false) {
            packageManager.getLaunchIntentForPackage("com.aiks.HaRemote")?.let { startActivity(it) }
                ?: toast("com.aiks.HaRemote isn't installed")
        })

        buttons(Triple("Save", true, ::save), Triple("Close", false, ::finish))
    }

    override fun onResume() {
        super.onResume()
        statusText.text = statusSummary()
        permissionsText.text = permissionSummary()
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
    private fun espInputs() = listOf(prefs.espName, prefs.espFriendlyName, prefs.activityEntity, prefs.weatherEntity)

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

    private fun statusSummary(): String {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val rules = KeyRemap.problems(prefs.keyRules)
        return listOf(
            "Battery: ${HostState.batteryLevel}%" + if (HostState.charging) ", charging" else "",
            "Home Assistant (ESPHome): " + when {
                HostState.voiceReady -> "connected, voice ready"
                HostState.haConnected -> "connected, voice not subscribed yet"
                else -> "not connected. Add the device in HA (see below)."
            },
            "This remote: ${ipAddress() ?: "no Wi-Fi address"}, port ${EspServer.PORT}, name ${prefs.espName}",
            "Activity (${prefs.activityEntity}): ${HostState.activity ?: "unknown"}" +
                if (HostState.activityRunning) " → keeping screen on" else "",
            "Last proximity event: ${HostState.lastProximity.ifEmpty { "none yet" }}",
            "Microphone permission: " + if (mic) "granted" else "NOT granted",
            if (rules.isEmpty()) "Key rules: OK" else "Key rules: ${rules.size} problem(s)",
        ).joinToString("\n")
    }

    private fun permissionSummary(): String {
        val admin = (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isAdminActive(ComponentName(this, ScreenOffAdmin::class.java))
        @Suppress("DEPRECATION")
        val usage = (getSystemService(APP_OPS_SERVICE) as AppOpsManager).checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        fun mark(ok: Boolean) = if (ok) "✓" else "✗"
        return listOf(
            "${mark(admin)} Screen off: lets HA turn the screen off (device admin). Without it, \"off\" is a black screensaver.",
            "${mark(Settings.System.canWrite(this))} Modify system settings: brightness, adaptive brightness, screen timeout.",
            "${mark(usage)} Usage access: the foreground-app diagnostic.",
        ).joinToString("\n")
    }

    @Suppress("DEPRECATION") // WifiInfo.ipAddress is the simple way on API 27
    private fun ipAddress(): String? {
        val ip = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).connectionInfo?.ipAddress ?: 0
        if (ip == 0) return null
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    // ---------- tiny view builders (Harmonium look) ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun title() {
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
            addView(TextView(this@SettingsActivity).apply {
                text = "Harmonium Host"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(S.TEXT)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "Version ${app.versionName} · hold a finger near the top edge to come back here"
                textSize = 12f
                setTextColor(S.DIM)
            })
        })
    }

    /** Starts a new card; the builders below add to it. */
    private fun section(title: String) {
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = S.rounded(S.TILE, S.RADIUS_DP * resources.displayMetrics.density)
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        page.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        card.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 12f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(S.ACCENT)
            setPadding(0, 0, 0, dp(4))
        })
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(S.TEXT)
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(4), 0, dp(4))
    }.also { card.addView(it) }

    private fun note(text: String) {
        card.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(S.DIM)
            setPadding(0, dp(2), 0, dp(6))
        })
    }

    private fun label(title: String) {
        card.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(S.DIM)
            setPadding(0, dp(10), 0, dp(4))
        })
    }

    private fun field(title: String, hint: String, value: String, type: Int): EditText {
        if (title.isNotEmpty()) label(title)
        return EditText(this).apply {
            inputType = type
            this.hint = hint
            setText(value)
            setTextColor(S.TEXT)
            setHintTextColor(S.FAINT)
            textSize = 15f
            background = S.focusable(S.TILE_HI, resources.displayMetrics.density)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }.also { card.addView(it, MATCH_PARENT, WRAP_CONTENT) }
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
            textSize = 15f
            setTextColor(S.TEXT)
            thumbTintList = S.switchThumb
            trackTintList = S.switchTrack
            background = S.focusable(0, resources.displayMetrics.density, pressed = S.TILE_HI)
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        card.addView(s, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        savers += { it.putBoolean(key, s.isChecked) }
    }

    private fun choice(key: String, title: String, options: List<Pair<String, String>>, value: String) {
        label(title)
        var selected = options.indexOfFirst { it.first == value }.coerceAtLeast(0)
        val labels = options.map { it.second }
        val spinner = Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item, labels) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                    styled(super.getView(position, convertView, parent))
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) =
                    styled(super.getDropDownView(position, convertView, parent))
                private fun styled(v: View) = v.apply { (this as? TextView)?.setTextColor(S.TEXT) }
            }
            background = S.focusable(S.TILE_HI, resources.displayMetrics.density)
            setPopupBackgroundDrawable(S.rounded(S.TILE_HI, S.RADIUS_DP * resources.displayMetrics.density))
            setSelection(selected)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selected = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        card.addView(spinner, MATCH_PARENT, WRAP_CONTENT)
        savers += { it.putString(key, options[selected].first) }
    }

    /** A row of buttons: (label, primary, action). The primary one wears the accent. */
    private fun buttons(vararg items: Triple<String, Boolean, () -> Unit>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val density = resources.displayMetrics.density
        items.forEachIndexed { i, (label, primary, action) ->
            row.addView(Button(this).apply {
                text = label
                setAllCaps(false)
                textSize = 14f
                setTextColor(if (primary) S.ACCENT_INK else S.TEXT)
                setTypeface(typeface, Typeface.BOLD)
                background = if (primary) S.focusable(S.ACCENT, density, pressed = S.ACCENT, ringColor = S.TEXT)
                             else S.focusable(S.TILE_HI, density)
                stateListAnimator = null
                minHeight = dp(46)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(8)
                topMargin = dp(8)
            })
        }
        (if (::card.isInitialized) card else page).addView(row, MATCH_PARENT, WRAP_CONTENT)
    }
}

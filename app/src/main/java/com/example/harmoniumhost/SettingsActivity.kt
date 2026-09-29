package com.example.harmoniumhost

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
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
    private lateinit var tokenField: EditText
    private lateinit var roomField: EditText
    private lateinit var entityField: EditText
    private lateinit var pipelineField: EditText
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

        heading("Harmonium Host", 22f)
        buttons("Save" to ::save, "Test connection" to ::test, "Close" to ::finish)

        heading("Home Assistant")
        urlField = text("ha_url", "Home Assistant URL", prefs.haUrl, InputType.TYPE_TEXT_VARIATION_URI)
        tokenField = field("Long-lived access token",
            if (prefs.token.isEmpty()) "not set (see note below)" else "saved (type here to replace)",
            "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        note("Easiest: leave this blank and pair the remote in Harmonium (Studio → approve); " +
            "this app then uses Harmonium's token. Or paste one over adb: see README.")
        buttons("Forget token" to {
            prefs.token = ""
            prefs.harmoniumProvisioned = true
            tokenField.hint = "not set"
            toast("Token cleared. Harmonium's token will be picked up if it has one.")
        })
        roomField = text("room", "Room id (as in select.harmonium_<room>_activity)", prefs.room)

        heading("Harmonium")
        text("harmonium_path", "Page path", prefs.harmoniumPath)
        text("harmonium_profile", "Remote profile (astrion, astrion2, …)", prefs.harmoniumProfile)
        entityField = text("activity_entity", "Activity entity", prefs.rawString("activity_entity") ?: "",
            hint = "select.harmonium_<room>_activity")
        text("idle_states", "States that mean \"no activity\" (comma separated)",
            prefs.rawString("idle_states") ?: "off")

        heading("Voice (hold the mic button and speak)")
        micLabel = label("")
        showMic()
        buttons("Learn mic button" to {
            learningMic = true
            micLabel.text = "Press the mic button now…"
        })
        pipelineField = text("pipeline", "Assist pipeline id", prefs.rawString("pipeline") ?: "",
            hint = "blank = HA's preferred pipeline")
        note("\"Test connection\" lists your pipelines and lets you pick one.")
        toggle("siri_enabled", "Siri routing (appletv_siri): use Siri when the entity below is on",
            prefs.siriEnabled)
        text("siri_entity", "Siri switch entity", prefs.rawString("siri_entity") ?: "",
            hint = "input_boolean.remote_siri_<room>")
        text("apple_tv", "Apple TV id (appletv_siri)", prefs.rawString("apple_tv") ?: "", hint = "<room>")

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

        heading("Status")
        statusText = label("")
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
        prefs.edit {
            savers.forEach { it(this) }
            tokenField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { putString("token", it) }
            putInt("mic_keycode", micCode).putInt("mic_scancode", micScan)
            putBoolean("setup_done", true)
        }

        val after = harmoniumInputs()
        if (after != before) {
            // hand the new host/token/profile to Harmonium, then reload it
            if (after.take(3) != before.take(3)) prefs.harmoniumProvisioned = false
            prefs.reloadPending = true
        }
        setSystemTimeout()
        HostService.reload(this)
        toast("Saved")
        finish()
    }

    private fun harmoniumInputs() = listOf(prefs.haUrl, prefs.token, prefs.harmoniumProfile, prefs.harmoniumPath)

    /** Checks the URL, token, activity entity and Assist pipelines using the values typed so far. */
    private fun test() {
        val url = urlField.text.toString().trim().trimEnd('/').ifEmpty { prefs.haUrl }
        val token = tokenField.text.toString().trim().ifEmpty { prefs.token }
        val room = roomField.text.toString().trim().ifEmpty { prefs.room }
        val entity = entityField.text.toString().trim().ifEmpty { "select.harmonium_${room}_activity" }
        statusText.text = "Testing…"
        thread(name = "settings-test") {
            val lines = ArrayList<String>()
            var pipelines: List<Pair<String, String>> = emptyList()
            try {
                if (token.isEmpty()) {
                    lines += "✗ No token. Pair Harmonium or paste one."
                } else {
                    get(url, token, "/api/").use { r ->
                        lines += when (r.code) {
                            200 -> "✓ Home Assistant reachable, token accepted"
                            401 -> "✗ Token rejected (401)"
                            else -> "✗ /api/ returned ${r.code}"
                        }
                    }
                    get(url, token, "/api/states/$entity").use { r ->
                        lines += if (r.isSuccessful) {
                            "✓ $entity = ${JSONObject(r.body?.string().orEmpty()).optString("state")}"
                        } else "✗ $entity not found (${r.code}). Check the room id / entity."
                    }
                    val found = listPipelines(url, token)
                    pipelines = found.first
                    lines += found.second
                }
            } catch (ex: Exception) {
                lines += "✗ ${ex.javaClass.simpleName}: ${ex.message}"
            }
            runOnUiThread {
                statusText.text = lines.joinToString("\n") + "\n\n" + statusSummary()
                if (pipelines.isNotEmpty()) pickPipeline(pipelines)
            }
        }
    }

    private fun get(url: String, token: String, path: String): Response =
        app.http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build().newCall(
            Request.Builder().url(url + path).header("Authorization", "Bearer $token").build()
        ).execute()

    /** One-off websocket: assist_pipeline/pipeline/list. Returns (id to label) plus report lines. */
    private fun listPipelines(url: String, token: String): Pair<List<Pair<String, String>>, List<String>> {
        val done = CountDownLatch(1)
        var reply: JSONObject? = null
        val ws = app.http.newWebSocket(
            Request.Builder().url(url.replaceFirst("http", "ws") + "/api/websocket").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = JSONObject(text)
                    when (msg.optString("type")) {
                        "auth_required" -> webSocket.send(
                            JSONObject().put("type", "auth").put("access_token", token).toString())
                        "auth_ok" -> webSocket.send(
                            JSONObject().put("id", 1).put("type", "assist_pipeline/pipeline/list").toString())
                        "result" -> { reply = msg; done.countDown() }
                        "auth_invalid" -> done.countDown()
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = done.countDown()
            })
        done.await(6, TimeUnit.SECONDS)
        ws.close(1000, null)
        val result = reply?.optJSONObject("result") ?: return emptyList<Pair<String, String>>() to
            listOf("✗ Couldn't list Assist pipelines")
        val preferred = result.optString("preferred_pipeline")
        val arr = result.optJSONArray("pipelines")
        val out = ArrayList<Pair<String, String>>()
        val lines = ArrayList<String>()
        for (i in 0 until (arr?.length() ?: 0)) {
            val p = arr!!.getJSONObject(i)
            val id = p.optString("id")
            val stt = p.optString("stt_engine").takeIf { it.isNotEmpty() && it != "null" }
            val tts = p.optString("tts_engine").takeIf { it.isNotEmpty() && it != "null" }
            val name = p.optString("name") + (if (id == preferred) " (preferred)" else "")
            out += id to name
            lines += (if (stt != null) "✓ " else "✗ ") + "Pipeline $name: " +
                (stt?.let { "speech-to-text $it" } ?: "NO speech-to-text, can't be used for voice") +
                (tts?.let { ", voice reply $it" } ?: ", no voice reply")
        }
        if (out.isEmpty()) lines += "✗ No Assist pipelines. Create one in HA (Settings → Voice assistants)."
        return out to lines
    }

    private fun pickPipeline(pipelines: List<Pair<String, String>>) {
        val labels = (listOf("HA's preferred pipeline") + pipelines.map { it.second }).toTypedArray()
        AlertDialog.Builder(this).setTitle("Assist pipeline for this remote")
            .setItems(labels) { _, which ->
                pipelineField.setText(if (which == 0) "" else pipelines[which - 1].first)
                toast("Pipeline set; press Save")
            }
            .setNegativeButton("Keep as is", null)
            .show()
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
            "HA link: " + if (HostState.haConnected) "connected" else "not connected",
            "Token: " + if (prefs.token.isEmpty()) "none yet" else "set",
            "Activity (${prefs.activityEntity}): ${HostState.activity ?: "unknown"}" +
                if (HostState.activityRunning) " → keeping screen on" else "",
            "Last proximity event: ${HostState.lastProximity.ifEmpty { "none yet" }}",
            "Microphone permission: " + if (mic) "granted" else "NOT granted",
            if (rules.isEmpty()) "Key rules: OK" else "Key rules: ${rules.size} problem(s)",
        ).joinToString("\n")
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

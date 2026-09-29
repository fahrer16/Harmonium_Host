package com.example.harmoniumhost

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONTokener
import kotlin.math.abs

/**
 * Harmonium Host: loads Harmonium from HA (same URL Fully Kiosk uses today) and handles
 * everything a browser can't: native mic, power events, screen wake, key translation.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "HarmoniumHost"
        /** Hold the battery readout at the top this long to open Settings. */
        private const val SETTINGS_HOLD_MS = 3_000L
    }

    private lateinit var app: HostApp
    private lateinit var prefs: HostPrefs
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var statusLine: TextView
    private lateinit var voiceOverlay: TextView
    private lateinit var charging: ChargingScreen
    private lateinit var voice: VoiceRouter
    private lateinit var keeper: ScreenKeeper
    private val remap = KeyRemap { window.superDispatchKeyEvent(it) }
    private val hideVoiceOverlay = Runnable { voiceOverlay.visibility = View.GONE }
    private val openSettings = Runnable { startActivity(Intent(this, SettingsActivity::class.java)) }
    private var pressX = 0f
    private var pressY = 0f
    private var lastAdoptTry = 0L

    private val hostListener = object : HostState.Listener {
        override fun onHostState() {
            updateStatusLine()
            keeper.evaluate()
        }
        override fun onHostEvent(event: HostState.Event) {
            when (event) {
                HostState.Event.SCREEN_ON -> {
                    keeper.interaction()
                    goImmersive()
                    maybeAdoptHarmoniumToken()
                }
                // A hand coming near brightens a dimmed screen before the thumb lands.
                HostState.Event.PROXIMITY -> keeper.interaction()
                HostState.Event.PLUGGED -> {
                    keeper.interaction()
                    val secs = prefs.chargeScreenSec
                    if (secs > 0) charging.show(HostState.batteryLevel, HostState.batteryFull, secs * 1000L)
                }
                HostState.Event.UNPLUGGED -> {
                    charging.dismiss()
                    keeper.interaction()
                    goImmersive()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = HostApp.of(this)
        prefs = app.prefs
        prefs.absorbProvisioning(intent)
        setShowWhenLocked(true)
        goImmersive()
        keeper = ScreenKeeper(window, prefs)

        val dp = resources.displayMetrics.density
        webView = WebView(this)
        statusLine = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setShadowLayer(2 * dp, 0f, 0f, Color.BLACK)   // legible on light and dark themes
            includeFontPadding = false
            // not clickable or focusable: touches and keys go straight through to Harmonium
        }
        voiceOverlay = TextView(this).apply {
            setBackgroundColor(0xE6000000.toInt())
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
            isFocusable = false   // clickable views are focusable on API 26+; keys must stay on the WebView
        }
        charging = ChargingScreen(this)
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(statusLine, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(voiceOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(charging, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(root)

        WebView.setWebContentsDebuggingEnabled(true)   // chrome://inspect on the Mac
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true                    // Harmonium keeps host/token in localStorage
            mediaPlaybackRequiresUserGesture = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    Log.w(TAG, "load failed: ${error.description}; retrying in 5 s")
                    view.postDelayed({ loadHarmonium() }, 5000)
                }
            }
            override fun onPageFinished(view: WebView, url: String) = maybeAdoptHarmoniumToken()
            // The MT6580 is short on memory; if the renderer is killed, start over instead of crashing.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "WebView renderer gone (crash=${detail.didCrash()}); recreating")
                recreate()
                return true
            }
        }
        loadHarmonium()
        webView.requestFocus()

        voice = VoiceRouter(this, prefs, app.link, app.http) { text, done ->
            runOnUiThread { showVoiceOverlay(text, if (done) 2500L else 0L) }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        // Registered for the activity's whole life (not onResume/onPause) so events arriving
        // while the screen is off still update the UI the moment it lights up.
        HostState.addListener(hostListener)
        ContextCompat.startForegroundService(this, Intent(this, HostService::class.java))
        applySettings()

        if (!prefs.setupDone) root.post(openSettings)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (prefs.absorbProvisioning(intent)) {
            loadHarmonium()
            HostService.reload(this)
        }
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        applySettings()
        if (prefs.reloadPending) {
            prefs.reloadPending = false
            loadHarmonium()
        }
        keeper.interaction()
    }

    // Deliberately NOT calling webView.onPause()/pauseTimers() in onPause: keeping the page
    // (and Harmonium's websocket) alive through screen-off is what makes waking feel instant.

    override fun onDestroy() {
        HostState.removeListener(hostListener)
        keeper.release()
        webView.destroy()
        super.onDestroy()
    }

    private fun applySettings() {
        remap.configure(prefs.keyRules, prefs.longPressMs)
        val dp = resources.displayMetrics.density
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.statusSizeSp.toFloat())
        statusLine.layoutParams = (statusLine.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or when (prefs.statusPos) {
                "left" -> Gravity.START
                "right" -> Gravity.END
                else -> Gravity.CENTER_HORIZONTAL
            }
            topMargin = (1 * dp).toInt()
            leftMargin = (6 * dp).toInt()
            rightMargin = (6 * dp).toInt()
        }
        updateStatusLine()
        keeper.evaluate()
    }

    // ---------- input ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount == 0) {
            Log.i(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} code=${event.keyCode} " +
                "scan=${event.scanCode} ${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"}")
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            keeper.interaction()
            charging.dismiss()
            if (!webView.hasFocus()) webView.requestFocus()
        }
        if (isMicKey(event)) {                          // push-to-talk: hold, speak, release
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) voice.start()
                KeyEvent.ACTION_UP -> voice.stop()
            }
            return true
        }
        if (remap.handle(event)) return true
        // This is the home screen: an unmapped Back goes to the page, never finishes the activity.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) { window.superDispatchKeyEvent(event); return true }
        return super.dispatchKeyEvent(event)
    }

    private fun isMicKey(e: KeyEvent): Boolean {
        val code = prefs.micKeyCode
        val scan = prefs.micScanCode
        return (code > 0 && e.keyCode == code) || (scan > 0 && e.keyCode == KeyEvent.KEYCODE_UNKNOWN && e.scanCode == scan)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) keeper.interaction()
        watchSettingsGesture(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Long-press on the battery readout opens Settings. Watches only; never takes the touch from Harmonium. */
    private fun watchSettingsGesture(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (inSettingsZone(ev.x, ev.y)) {
                pressX = ev.x
                pressY = ev.y
                root.postDelayed(openSettings, SETTINGS_HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (abs(ev.x - pressX) > slop || abs(ev.y - pressY) > slop) root.removeCallbacks(openSettings)
            }
            else -> root.removeCallbacks(openSettings)
        }
    }

    private fun inSettingsZone(x: Float, y: Float): Boolean {
        val dp = resources.displayMetrics.density
        if (y > 40 * dp) return false
        val cx = if (statusLine.visibility == View.VISIBLE && statusLine.width > 0)
            statusLine.left + statusLine.width / 2f else root.width / 2f
        return abs(x - cx) < 40 * dp
    }

    // ---------- Harmonium ----------

    private fun loadHarmonium() {
        val base = prefs.haUrl + prefs.harmoniumPath
        val url = if (!prefs.harmoniumProvisioned && prefs.token.isNotEmpty()) {
            prefs.harmoniumProvisioned = true
            // Harmonium's own one-time provisioning: it stores these and strips them from the URL.
            "$base#host=${Uri.encode(Uri.parse(prefs.haUrl).authority)}&token=${Uri.encode(prefs.token)}" +
                "&device=${Uri.encode(prefs.harmoniumProfile)}"
        } else base
        webView.loadUrl(url)
    }

    /**
     * No token of our own yet, but Harmonium may have one (its pairing flow stores it in
     * localStorage). Borrow it so voice and keep-awake work without typing a token on the remote.
     */
    private fun maybeAdoptHarmoniumToken() {
        if (prefs.token.isNotEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdoptTry < 10_000) return
        lastAdoptTry = now
        webView.evaluateJavascript(
            "(function(){try{return localStorage.getItem('hakr_token')||''}catch(e){return ''}})()") { v ->
            val token = try { JSONTokener(v).nextValue() as? String } catch (e: Exception) { null }
            if (!token.isNullOrBlank() && prefs.token.isEmpty()) {
                Log.i(TAG, "using Harmonium's paired HA token")
                prefs.token = token.trim()
                prefs.harmoniumProvisioned = true
                app.link.restart()
            }
        }
    }

    // ---------- overlays ----------

    private fun updateStatusLine() {
        val level = HostState.batteryLevel
        if (!prefs.statusShow || level < 0) { statusLine.visibility = View.GONE; return }
        statusLine.visibility = View.VISIBLE
        statusLine.text = if (HostState.charging) "⚡$level%" else "$level%"
    }

    private fun showVoiceOverlay(text: String, autoHideMs: Long) {
        voiceOverlay.removeCallbacks(hideVoiceOverlay)
        voiceOverlay.text = text
        voiceOverlay.visibility = View.VISIBLE
        if (autoHideMs > 0) voiceOverlay.postDelayed(hideVoiceOverlay, autoHideMs)
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

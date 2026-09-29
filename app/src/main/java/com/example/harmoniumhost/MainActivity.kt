package com.example.harmoniumhost

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Harmonium Host: loads Harmonium from HA (same URL Fully Kiosk uses today) and handles
 * everything a browser can't: native mic, power events, screen wake, key translation.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "HarmoniumHost"
        /** A swipe that starts this close to the top edge… */
        private const val SWIPE_EDGE_DP = 36
        /** …and travels this far down opens Settings. */
        private const val SWIPE_DISTANCE_DP = 90
        /** Last-interaction is reported to HA at most this often. */
        private const val INTERACTION_REPORT_MS = 10_000L

        private const val NO_SIDEWAYS_SCROLL = """(function(){
            if (document.getElementById('hh-noside')) return;
            var s = document.createElement('style');
            s.id = 'hh-noside';
            s.textContent = 'html,body{overflow-x:hidden!important;overscroll-behavior-x:none!important}';
            (document.head || document.documentElement).appendChild(s);
        })()"""
    }

    /** Backstop for the CSS above: the page itself can never sit scrolled sideways. */
    private class VerticalWebView(context: android.content.Context) : WebView(context) {
        init {
            overScrollMode = OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
        }
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            if (l != 0) scrollTo(0, t)
        }
    }

    private lateinit var app: HostApp
    private lateinit var prefs: HostPrefs
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var statusLine: TextView
    private lateinit var voiceOverlay: TextView
    private lateinit var charging: ChargingScreen
    private lateinit var screensaver: Screensaver
    private lateinit var voice: VoiceSatellite
    private lateinit var keeper: ScreenKeeper
    private val remap = KeyRemap { window.superDispatchKeyEvent(it) }
    private val hideVoiceOverlay = Runnable { voiceOverlay.visibility = View.GONE }
    private var swallowGesture = false
    private var swipeFromTop = false
    private var swipeX = 0f
    private var swipeY = 0f
    private var lastInteractionReport = 0L

    private val hostListener = object : HostState.Listener {
        override fun onHostState() {
            updateStatusLine()
            keeper.evaluate()
            if (screensaver.showing) screensaver.updateWeather()
        }
        override fun onHostEvent(event: HostState.Event) {
            when (event) {
                HostState.Event.SCREEN_ON -> {
                    interaction()
                    goImmersive()
                }
                // A hand coming near brightens a dimmed screen before the thumb lands.
                HostState.Event.PROXIMITY -> interaction()
                HostState.Event.PLUGGED -> {
                    interaction()
                    val secs = prefs.chargeScreenSec
                    if (secs > 0) charging.show(HostState.batteryLevel, HostState.batteryFull, secs * 1000L)
                }
                HostState.Event.UNPLUGGED -> {
                    charging.dismiss()
                    interaction()
                    goImmersive()
                }
                HostState.Event.RELOAD -> loadHarmonium()
                HostState.Event.CLEAR_CACHE -> {
                    webView.clearCache(true)
                    loadHarmonium()
                }
                HostState.Event.SCREENSAVER_ON -> screensaver.show(prefs.screensaverMode)
                HostState.Event.SCREENSAVER_OFF -> screensaver.hide()
                HostState.Event.SCREEN_BLACK -> {
                    screensaver.show("black")
                    keeper.dimNow()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = HostApp.of(this)
        prefs = app.prefs
        Log.i(TAG, "Harmonium Host ${app.versionName} starting")
        prefs.absorbProvisioning(intent)
        setShowWhenLocked(true)
        goImmersive()
        keeper = ScreenKeeper(window, prefs) { dimmed ->
            if (dimmed && prefs.screensaverWhenDimmed) screensaver.show(prefs.screensaverMode)
        }

        val dp = resources.displayMetrics.density
        webView = VerticalWebView(this)
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
        screensaver = Screensaver(this)
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(statusLine, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(screensaver, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(voiceOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(charging, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(root)

        WebView.setWebContentsDebuggingEnabled(true)   // chrome://inspect on the Mac
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true                    // Harmonium keeps host/token in localStorage
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)                       // no pinch zoom: the page is sized for the panel
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    Log.w(TAG, "load failed: ${error.description}; retrying in 5 s")
                    view.postDelayed({ loadHarmonium() }, 5000)
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                // Only vertical scrolling: stop the page from panning sideways under a swipe.
                view.evaluateJavascript(NO_SIDEWAYS_SCROLL, null)
                pageChanged(url)
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = pageChanged(url)
            // The MT6580 is short on memory; if the renderer is killed, start over instead of crashing.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "WebView renderer gone (crash=${detail.didCrash()}); recreating")
                recreate()
                return true
            }
        }
        loadHarmonium()
        webView.requestFocus()

        voice = VoiceSatellite(this, prefs, app.esp) { text, done ->
            runOnUiThread { showVoiceOverlay(text, if (done) 2500L else 0L) }
        }
        HostState.screenshot = { screenshot() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        // Registered for the activity's whole life (not onResume/onPause) so events arriving
        // while the screen is off still update the UI the moment it lights up.
        HostState.addListener(hostListener)
        ContextCompat.startForegroundService(this, Intent(this, HostService::class.java))
        applySettings()

        if (!prefs.setupDone) root.post { openSettings() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (prefs.absorbProvisioning(intent)) {
            loadHarmonium()
            HostService.reload(this, espChanged = true)
        }
    }

    override fun onResume() {
        super.onResume()
        HostState.appInForeground = true
        goImmersive()
        readBattery()
        applySettings()
        if (prefs.reloadPending) {
            prefs.reloadPending = false
            loadHarmonium()
        }
        interaction()
    }

    override fun onPause() {
        HostState.appInForeground = false
        super.onPause()
    }

    // Deliberately NOT calling webView.onPause()/pauseTimers() in onPause: keeping the page
    // (and Harmonium's websocket) alive through screen-off is what makes waking feel instant.

    override fun onDestroy() {
        HostState.removeListener(hostListener)
        HostState.screenshot = null
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

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    /** A press, touch, wake or approaching hand: brighten, restart the idle timers, drop the screensaver. */
    private fun interaction() {
        keeper.interaction()
        screensaver.hide()
        HostState.lastInteractionAt = System.currentTimeMillis()
        val now = SystemClock.elapsedRealtime()
        if (now - lastInteractionReport > INTERACTION_REPORT_MS) {
            lastInteractionReport = now
            app.esp.refresh()
        }
    }

    // ---------- input ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount == 0) {
            Log.i(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} code=${event.keyCode} " +
                "scan=${event.scanCode} ${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"}")
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            interaction()                               // the key still goes on to Harmonium below
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
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowGesture = screensaver.showing       // a tap on the screensaver only wakes it up
            interaction()
        }
        if (swallowGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowGesture = false
            }
            return true
        }
        watchSwipeDown(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Swipe down from the top edge opens Settings (like Android's own pull-down). Watches only. */
    private fun watchSwipeDown(ev: MotionEvent) {
        val dp = resources.displayMetrics.density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeFromTop = ev.y < SWIPE_EDGE_DP * dp
                swipeX = ev.x
                swipeY = ev.y
            }
            MotionEvent.ACTION_MOVE -> if (swipeFromTop) {
                val dy = ev.y - swipeY
                val dx = abs(ev.x - swipeX)
                if (dx > dy) swipeFromTop = dx < 24 * dp           // sideways: not our gesture
                else if (dy > SWIPE_DISTANCE_DP * dp) {
                    swipeFromTop = false
                    openSettings()
                }
            }
            else -> swipeFromTop = false
        }
    }

    // ---------- Harmonium ----------

    /**
     * Loads Harmonium with its URL-fragment parameters (it reads them, stores what it keeps, and
     * clears the fragment): `device` = the remote profile (astrion/astrion2) and `page` = the start
     * page, on every load; `host`/`token` only once after they change (Harmonium's own pairing
     * works too).
     */
    private fun loadHarmonium() {
        val params = ArrayList<String>()
        if (!prefs.harmoniumProvisioned && prefs.token.isNotEmpty()) {
            prefs.harmoniumProvisioned = true
            params += "host=" + Uri.encode(Uri.parse(prefs.haUrl).authority)
            params += "token=" + Uri.encode(prefs.token)
        }
        prefs.harmoniumProfile.takeIf { it.isNotEmpty() }?.let { params += "device=" + Uri.encode(it) }
        prefs.startPage.takeIf { it.isNotEmpty() }?.let { params += "page=" + Uri.encode(it) }
        val base = prefs.haUrl + prefs.harmoniumPath
        val url = if (params.isEmpty()) base else base + "#" + params.joinToString("&")
        Log.i(TAG, "loading Harmonium: $base (device=${prefs.harmoniumProfile}, page=${prefs.startPage})")
        webView.loadUrl(url)
    }

    private fun pageChanged(url: String) {
        // never report the provisioning fragment (it can carry the token)
        val clean = url.substringBefore("#token=").substringBefore("&token=")
        if (clean != HostState.pageUrl) {
            HostState.pageUrl = clean
            app.esp.refresh()
        }
    }

    /** Reads the sticky battery state directly, so the readout shows even before the service reports. */
    private fun readBattery() {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val full = plugged && b.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_FULL
        if (level >= 0 && scale > 0) HostState.setBattery(level * 100 / scale, plugged, full)
    }

    /** JPEG of what's on screen, for HA's screenshot camera. Called off the main thread. */
    private fun screenshot(): ByteArray? {
        var out: ByteArray? = null
        val done = CountDownLatch(1)
        runOnUiThread {
            try {
                if (root.width > 0 && root.height > 0) {
                    val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.RGB_565)
                    root.draw(Canvas(bmp))
                    out = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
                    bmp.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "screenshot failed", e)
            } finally {
                done.countDown()
            }
        }
        done.await(3, TimeUnit.SECONDS)
        return out
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

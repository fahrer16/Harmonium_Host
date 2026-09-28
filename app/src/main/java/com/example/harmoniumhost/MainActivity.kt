package com.example.harmoniumhost

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
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

/**
 * Harmonium Host: loads Harmonium from HA (same URL Fully Kiosk uses today) and handles
 * everything a browser can't: native mic, power events, screen wake, key translation.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "HarmoniumHost"

        // STEP 1: run the app, press the mic key, read Logcat (filter "HarmoniumHost").
        // Vendor keys often arrive as keyCode 0 (UNKNOWN) with only a scanCode, so either can be matched.
        const val MIC_KEYCODE = -1
        const val MIC_SCANCODE = -1
    }

    /** Replaces Key Mapper: vendor scanCode -> standard keyCode Harmonium understands. Fill in from Logcat. */
    private val remap: Map<Int, Int> = mapOf(
        // 0x2F2 to KeyEvent.KEYCODE_DPAD_CENTER,
    )

    private lateinit var prefs: HostPrefs
    private lateinit var webView: WebView
    private lateinit var overlayView: TextView
    private lateinit var voice: VoiceRouter
    private val hideOverlay = Runnable { overlayView.visibility = View.GONE }

    private val hostEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(HostService.EXTRA_LEVEL, -1)
            when (intent.action) {
                HostService.ACTION_CHARGING -> showOverlay("⚡  Charging · $level%", 2500)
                HostService.ACTION_UNPLUGGED -> goImmersive()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = HostPrefs(this)
        prefs.absorbProvisioning(intent)
        setShowWhenLocked(true)
        goImmersive()

        webView = WebView(this)
        overlayView = TextView(this).apply {
            setBackgroundColor(0xE6000000.toInt())
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            visibility = View.GONE          // not clickable, so touches fall through to Harmonium
        }
        setContentView(FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(overlayView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })

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
        }
        loadHarmonium()
        webView.requestFocus()

        voice = VoiceRouter(this, prefs) { text, done ->
            runOnUiThread { showOverlay(text, if (done) 2500L else 0L) }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        // Registered for the activity's whole life (not onResume/onPause) so events arriving
        // while the screen is off still update the UI the moment it lights up.
        ContextCompat.registerReceiver(this, hostEvents, IntentFilter().apply {
            addAction(HostService.ACTION_CHARGING)
            addAction(HostService.ACTION_UNPLUGGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.startForegroundService(this, Intent(this, HostService::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (prefs.absorbProvisioning(intent)) loadHarmonium()
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
    }

    // Deliberately NOT calling webView.onPause()/pauseTimers() in onPause: keeping the page
    // (and Harmonium's websocket) alive through screen-off is what makes waking feel instant.

    override fun onDestroy() {
        unregisterReceiver(hostEvents)
        webView.destroy()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount == 0) {
            Log.i(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} code=${event.keyCode} " +
                "scan=${event.scanCode} ${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"}")
        }
        if (isMicKey(event)) {                          // push-to-talk: hold, speak, release
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) voice.start()
                KeyEvent.ACTION_UP -> voice.stop()
            }
            return true
        }
        remap[event.scanCode]?.let { code ->
            return webView.dispatchKeyEvent(
                KeyEvent(event.downTime, event.eventTime, event.action, code, event.repeatCount))
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isMicKey(e: KeyEvent) =
        (MIC_KEYCODE >= 0 && e.keyCode == MIC_KEYCODE) || (MIC_SCANCODE >= 0 && e.scanCode == MIC_SCANCODE)

    private fun loadHarmonium() {
        val base = prefs.haUrl + prefs.harmoniumPath
        val url = if (!prefs.harmoniumProvisioned && prefs.token.isNotEmpty()) {
            prefs.harmoniumProvisioned = true
            // Harmonium's own one-time provisioning: it stores these and strips them from the URL.
            "$base#host=${Uri.parse(prefs.haUrl).authority}&token=${prefs.token}&device=astrion"
        } else base
        webView.loadUrl(url)
    }

    private fun showOverlay(text: String, autoHideMs: Long) {
        overlayView.removeCallbacks(hideOverlay)
        overlayView.text = text
        overlayView.visibility = View.VISIBLE
        if (autoHideMs > 0) overlayView.postDelayed(hideOverlay, autoHideMs)
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

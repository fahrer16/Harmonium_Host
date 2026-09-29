package com.example.harmoniumhost

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The native side's one Home Assistant websocket, shared by the activity watcher and Assist.
 * (Harmonium's own websocket lives in the WebView and is separate.)
 *
 * It's only connected while something [want]s it: the screen being on, or a voice session. Once
 * nothing does, it closes after [GRACE_MS], so a sleeping remote isn't woken by its heartbeats.
 */
class HaLink(private val prefs: HostPrefs, http: OkHttpClient) {

    interface Listener {
        fun onReady() {}
    }

    private companion object {
        const val TAG = "HarmoniumHost"
        const val GRACE_MS = 60_000L
        const val MAX_BACKOFF_MS = 60_000L
        /** Synthetic message handed to pending handlers when the connection drops. */
        const val LINK_DOWN = "link_down"
    }

    // Pings only run while connected, which is only while the screen is on.
    private val client = http.newBuilder().pingInterval(45, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val wants = HashSet<String>()
    private val handlers = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val nextId = AtomicInteger(1)   // HA wants ids to increase; never reset
    private var ws: WebSocket? = null
    private var authFailed = false
    private var backoffMs = 2_000L

    @Volatile var ready = false
        private set

    private val dropIfUnwanted = Runnable { synchronized(lock) { if (wants.isEmpty()) close() } }
    private val retry = Runnable { synchronized(lock) { if (wants.isNotEmpty() && ws == null) open() } }

    fun addListener(l: Listener) { listeners += l }

    /** Registers (or drops) a reason to stay connected. */
    fun want(reason: String, on: Boolean) = synchronized(lock) {
        if (on) {
            wants += reason
            main.removeCallbacks(dropIfUnwanted)
            if (ws == null) open()
        } else if (wants.remove(reason) && wants.isEmpty()) {
            main.removeCallbacks(dropIfUnwanted)
            main.postDelayed(dropIfUnwanted, GRACE_MS)
        }
    }

    /** Settings changed: drop the connection and reconnect with the new URL/token if still wanted. */
    fun restart() = synchronized(lock) {
        authFailed = false
        backoffMs = 2_000L
        close()
        if (wants.isNotEmpty()) open()
    }

    /** Blocks a worker thread until authenticated. Never call on the main thread. */
    fun awaitReady(timeoutMs: Long): Boolean {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!ready && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
        return ready
    }

    /**
     * Sends a command. Every reply carrying its id ("result" and "event") goes to [handler], on
     * OkHttp's thread. If the link drops first, [handler] gets `{"type":"link_down"}`.
     * Returns the id, or -1 when not connected.
     */
    fun send(msg: JSONObject, handler: (JSONObject) -> Unit): Int {
        val socket = ws
        if (!ready || socket == null) return -1
        val id = nextId.getAndIncrement()
        handlers[id] = handler
        if (!socket.send(msg.put("id", id).toString())) { handlers.remove(id); return -1 }
        return id
    }

    fun forget(id: Int) { handlers.remove(id) }

    fun sendBinary(bytes: ByteString): Boolean = ready && ws?.send(bytes) == true

    private fun open() {
        if (prefs.token.isEmpty()) { Log.i(TAG, "HA link: no token yet"); return }
        if (authFailed) return
        val url = prefs.haUrl.replaceFirst("http", "ws") + "/api/websocket"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket === ws) onText(webSocket, text)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, "closed $code")
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                lost(webSocket, t.message ?: t.javaClass.simpleName)
        })
    }

    private fun onText(socket: WebSocket, text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        when (msg.optString("type")) {
            "auth_required" ->
                socket.send(JSONObject().put("type", "auth").put("access_token", prefs.token).toString())
            "auth_ok" -> {
                ready = true
                backoffMs = 2_000L
                HostState.setHaConnected(true)
                Log.i(TAG, "HA link up")
                listeners.forEach { it.onReady() }
            }
            "auth_invalid" -> {
                Log.w(TAG, "HA link: token rejected")
                synchronized(lock) { authFailed = true; close() }
            }
            else -> {
                val id = msg.optInt("id", -1)
                if (id >= 0) handlers[id]?.invoke(msg)
            }
        }
    }

    private fun lost(socket: WebSocket, why: String) = synchronized(lock) {
        if (socket !== ws) return@synchronized
        Log.i(TAG, "HA link down: $why")
        ws = null
        markDown()
        if (wants.isNotEmpty() && !authFailed) {
            main.postDelayed(retry, backoffMs)
            backoffMs = minOf(backoffMs * 2, MAX_BACKOFF_MS)
        }
    }

    private fun close() {
        main.removeCallbacks(retry)
        ws?.close(1000, null)
        ws = null
        markDown()
    }

    private fun markDown() {
        ready = false
        HostState.setHaConnected(false)
        val down = JSONObject().put("type", LINK_DOWN)
        handlers.values.forEach { it(down) }
        handlers.clear()
    }
}

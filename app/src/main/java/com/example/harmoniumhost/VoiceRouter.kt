package com.example.harmoniumhost

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Push-to-talk: hold the mic key, speak, release.
 * The route is decided per utterance by an HA entity (prefs.siriEntity):
 *   on  -> stream PCM to appletv_siri's per-Apple-TV endpoint (Siri on that TV)
 *   off -> run an Assist pipeline over HA's websocket and play the TTS reply
 * Capture is native AudioRecord, so there's no browser getUserMedia and plain-http HA is fine.
 */
class VoiceRouter(
    private val context: Context,
    private val prefs: HostPrefs,
    private val status: (text: String, done: Boolean) -> Unit,
) {
    private companion object {
        const val TAG = "HarmoniumHost"
        const val RATE = 16_000               // PCM16 mono 16 kHz: what HA STT and appletv_siri both take
        const val CHUNK = RATE * 2 / 10       // 100 ms of audio
        const val MAX_UTTERANCE_MS = 15_000L
    }

    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val quick = http.newBuilder().callTimeout(800, TimeUnit.MILLISECONDS).build()

    @Volatile private var busy = false       // a session (capture + response) is in progress
    @Volatile private var capturing = false  // mic key is held

    fun start() {
        if (busy) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            status("Microphone permission not granted", true); return
        }
        busy = true
        capturing = true
        thread(name = "voice") {
            var rec: AudioRecord? = null
            try {
                rec = openMic()               // capture first; its ~1 s buffer absorbs the route lookup
                val siri = routeToSiri()
                status(if (siri) "Listening · Siri" else "Listening…", false)
                if (siri) streamToSiri(rec) else runAssist(rec)
            } catch (e: Exception) {
                Log.e(TAG, "voice failed", e)
                status("Voice error: ${e.message}", true)
            } finally {
                capturing = false
                busy = false
                rec?.release()
            }
        }
    }

    /** Key released: both routes treat end-of-audio as end-of-utterance. */
    fun stop() { capturing = false }

    @SuppressLint("MissingPermission") // checked in start()
    private fun openMic(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        rec.startRecording()
        return rec
    }

    /** Feeds 100 ms chunks to [sink] until the key is released (or the safety cap is hit). */
    private fun pump(rec: AudioRecord, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(CHUNK)
        val deadline = SystemClock.elapsedRealtime() + MAX_UTTERANCE_MS
        while (capturing && SystemClock.elapsedRealtime() < deadline) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) sink(buf, n)
        }
        rec.stop()
    }

    private fun routeToSiri(): Boolean = try {
        val req = Request.Builder()
            .url("${prefs.haUrl}/api/states/${prefs.siriEntity}")
            .header("Authorization", "Bearer ${prefs.token}")
            .build()
        quick.newCall(req).execute().use { r ->
            r.isSuccessful && JSONObject(r.body?.string().orEmpty()).optString("state") == "on"
        }
    } catch (e: Exception) {
        Log.w(TAG, "route lookup failed, defaulting to Assist", e)
        false
    }

    // ---------- Siri route (appletv_siri) ----------

    private fun streamToSiri(rec: AudioRecord) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = -1L          // chunked: end of body = end of utterance
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                pump(rec) { b, n -> sink.write(b, 0, n); sink.flush() }
            }
        }
        val req = Request.Builder()
            .url("${prefs.haUrl}/api/appletv_siri/audio/${prefs.appleTv}?route=siri")
            .header("Authorization", "Bearer ${prefs.token}")
            .post(body)
            .build()
        http.newCall(req).execute().use { r ->
            Log.i(TAG, "siri: ${r.code} ${r.body?.string().orEmpty()}")
            status(if (r.isSuccessful) "Sent to Siri" else "Siri route failed (${r.code})", true)
        }
    }

    // ---------- Assist route (HA websocket assist_pipeline/run) ----------

    private fun runAssist(rec: AudioRecord) {
        val handlerId = AtomicInteger(-1)
        val finished = CountDownLatch(1)
        val wsUrl = prefs.haUrl.replaceFirst("http", "ws") + "/api/websocket"

        val ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = JSONObject(text)
                when (msg.optString("type")) {
                    "auth_required" -> webSocket.send(
                        JSONObject().put("type", "auth").put("access_token", prefs.token).toString())
                    "auth_ok" -> webSocket.send(pipelineRun().toString())
                    "auth_invalid" -> { status("HA rejected the token", true); finished.countDown() }
                    "result" -> if (!msg.optBoolean("success")) {
                        status("Assist: ${msg.optJSONObject("error")?.optString("message")}", true)
                        finished.countDown()
                    }
                    "event" -> onPipelineEvent(msg.getJSONObject("event"), handlerId, finished)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                status("HA connection failed", true)
                finished.countDown()
            }
        })

        // run-start hands us the binary handler id; AudioRecord keeps buffering meanwhile.
        val waitUntil = SystemClock.elapsedRealtime() + 3_000
        while (handlerId.get() < 0 && finished.count > 0 && SystemClock.elapsedRealtime() < waitUntil) {
            Thread.sleep(10)
        }
        val id = handlerId.get()
        if (id < 0) {
            if (finished.count > 0) status("Assist didn't start", true)
            ws.close(1000, null)
            return
        }

        pump(rec) { b, n ->
            val frame = ByteArray(n + 1)
            frame[0] = id.toByte()                        // every audio frame is prefixed with the handler id
            System.arraycopy(b, 0, frame, 1, n)
            ws.send(frame.toByteString())
        }
        ws.send(byteArrayOf(id.toByte()).toByteString())  // handler id alone = end of audio
        status("Thinking…", false)
        finished.await(20, TimeUnit.SECONDS)
        ws.close(1000, null)
    }

    private fun pipelineRun() = JSONObject()
        .put("id", 1)
        .put("type", "assist_pipeline/run")
        .put("start_stage", "stt")
        .put("end_stage", "tts")
        .put("input", JSONObject().put("sample_rate", RATE))
        .apply { if (prefs.pipelineId.isNotEmpty()) put("pipeline", prefs.pipelineId) }

    private fun onPipelineEvent(event: JSONObject, handlerId: AtomicInteger, finished: CountDownLatch) {
        val data = event.optJSONObject("data")
        when (event.optString("type")) {
            "run-start" -> handlerId.set(
                data?.optJSONObject("runner_data")?.optInt("stt_binary_handler_id", -1) ?: -1)
            "stt-end" -> data?.optJSONObject("stt_output")?.optString("text")
                ?.takeIf { it.isNotBlank() }?.let { status("“$it”", false) }
            "intent-end" -> data?.optJSONObject("intent_output")?.optJSONObject("response")
                ?.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech")
                ?.takeIf { it.isNotBlank() }?.let { status(it, true) }
            "tts-end" -> data?.optJSONObject("tts_output")?.optString("url")
                ?.takeIf { it.isNotBlank() }?.let { play(it) }
            "error" -> { status("Assist: ${data?.optString("message")}", true); finished.countDown() }
            "run-end" -> finished.countDown()
        }
    }

    /** Plays the TTS reply on the remote's own speaker, if it has a usable one. */
    private fun play(path: String) {
        val url = if (path.startsWith("http")) path else prefs.haUrl + path
        MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            setDataSource(url)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { it.release() }
            setOnErrorListener { mp, _, _ -> mp.release(); true }
            prepareAsync()
        }
    }
}

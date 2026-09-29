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
import okio.BufferedSink
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Push-to-talk: hold the mic key, speak, release.
 * Default route: Home Assistant Assist over the shared websocket ([HaLink]); the TTS reply plays on
 * the remote's speaker. Optional (Settings): when Siri routing is on, an HA entity decides per
 * utterance whether the audio goes to appletv_siri instead.
 * Capture is native AudioRecord, so there's no browser getUserMedia and plain-http HA is fine.
 */
class VoiceRouter(
    private val context: Context,
    private val prefs: HostPrefs,
    private val link: HaLink,
    private val http: OkHttpClient,
    private val status: (text: String, done: Boolean) -> Unit,
) {
    private companion object {
        const val TAG = "HarmoniumHost"
        const val RATE = 16_000               // PCM16 mono 16 kHz: what HA STT and appletv_siri both take
        const val CHUNK = RATE * 2 / 10       // 100 ms of audio
        const val MIN_SPEECH_BYTES = RATE * 2 * 3 / 10   // < 0.3 s held = an accidental tap
        const val MAX_UTTERANCE_MS = 15_000L
        const val CONVERSATION_MS = 5 * 60_000L          // follow-ups within this reuse the conversation
    }

    private val quick = http.newBuilder().callTimeout(800, TimeUnit.MILLISECONDS).build()

    @Volatile private var busy = false       // a session (capture + response) is in progress
    @Volatile private var capturing = false  // mic key is held
    private var conversationId: String? = null
    private var conversationAt = 0L

    fun start() {
        if (busy) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            status("Microphone permission not granted", true); return
        }
        if (prefs.token.isEmpty()) { status("No Home Assistant token yet: see Settings", true); return }
        busy = true
        capturing = true
        link.want("voice", true)                  // usually already up while the screen is on
        thread(name = "voice") {
            var rec: AudioRecord? = null
            try {
                rec = openMic()                   // capture first; its buffer absorbs the setup time
                val siri = prefs.siriEnabled && routeToSiri()
                status(if (siri) "Listening · Siri" else "Listening…", false)
                if (siri) streamToSiri(rec) else runAssist(rec)
            } catch (e: Exception) {
                Log.e(TAG, "voice failed", e)
                status("Voice error: ${e.message}", true)
            } finally {
                capturing = false
                busy = false
                rec?.release()
                link.want("voice", false)
            }
        }
    }

    /** Key released: both routes treat end-of-audio as end-of-utterance. */
    fun stop() { capturing = false }

    @SuppressLint("MissingPermission") // checked in start()
    private fun openMic(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // 3 s of buffer: covers connecting to HA if the link wasn't up yet.
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2 * 3))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        rec.startRecording()
        return rec
    }

    /** Feeds 100 ms chunks to [sink] until the key is released (or the safety cap is hit). Returns bytes sent. */
    private fun pump(rec: AudioRecord, sink: (ByteArray, Int) -> Unit): Int {
        val buf = ByteArray(CHUNK)
        val deadline = SystemClock.elapsedRealtime() + MAX_UTTERANCE_MS
        var total = 0
        while (capturing && SystemClock.elapsedRealtime() < deadline) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) { sink(buf, n); total += n }
        }
        rec.stop()
        return total
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

    // ---------- Assist route (HA websocket assist_pipeline/run) ----------

    private fun runAssist(rec: AudioRecord) {
        if (!link.awaitReady(4_000)) {
            capturing = false
            status("Can't reach Home Assistant", true)
            return
        }
        val handlerId = AtomicInteger(-1)
        val finished = CountDownLatch(1)
        val runId = link.send(pipelineRun()) { msg ->
            when (msg.optString("type")) {
                "result" -> if (!msg.optBoolean("success")) {
                    status("Assist: ${msg.optJSONObject("error")?.optString("message")}", true)
                    finished.countDown()
                }
                "event" -> msg.optJSONObject("event")?.let { onPipelineEvent(it, handlerId, finished) }
                "link_down" -> { status("Lost connection to Home Assistant", true); finished.countDown() }
            }
        }
        if (runId < 0) { capturing = false; status("Can't reach Home Assistant", true); return }

        // run-start hands us the binary handler id; AudioRecord keeps buffering meanwhile.
        val waitUntil = SystemClock.elapsedRealtime() + 3_000
        while (handlerId.get() < 0 && finished.count > 0 && SystemClock.elapsedRealtime() < waitUntil) {
            Thread.sleep(10)
        }
        val id = handlerId.get()
        if (id < 0) {
            capturing = false
            if (finished.count > 0) status("Assist didn't start", true)
            link.forget(runId)
            return
        }

        val sent = pump(rec) { b, n ->
            val frame = ByteArray(n + 1)
            frame[0] = id.toByte()                        // every audio frame is prefixed with the handler id
            System.arraycopy(b, 0, frame, 1, n)
            link.sendBinary(frame.toByteString())
        }
        link.sendBinary(byteArrayOf(id.toByte()).toByteString())  // handler id alone = end of audio
        if (sent < MIN_SPEECH_BYTES) {
            link.forget(runId)
            status("Hold the mic button while you speak", true)
            return
        }
        status("Thinking…", false)
        finished.await(20, TimeUnit.SECONDS)
        link.forget(runId)
    }

    private fun pipelineRun() = JSONObject()
        .put("type", "assist_pipeline/run")
        .put("start_stage", "stt")
        .put("end_stage", "tts")
        // no_vad: push-to-talk, so the release ends the utterance, not HA's silence detector.
        .put("input", JSONObject().put("sample_rate", RATE).put("no_vad", true))
        .apply {
            if (prefs.pipelineId.isNotEmpty()) put("pipeline", prefs.pipelineId)
            val convo = conversationId
            if (convo != null && SystemClock.elapsedRealtime() - conversationAt < CONVERSATION_MS) {
                put("conversation_id", convo)
            }
        }

    private fun onPipelineEvent(event: JSONObject, handlerId: AtomicInteger, finished: CountDownLatch) {
        val data = event.optJSONObject("data")
        when (event.optString("type")) {
            "run-start" -> handlerId.set(
                data?.optJSONObject("runner_data")?.optInt("stt_binary_handler_id", -1) ?: -1)
            "stt-end" -> data?.optJSONObject("stt_output")?.optString("text")
                ?.takeIf { it.isNotBlank() }?.let { status("“$it”", false) }
            "intent-end" -> data?.optJSONObject("intent_output")?.let { out ->
                out.optString("conversation_id").takeIf { it.isNotBlank() }?.let {
                    conversationId = it
                    conversationAt = SystemClock.elapsedRealtime()
                }
                out.optJSONObject("response")?.optJSONObject("speech")?.optJSONObject("plain")
                    ?.optString("speech")?.takeIf { it.isNotBlank() }?.let { status(it, true) }
            }
            "tts-end" -> data?.optJSONObject("tts_output")?.optString("url")
                ?.takeIf { it.isNotBlank() }?.let { play(it) }
            "error" -> { status("Assist: ${data?.optString("message")}", true); finished.countDown() }
            "run-end" -> finished.countDown()
        }
    }

    /** Plays the TTS reply on the remote's own speaker. */
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

    // ---------- Siri route (appletv_siri), only when enabled in Settings ----------

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
}

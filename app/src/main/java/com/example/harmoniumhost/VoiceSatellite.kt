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
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Push-to-talk through the remote's ESPHome voice satellite: hold the mic key, speak, release.
 * HA runs the Assist pipeline chosen on the device's page in HA (with the device's area, so
 * "turn on the lights" works), and sends the spoken reply back as a URL the remote plays.
 * Capture is native AudioRecord: no browser getUserMedia, so plain-http HA is fine.
 */
class VoiceSatellite(
    private val context: Context,
    private val prefs: HostPrefs,
    private val esp: EspServer,
    private val status: (text: String, done: Boolean) -> Unit,
) : EspServer.VoiceListener {

    private companion object {
        const val TAG = "HarmoniumHost"
        const val RATE = 16_000               // PCM16 mono 16 kHz: what HA's pipeline takes
        const val CHUNK = RATE * 2 / 20       // 50 ms of audio per message
        const val MIN_SPEECH_BYTES = RATE * 2 * 3 / 10   // < 0.3 s held = an accidental tap
        const val MAX_UTTERANCE_MS = 15_000L
        const val CONVERSATION_MS = 5 * 60_000L          // follow-ups within this reuse the conversation
    }

    @Volatile private var busy = false       // a session (capture + response) is in progress
    @Volatile private var capturing = false  // mic key is held
    @Volatile private var response: CountDownLatch? = null
    @Volatile private var responseError = false
    @Volatile private var finished: CountDownLatch? = null
    private var conversationId = ""
    private var conversationAt = 0L

    init { esp.voiceListener = this }

    fun start() {
        if (busy) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            status("Microphone permission not granted", true); return
        }
        if (!esp.voiceReady) {
            status("Voice isn't set up yet: add this remote to Home Assistant (ESPHome). See Settings.", true)
            return
        }
        busy = true
        capturing = true
        thread(name = "voice") {
            var rec: AudioRecord? = null
            try {
                rec = openMic()                   // capture first; its buffer covers HA's reply
                run(rec)
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

    /** Key released: end of the utterance. */
    fun stop() { capturing = false }

    private fun run(rec: AudioRecord) {
        val resp = CountDownLatch(1).also { response = it }
        val done = CountDownLatch(1).also { finished = it }
        responseError = false
        val convo = if (SystemClock.elapsedRealtime() - conversationAt < CONVERSATION_MS) conversationId else ""
        if (!esp.sendVoiceStart(convo) || !resp.await(4, TimeUnit.SECONDS) || responseError) {
            capturing = false
            status("Home Assistant didn't start the voice assistant", true)
            return
        }
        status("Listening…", false)

        val buf = ByteArray(CHUNK)
        val deadline = SystemClock.elapsedRealtime() + MAX_UTTERANCE_MS
        var sent = 0
        while (capturing && SystemClock.elapsedRealtime() < deadline) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) {
                if (!esp.sendAudio(buf, n)) break
                sent += n
            }
        }
        rec.stop()
        if (sent < MIN_SPEECH_BYTES) {
            esp.sendVoiceAbort()
            status("Hold the mic button while you speak", true)
            return
        }
        esp.sendAudioEnd()
        if (done.count > 0) status("Thinking…", false)
        done.await(30, TimeUnit.SECONDS)
    }

    @SuppressLint("MissingPermission") // checked in start()
    private fun openMic(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // 3 s of buffer: holds the first words while HA sets the pipeline up.
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2 * 3))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        rec.startRecording()
        return rec
    }

    // ---------- from HA (socket thread) ----------

    override fun onVoiceResponse(error: Boolean) {
        responseError = error
        response?.countDown()
    }

    override fun onVoiceEvent(type: Int, data: Map<String, String>) {
        when (type) {
            EspServer.EVENT_STT_END -> {
                capturing = false                 // HA has the words; stop sending
                data["text"]?.takeIf { it.isNotBlank() }?.let { status("“$it”", false) }
            }
            EspServer.EVENT_INTENT_END -> {
                data["conversation_id"]?.takeIf { it.isNotBlank() }?.let {
                    conversationId = it
                    conversationAt = SystemClock.elapsedRealtime()
                }
                data["speech"]?.takeIf { it.isNotBlank() }?.let { status(it, true) }
            }
            EspServer.EVENT_TTS_END -> data["url"]?.takeIf { it.isNotBlank() }?.let { play(it) }
            EspServer.EVENT_ERROR -> {
                capturing = false
                responseError = true
                status("Assist: ${data["message"] ?: data["code"] ?: "error"}", true)
                finished?.countDown()
                response?.countDown()
            }
            EspServer.EVENT_RUN_END -> finished?.countDown()
        }
    }

    /** Plays the spoken reply on the remote's own speaker. */
    private fun play(path: String) {
        val uri = Uri.parse(path)
        // HA builds the URL from its own "internal URL"; Android 8.1 can't resolve .local names.
        val url = if (uri.host == null || uri.host!!.endsWith(".local")) {
            prefs.haUrl + (uri.encodedPath ?: "") + (uri.encodedQuery?.let { "?$it" } ?: "")
        } else path
        try {
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
        } catch (e: Exception) {
            Log.w(TAG, "can't play reply $url", e)
        }
    }
}

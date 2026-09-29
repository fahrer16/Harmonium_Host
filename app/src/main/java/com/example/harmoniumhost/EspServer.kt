package com.example.harmoniumhost

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Makes the remote an ESPHome device in Home Assistant: a voice satellite (assist_satellite) plus
 * the entities in [entities], over the ESPHome native API (plain TCP, port 6053, no TLS, so it
 * works with an http-only HA). HA is the client: it discovers the remote over mDNS (or is given
 * its IP), connects, and keeps the connection open.
 *
 * Only the messages this device needs are implemented; everything else is ignored. The protocol
 * is ESPHome's api.proto (message ids and field numbers are noted inline).
 *
 * Threading: every write goes through one writer thread ([io]), so callers on any thread (the
 * main thread included) never touch the network. Each connection has its own reader thread.
 */
class EspServer(private val context: Context, private val prefs: HostPrefs) {

    /** Voice traffic from HA, delivered on a socket thread. */
    interface VoiceListener {
        fun onVoiceResponse(error: Boolean)
        fun onVoiceEvent(type: Int, data: Map<String, String>)
    }

    companion object {
        const val PORT = 6053
        private const val TAG = "HarmoniumHost"
        /** API 1.12: new enough for voice feature flags (1.10), below the object_id-optional change (1.14). */
        private const val API_MINOR = 12L
        private const val ESPHOME_VERSION = "2025.9.0"
        private const val REFRESH_S = 60L
        private const val CAMERA_CHUNK = 8 * 1024

        // message ids (api.proto `option (id)`)
        private const val HELLO_REQ = 1; private const val HELLO_RESP = 2
        private const val AUTH_REQ = 3; private const val AUTH_RESP = 4
        private const val DISCONNECT_REQ = 5; private const val DISCONNECT_RESP = 6
        private const val PING_REQ = 7; private const val PING_RESP = 8
        private const val DEVICE_INFO_REQ = 9; private const val DEVICE_INFO_RESP = 10
        private const val LIST_ENTITIES_REQ = 11
        private const val LIST_BINARY_SENSOR = 12
        private const val LIST_SENSOR = 16
        private const val LIST_SWITCH = 17
        private const val LIST_TEXT_SENSOR = 18
        private const val LIST_DONE = 19
        private const val SUBSCRIBE_STATES = 20
        private const val BINARY_SENSOR_STATE = 21
        private const val SENSOR_STATE = 25
        private const val SWITCH_STATE = 26
        private const val TEXT_SENSOR_STATE = 27
        private const val SWITCH_COMMAND = 33
        private const val SUBSCRIBE_HA_STATES = 38
        private const val SUBSCRIBE_HA_STATE = 39
        private const val HA_STATE = 40
        private const val LIST_CAMERA = 43
        private const val CAMERA_IMAGE = 44
        private const val CAMERA_REQUEST = 45
        private const val LIST_NUMBER = 49
        private const val NUMBER_STATE = 50
        private const val NUMBER_COMMAND = 51
        private const val LIST_SELECT = 52
        private const val SELECT_STATE = 53
        private const val SELECT_COMMAND = 54
        private const val LIST_BUTTON = 61
        private const val BUTTON_COMMAND = 62
        private const val SUBSCRIBE_VOICE = 89
        private const val VOICE_REQUEST = 90
        private const val VOICE_RESPONSE = 91
        private const val VOICE_EVENT = 92
        private const val VOICE_AUDIO = 106
        private const val VOICE_CONFIG_REQ = 121
        private const val VOICE_CONFIG_RESP = 122

        // VoiceAssistantFeature: VOICE_ASSISTANT | API_AUDIO (mic audio over this TCP connection).
        // No SPEAKER flag: HA then sends the reply as a URL, which the remote plays itself.
        private const val VOICE_FEATURES = 1L or 4L

        // VoiceAssistantEvent values used by VoiceSatellite
        const val EVENT_ERROR = 0
        const val EVENT_RUN_END = 2
        const val EVENT_STT_END = 4
        const val EVENT_INTENT_END = 6
        const val EVENT_TTS_END = 8
    }

    /** Set once by HostService before [start]. */
    @Volatile var entities: List<EspEntity> = emptyList()
    /** (entity_id, attribute) pairs HA should forward; attribute "" = the state. */
    @Volatile var haSubscriptions: () -> List<Pair<String, String>> = { emptyList() }
    @Volatile var onHaState: (entityId: String, attribute: String, state: String) -> Unit = { _, _, _ -> }
    @Volatile var voiceListener: VoiceListener? = null

    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "esphome-io").apply { isDaemon = true } }
    private val refreshQueued = AtomicBoolean(false)
    private val connections = CopyOnWriteArrayList<Conn>()
    @Volatile private var voiceConn: Conn? = null
    @Volatile private var server: ServerSocket? = null
    private var nsd: NsdManager.RegistrationListener? = null

    /** HA has subscribed to this remote's voice assistant: push-to-talk can run. */
    val voiceReady get() = voiceConn != null

    fun start() {
        if (server != null) return
        thread(name = "esphome-api", isDaemon = true) {
            try {
                val ss = ServerSocket(PORT)
                server = ss
                Log.i(TAG, "ESPHome API listening on port $PORT as ${prefs.espName}")
                while (!ss.isClosed) {
                    val s = ss.accept()
                    s.tcpNoDelay = true
                    s.soTimeout = 150_000          // HA pings every 20 s; silence this long = gone
                    val c = Conn(s)
                    connections += c
                    thread(name = "esphome-conn", isDaemon = true) { c.run() }
                }
            } catch (e: IOException) {
                Log.w(TAG, "ESPHome API stopped: ${e.message}")
                server = null
            }
        }
        // Diagnostics drift slowly; a sleeping CPU simply delays this, it never wakes it.
        io.scheduleWithFixedDelay({ refresh() }, REFRESH_S, REFRESH_S, TimeUnit.SECONDS)
        advertise()
    }

    fun stop() {
        server?.let { s -> server = null; try { s.close() } catch (e: IOException) {} }
        connections.forEach { it.close() }
        unadvertise()
    }

    /** Name, friendly name or subscriptions changed: re-announce, and let HA reconnect to pick it up. */
    fun reload() {
        connections.forEach { it.close() }
        unadvertise()
        advertise()
    }

    /** Re-reads every entity and sends the ones that changed. Cheap; coalesced; callable from any thread. */
    fun refresh() {
        if (!refreshQueued.compareAndSet(false, true)) return
        io.execute {
            refreshQueued.set(false)
            val live = connections.filter { it.statesWanted }
            if (live.isEmpty()) return@execute
            for (e in entities) {
                val msg = try { stateMessage(e) } catch (ex: Exception) { Log.w(TAG, "read ${e.objectId}: $ex"); null } ?: continue
                live.forEach { it.sendIfChanged(e.key, msg) }
            }
        }
    }

    /** Captures and sends a fresh image for [cam] to every connection. */
    fun pushImage(cam: EspCamera) {
        thread(name = "esphome-camera") {
            val jpeg = try { cam.capture() } catch (e: Exception) { Log.w(TAG, "screenshot: $e"); null } ?: return@thread
            var off = 0
            while (off < jpeg.size) {
                val n = minOf(CAMERA_CHUNK, jpeg.size - off)
                val last = off + n >= jpeg.size
                val msg = ProtoWriter().fixed32(1, cam.key).bytes(2, jpeg.copyOfRange(off, off + n))
                    .bool(3, last).toByteArray()
                connections.forEach { it.send(CAMERA_IMAGE, msg) }
                off += n
            }
        }
    }

    // ---------- voice, used by VoiceSatellite ----------

    fun sendVoiceStart(conversationId: String): Boolean = voiceConn?.send(VOICE_REQUEST, ProtoWriter()
        .bool(1, true)                       // start
        .string(2, conversationId)           // conversation_id
        .uint(3, 0)                          // flags: no wake word
        .message(4, ProtoWriter().float(3, 1f))  // audio_settings.volume_multiplier
        .toByteArray()) == true

    /** start=false: HA aborts the run. */
    fun sendVoiceAbort() { voiceConn?.send(VOICE_REQUEST, ByteArray(0)) }

    fun sendAudio(data: ByteArray, len: Int): Boolean =
        voiceConn?.send(VOICE_AUDIO, ProtoWriter().bytes(1, data, len).toByteArray()) == true

    /** end=true: the key was released; HA finishes speech-to-text with what it has. */
    fun sendAudioEnd() { voiceConn?.send(VOICE_AUDIO, ProtoWriter().bool(2, true).toByteArray()) }

    // ---------- entity encoding ----------

    private fun listMessage(e: EspEntity): Pair<Int, ByteArray> {
        val w = ProtoWriter().string(1, e.objectId).fixed32(2, e.key).string(3, e.name)
        return when (e) {
            is EspSensor -> LIST_SENSOR to w.string(5, e.icon).string(6, e.unit).uint(7, e.decimals.toLong())
                .string(9, e.deviceClass).uint(10, e.stateClass.toLong()).uint(13, e.category.toLong()).toByteArray()
            is EspBinarySensor -> LIST_BINARY_SENSOR to w.string(5, e.deviceClass).string(8, e.icon)
                .uint(9, e.category.toLong()).toByteArray()
            is EspTextSensor -> LIST_TEXT_SENSOR to w.string(5, e.icon).uint(7, e.category.toLong())
                .string(8, e.deviceClass).toByteArray()
            is EspSwitch -> LIST_SWITCH to w.string(5, e.icon).uint(8, e.category.toLong()).toByteArray()
            is EspNumber -> LIST_NUMBER to w.string(5, e.icon).float(6, e.min).float(7, e.max).float(8, e.step)
                .uint(10, e.category.toLong()).string(11, e.unit).uint(12, 2).toByteArray()   // mode: slider
            is EspSelect -> LIST_SELECT to w.string(5, e.icon).apply { e.options.forEach { string(6, it) } }
                .uint(8, e.category.toLong()).toByteArray()
            is EspButton -> LIST_BUTTON to w.string(5, e.icon).uint(7, e.category.toLong()).toByteArray()
            is EspCamera -> LIST_CAMERA to w.string(6, e.icon).uint(7, e.category.toLong()).toByteArray()
        }
    }

    /** The state message for [e], or null for entities without a state (buttons, cameras). */
    private fun stateMessage(e: EspEntity): Pair<Int, ByteArray>? {
        val w = ProtoWriter().fixed32(1, e.key)
        return when (e) {
            is EspSensor -> e.read().let { v -> SENSOR_STATE to w.float(2, v ?: 0f).bool(3, v == null || v.isNaN()).toByteArray() }
            is EspBinarySensor -> e.read().let { v -> BINARY_SENSOR_STATE to w.bool(2, v == true).bool(3, v == null).toByteArray() }
            is EspTextSensor -> e.read().let { v -> TEXT_SENSOR_STATE to w.string(2, (v ?: "").take(255)).bool(3, v == null).toByteArray() }
            is EspSwitch -> e.read().let { v -> SWITCH_STATE to w.bool(2, v == true).bool(4, v == null).toByteArray() }
            is EspNumber -> e.read().let { v -> NUMBER_STATE to w.float(2, v ?: 0f).bool(3, v == null).toByteArray() }
            is EspSelect -> e.read().let { v -> SELECT_STATE to w.string(2, v ?: "").bool(3, v == null).toByteArray() }
            is EspButton, is EspCamera -> null
        }
    }

    private fun entity(key: Int) = entities.firstOrNull { it.key == key }

    private fun command(type: Int, payload: ByteArray) {
        val r = ProtoReader(payload)
        val e = entity(r.fixed32(1)) ?: return
        Log.i(TAG, "ESPHome: command for ${e.objectId}")
        try {
            when {
                type == SWITCH_COMMAND && e is EspSwitch -> e.write(r.bool(2))
                type == NUMBER_COMMAND && e is EspNumber -> e.write(r.float(2))
                type == SELECT_COMMAND && e is EspSelect -> e.write(r.string(2))
                type == BUTTON_COMMAND && e is EspButton -> e.press()
            }
        } catch (ex: Exception) {
            Log.w(TAG, "command ${e.objectId} failed: $ex")
        }
        // Report the result once the command has taken effect.
        io.schedule({ refresh() }, 700, TimeUnit.MILLISECONDS)
    }

    // ---------- mDNS (how HA discovers ESPHome devices) ----------

    private fun advertise() {
        val mgr = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            serviceName = prefs.espName
            serviceType = "_esphomelib._tcp"
            port = PORT
            setAttribute("mac", prefs.espMac.replace(":", "").lowercase())
            setAttribute("version", ESPHOME_VERSION)
            setAttribute("friendly_name", prefs.espFriendlyName)
            setAttribute("platform", "Android")
            setAttribute("board", "astrion_ha100")
            setAttribute("network", "wifi")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "mDNS: announced as ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "mDNS: announce failed ($code)") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        try {
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsd = listener
        } catch (e: Exception) {
            Log.w(TAG, "mDNS: ${e.message}")
        }
    }

    private fun unadvertise() {
        val l = nsd ?: return
        nsd = null
        try { (context.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) } catch (e: Exception) {}
    }

    // ---------- one HA connection ----------

    private inner class Conn(private val socket: Socket) {
        private val input: InputStream = BufferedInputStream(socket.getInputStream())
        private val out = BufferedOutputStream(socket.getOutputStream())
        private val peer = socket.inetAddress?.hostAddress ?: "?"
        @Volatile private var closed = false
        @Volatile var statesWanted = false
        /** Last state payload sent per entity key (writer thread only). */
        private val sent = HashMap<Int, ByteArray>()

        fun run() {
            Log.i(TAG, "ESPHome: Home Assistant connected from $peer")
            try {
                while (true) {
                    val preamble = input.read()
                    if (preamble < 0) break
                    if (preamble != 0) {
                        Log.w(TAG, "ESPHome: $peer wants an encrypted connection. Remove the encryption key for this device in HA.")
                        break
                    }
                    val len = readVarint()
                    val type = readVarint()
                    if (len < 0 || type < 0 || len > 1_000_000) break
                    val payload = ByteArray(len)
                    var got = 0
                    while (got < len) {
                        val n = input.read(payload, got, len - got)
                        if (n < 0) throw IOException("eof")
                        got += n
                    }
                    handle(type, payload)
                }
            } catch (e: IOException) {
                Log.i(TAG, "ESPHome: connection from $peer ended (${e.message})")
            } finally {
                close()
            }
        }

        private fun readVarint(): Int {
            var result = 0
            var shift = 0
            while (shift < 35) {
                val b = input.read()
                if (b < 0) return -1
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return -1
        }

        /** Queues a frame on the writer thread. Returns false once the connection is gone. */
        fun send(type: Int, payload: ByteArray): Boolean {
            if (closed) return false
            io.execute { write(type, payload) }
            return true
        }

        private fun write(type: Int, payload: ByteArray) {
            if (closed) return
            try {
                out.write(0)
                out.write(ProtoWriter.varintBytes(payload.size))
                out.write(ProtoWriter.varintBytes(type))
                out.write(payload)
                out.flush()
            } catch (e: IOException) {
                close()
            }
        }

        /** Writer thread only. */
        fun sendIfChanged(key: Int, msg: Pair<Int, ByteArray>) {
            if (sent[key]?.contentEquals(msg.second) == true) return
            sent[key] = msg.second
            write(msg.first, msg.second)
        }

        fun close() {
            closed = true
            if (!connections.remove(this)) return
            try { socket.close() } catch (e: IOException) {}
            if (voiceConn === this) {
                voiceConn = null
                HostState.setVoiceReady(false)
            }
            HostState.setHaConnected(connections.isNotEmpty())
        }

        private fun handle(type: Int, payload: ByteArray) {
            when (type) {
                HELLO_REQ -> {
                    Log.i(TAG, "ESPHome: hello from ${ProtoReader(payload).string(1)}")
                    send(HELLO_RESP, ProtoWriter()
                        .uint(1, 1).uint(2, API_MINOR)
                        .string(3, "Harmonium Host ${HostApp.of(context).versionName}")
                        .string(4, prefs.espName)
                        .toByteArray())
                    HostState.setHaConnected(true)
                }
                AUTH_REQ -> send(AUTH_RESP, ByteArray(0))          // no password
                DISCONNECT_REQ -> { send(DISCONNECT_RESP, ByteArray(0)); io.execute { close() } }
                PING_REQ -> send(PING_RESP, ByteArray(0))
                DEVICE_INFO_REQ -> send(DEVICE_INFO_RESP, deviceInfo())
                LIST_ENTITIES_REQ -> {
                    entities.forEach { e -> listMessage(e).let { send(it.first, it.second) } }
                    send(LIST_DONE, ByteArray(0))
                }
                SUBSCRIBE_STATES -> {
                    statesWanted = true
                    io.execute { sent.clear() }
                    refresh()
                }
                // HA forwards these entities' states from now on (no token or permission needed).
                SUBSCRIBE_HA_STATES -> haSubscriptions().forEach { (entity, attr) ->
                    if (entity.isNotEmpty()) send(SUBSCRIBE_HA_STATE, ProtoWriter().string(1, entity).string(2, attr).toByteArray())
                }
                HA_STATE -> {
                    val r = ProtoReader(payload)
                    onHaState(r.string(1), r.string(3), r.string(2))
                }
                SWITCH_COMMAND, NUMBER_COMMAND, SELECT_COMMAND, BUTTON_COMMAND -> command(type, payload)
                CAMERA_REQUEST -> entities.filterIsInstance<EspCamera>().firstOrNull()?.let { pushImage(it) }
                SUBSCRIBE_VOICE -> {
                    val r = ProtoReader(payload)
                    if (r.bool(1)) {
                        voiceConn = this
                        Log.i(TAG, "ESPHome: voice subscribed (flags ${r.uint(2)})")
                    } else if (voiceConn === this) {
                        voiceConn = null
                    }
                    HostState.setVoiceReady(voiceConn != null)
                }
                VOICE_RESPONSE -> voiceListener?.onVoiceResponse(ProtoReader(payload).bool(2))
                VOICE_EVENT -> {
                    val r = ProtoReader(payload)
                    val data = r.messages(2).associate { it.string(1) to it.string(2) }
                    voiceListener?.onVoiceEvent(r.uint(1).toInt(), data)
                }
                VOICE_CONFIG_REQ -> send(VOICE_CONFIG_RESP, ByteArray(0))   // no wake words
                VOICE_AUDIO -> {}                                     // only with SPEAKER, which we don't claim
                else -> Log.d(TAG, "ESPHome: ignoring message $type")
            }
        }

        private fun deviceInfo() = ProtoWriter()
            .string(2, prefs.espName)
            .string(3, prefs.espMac)
            .string(4, ESPHOME_VERSION)
            .string(5, SimpleDateFormat("MMM dd yyyy, HH:mm:ss", Locale.US)
                .format(Date(HostApp.of(context).installedAt)))
            .string(6, "Astrion HA100")
            .string(8, "harmonium.host")
            .string(9, HostApp.of(context).versionName)
            .string(12, "Sanytron")
            .string(13, prefs.espFriendlyName)
            .uint(17, VOICE_FEATURES)
            .toByteArray()
    }
}

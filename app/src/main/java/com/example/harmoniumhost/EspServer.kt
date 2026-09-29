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
import kotlin.concurrent.thread

/**
 * Makes the remote an ESPHome device in Home Assistant: a voice satellite (assist_satellite) plus
 * battery and charging entities, over the ESPHome native API (plain TCP, port 6053, no TLS, so it
 * works with an http-only HA). HA is the client: it discovers the remote over mDNS (or is given
 * its IP), connects, and keeps the connection open.
 *
 * Only the messages a voice satellite needs are implemented; everything else is ignored. The
 * protocol is ESPHome's api.proto (message ids and field numbers are noted inline).
 * Also carries the activity select's state: the remote asks HA to forward it (no token needed).
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
        /** We speak API 1.12: new enough for voice feature flags (1.10), below the object_id-optional change (1.14). */
        private const val API_MINOR = 12L
        private const val ESPHOME_VERSION = "2025.9.0"

        // message ids (api.proto `option (id)`)
        private const val HELLO_REQ = 1; private const val HELLO_RESP = 2
        private const val AUTH_REQ = 3; private const val AUTH_RESP = 4
        private const val DISCONNECT_REQ = 5; private const val DISCONNECT_RESP = 6
        private const val PING_REQ = 7; private const val PING_RESP = 8
        private const val DEVICE_INFO_REQ = 9; private const val DEVICE_INFO_RESP = 10
        private const val LIST_ENTITIES_REQ = 11
        private const val LIST_BINARY_SENSOR = 12
        private const val LIST_SENSOR = 16
        private const val LIST_DONE = 19
        private const val SUBSCRIBE_STATES = 20
        private const val BINARY_SENSOR_STATE = 21
        private const val SENSOR_STATE = 25
        private const val SUBSCRIBE_HA_STATES = 38
        private const val SUBSCRIBE_HA_STATE = 39
        private const val HA_STATE = 40
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

        private const val KEY_BATTERY = 1
        private const val KEY_CHARGING = 2
    }

    private val connections = CopyOnWriteArrayList<Conn>()
    @Volatile private var voiceConn: Conn? = null
    @Volatile var voiceListener: VoiceListener? = null
    @Volatile private var server: ServerSocket? = null
    private var nsd: NsdManager.RegistrationListener? = null
    private var batteryLevel = -1
    private var charging = false

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
                    s.soTimeout = 150_000          // HA pings every ~20 s; silence this long = gone
                    val c = Conn(s)
                    connections += c
                    thread(name = "esphome-conn", isDaemon = true) { c.run() }
                }
            } catch (e: IOException) {
                Log.w(TAG, "ESPHome API stopped: ${e.message}")
                server = null
            }
        }
        advertise()
    }

    fun stop() {
        server?.let { s -> server = null; try { s.close() } catch (e: IOException) {} }
        connections.forEach { it.close() }
        unadvertise()
    }

    /** Name, friendly name or activity entity changed: re-announce, and let HA reconnect to pick it up. */
    fun reload() {
        connections.forEach { it.close() }
        unadvertise()
        advertise()
    }

    // ---------- outgoing, used by VoiceSatellite and HostService ----------

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

    fun publishBattery(level: Int, charging: Boolean) {
        if (level == batteryLevel && charging == this.charging) return
        batteryLevel = level
        this.charging = charging
        connections.filter { it.statesWanted }.forEach { it.sendStates() }
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
        @Volatile var statesWanted = false

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

        fun send(type: Int, payload: ByteArray): Boolean = synchronized(this) {
            try {
                out.write(0)
                out.write(ProtoWriter.varintBytes(payload.size))
                out.write(ProtoWriter.varintBytes(type))
                out.write(payload)
                out.flush()
                true
            } catch (e: IOException) {
                close()
                false
            }
        }

        fun close() {
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
                DISCONNECT_REQ -> { send(DISCONNECT_RESP, ByteArray(0)); close() }
                PING_REQ -> send(PING_RESP, ByteArray(0))
                DEVICE_INFO_REQ -> send(DEVICE_INFO_RESP, deviceInfo())
                LIST_ENTITIES_REQ -> {
                    send(LIST_SENSOR, ProtoWriter()
                        .string(1, "battery").fixed32(2, KEY_BATTERY).string(3, "Battery")
                        .string(6, "%").string(9, "battery")         // unit, device_class
                        .uint(10, 1)                            // state_class: measurement
                        .toByteArray())
                    send(LIST_BINARY_SENSOR, ProtoWriter()
                        .string(1, "charging").fixed32(2, KEY_CHARGING).string(3, "Charging")
                        .string(5, "battery_charging")                // device_class
                        .toByteArray())
                    send(LIST_DONE, ByteArray(0))
                }
                SUBSCRIBE_STATES -> { statesWanted = true; sendStates() }
                // HA forwards this entity's state from now on (no token or permission needed).
                SUBSCRIBE_HA_STATES -> prefs.activityEntity.takeIf { it.isNotEmpty() }?.let {
                    send(SUBSCRIBE_HA_STATE, ProtoWriter().string(1, it).toByteArray())
                }
                HA_STATE -> {
                    val r = ProtoReader(payload)
                    if (r.string(1) == prefs.activityEntity && r.string(3).isEmpty()) {
                        Log.i(TAG, "activity: ${r.string(2)}")
                        HostState.setActivity(r.string(2), prefs.idleStates)
                    }
                }
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
                VOICE_RESPONSE -> {
                    val r = ProtoReader(payload)
                    voiceListener?.onVoiceResponse(r.bool(2))
                }
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

        fun sendStates() {
            val level = batteryLevel
            send(SENSOR_STATE, ProtoWriter().fixed32(1, KEY_BATTERY).float(2, level.toFloat())
                .bool(3, level < 0).toByteArray())
            send(BINARY_SENSOR_STATE, ProtoWriter().fixed32(1, KEY_CHARGING).bool(2, charging).toByteArray())
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

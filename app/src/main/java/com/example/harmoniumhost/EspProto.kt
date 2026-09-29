package com.example.harmoniumhost

import java.io.ByteArrayOutputStream

/**
 * Just enough protobuf for the ESPHome native API: varints, strings/bytes, fixed32 and floats.
 * Field numbers live next to each message in [EspServer]; they follow ESPHome's api.proto.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun uint(field: Int, v: Long) = apply { if (v != 0L) { tag(field, 0); varint(v) } }
    fun bool(field: Int, v: Boolean) = apply { if (v) { tag(field, 0); varint(1) } }
    fun string(field: Int, v: String) = apply { if (v.isNotEmpty()) bytes(field, v.toByteArray(Charsets.UTF_8)) }
    fun bytes(field: Int, b: ByteArray, len: Int = b.size) = apply {
        if (len > 0) { tag(field, 2); varint(len.toLong()); out.write(b, 0, len) }
    }
    fun message(field: Int, m: ProtoWriter) = bytes(field, m.toByteArray())
    /** Always written, even when 0 (entity keys are `force = true`). */
    fun fixed32(field: Int, v: Int) = apply {
        tag(field, 5)
        for (i in 0 until 4) out.write((v ushr (8 * i)) and 0xFF)
    }
    fun float(field: Int, v: Float) = apply { if (v != 0f) fixed32(field, java.lang.Float.floatToIntBits(v)) }

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        /** Unsigned LEB128, as used by the frame header. */
        fun varintBytes(value: Int): ByteArray {
            val o = ByteArrayOutputStream()
            var v = value
            while (v and 0x7F.inv() != 0) {
                o.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            o.write(v)
            return o.toByteArray()
        }
    }
}

/** Decodes one message into per-field values. Unknown fields are kept, never an error. */
class ProtoReader(bytes: ByteArray) {
    private val varints = HashMap<Int, Long>()
    private val blobs = HashMap<Int, MutableList<ByteArray>>()

    init {
        var i = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (i < bytes.size) {
                val b = bytes[i++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return result
        }
        while (i < bytes.size) {
            val key = readVarint().toInt()
            val field = key ushr 3
            when (key and 7) {
                0 -> varints[field] = readVarint()
                1 -> i += 8
                2 -> {
                    val len = readVarint().toInt()
                    if (len < 0 || i + len > bytes.size) break
                    blobs.getOrPut(field) { ArrayList() } += bytes.copyOfRange(i, i + len)
                    i += len
                }
                5 -> i += 4
                else -> break    // groups aren't used by the API
            }
        }
    }

    fun uint(field: Int) = varints[field] ?: 0L
    fun bool(field: Int) = uint(field) != 0L
    fun string(field: Int) = blobs[field]?.firstOrNull()?.toString(Charsets.UTF_8) ?: ""
    fun messages(field: Int): List<ProtoReader> = blobs[field]?.map { ProtoReader(it) } ?: emptyList()
}

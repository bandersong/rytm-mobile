package com.bandersong.rytmmobile

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * Elektron SysEx file-transfer protocol (the one Elektron Transfer and Overbridge use),
 * ported from Elektroid (GPL-3.0, github.com/dagargo/elektroid, src/connectors/elektron.c).
 *
 * Message (before 7-bit packing): [seq hi, seq lo, 0, 0, type, ...payload]
 * Wire: F0 00 20 3C 10 00 <7-bit packed message> F7
 * Responses carry type | 0x80 and echo the request's seq.
 */
object Elektron {
    val HEADER = byteArrayOf(0xF0.toByte(), 0x00, 0x20, 0x3C, 0x10, 0x00)
    const val SAMPLE_RATE = 48000
    const val BLOCK_BYTES = 0x2000
    const val SAMPLE_HEADER_BYTES = 64 // elektron_sample_header: 4+4*4+4+10*4
    const val LOOP_FWD = 0x00
    const val LOOP_NONE = 0x7F

    const val PING = 0x01
    const val SW_VERSION = 0x02
    const val READ_DIR = 0x10
    const val CREATE_DIR = 0x11
    const val DELETE_FILE = 0x20
    const val OPEN_WRITER = 0x40
    const val CLOSE_WRITER = 0x41
    const val WRITE_FILE = 0x42

    private val CP1252: Charset = Charset.forName("windows-1252")

    /** 8 bytes out for every 7 in: first byte holds the high bits, MSB-first. */
    fun encode7(src: ByteArray): ByteArray {
        val out = ByteArray(src.size + (src.size + 6) / 7)
        var i = 0; var j = 0
        while (j < src.size) {
            var accum = 0
            for (k in 0 until 7) {
                accum = accum shl 1
                if (j + k < src.size) {
                    val b = src[j + k].toInt() and 0xFF
                    if (b and 0x80 != 0) accum = accum or 1
                    out[i + k + 1] = (b and 0x7F).toByte()
                }
            }
            out[i] = accum.toByte()
            i += 8; j += 7
        }
        return out
    }

    fun decode7(src: ByteArray): ByteArray {
        val out = ByteArray(src.size - (src.size + 7) / 8)
        var i = 0; var j = 0
        while (i < src.size) {
            var shift = 0x40
            var k = 0
            while (k < 7 && i + k + 1 < src.size) {
                val hi = if (src[i].toInt() and shift != 0) 0x80 else 0
                out[j + k] = ((src[i + k + 1].toInt() and 0x7F) or hi).toByte()
                shift = shift shr 1; k++
            }
            i += 8; j += 7
        }
        return out
    }

    fun toSysex(msg: ByteArray, seq: Int): ByteArray {
        msg[0] = (seq shr 8).toByte(); msg[1] = seq.toByte()
        val enc = encode7(msg)
        return HEADER + enc + byteArrayOf(0xF7.toByte())
    }

    /** Returns the decoded message, or null if this SysEx is not an Elektron transfer message. */
    fun fromSysex(raw: ByteArray): ByteArray? {
        if (raw.size < 12) return null
        for (k in HEADER.indices) if (raw[k] != HEADER[k]) return null
        val end = if (raw.last() == 0xF7.toByte()) raw.size - 1 else raw.size
        return decode7(raw.copyOfRange(HEADER.size, end))
    }

    private fun base(type: Int, extra: Int = 0): ByteArrayOutputStream {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0, 0, 0, 0, type.toByte()))
        o.write(ByteArray(extra))
        return o
    }

    private fun path(o: ByteArrayOutputStream, p: String) { o.write(p.toByteArray(CP1252)); o.write(0) }
    private fun be32(b: ByteArray, off: Int, v: Int) { ByteBuffer.wrap(b, off, 4).putInt(v) }
    fun rd32(b: ByteArray, off: Int) = ByteBuffer.wrap(b, off, 4).int

    fun ping() = base(PING).toByteArray()
    fun swVersion() = base(SW_VERSION).toByteArray()
    fun readDir(p: String) = base(READ_DIR).also { path(it, p) }.toByteArray()
    fun createDir(p: String) = base(CREATE_DIR).also { path(it, p) }.toByteArray()
    fun deleteFile(p: String) = base(DELETE_FILE).also { path(it, p) }.toByteArray()

    /** bytes = PCM bytes; the device file also includes the 64-byte header. */
    fun openWriter(p: String, pcmBytes: Int): ByteArray {
        val m = base(OPEN_WRITER, 4).also { path(it, p) }.toByteArray()
        be32(m, 5, pcmBytes + SAMPLE_HEADER_BYTES); return m
    }

    fun closeWriter(id: Int, pcmBytes: Int): ByteArray {
        val m = base(CLOSE_WRITER, 8).toByteArray()
        be32(m, 5, id); be32(m, 9, pcmBytes + SAMPLE_HEADER_BYTES); return m
    }

    /**
     * Block [seq] of a sample write. pcm is 16-bit native samples; they go out big-endian.
     * Block 0 starts with the 64-byte sample header. Returns message and samples consumed.
     */
    fun writeBlock(id: Int, seq: Int, pcm: ShortArray, startSample: Int, channels: Int,
                   loopStart: Int, loopEnd: Int, loop: Boolean): Pair<ByteArray, Int> {
        val o = base(WRITE_FILE, 12)
        var room = BLOCK_BYTES
        var consumed = 0
        if (seq == 0) {
            val h = ByteBuffer.allocate(SAMPLE_HEADER_BYTES)
            h.put(0); h.put((channels - 1).toByte()); h.put(0); h.put(0)
            h.putInt(pcm.size * 2); h.putInt(SAMPLE_RATE)
            h.putInt(loopStart); h.putInt(loopEnd)
            h.put((if (loop) LOOP_FWD else LOOP_NONE).toByte())
            o.write(h.array())
            room -= SAMPLE_HEADER_BYTES; consumed += SAMPLE_HEADER_BYTES
        }
        val n = minOf(room / 2, pcm.size - startSample)
        val bb = ByteBuffer.allocate(n * 2)
        for (k in 0 until n) bb.putShort(pcm[startSample + k])
        o.write(bb.array())
        consumed += n * 2
        val m = o.toByteArray()
        be32(m, 5, id); be32(m, 9, consumed); be32(m, 13, BLOCK_BYTES * seq)
        return m to n
    }

    data class Entry(val name: String, val size: Int, val isDir: Boolean)

    /** READ_DIR response: from offset 5, repeated {hash u32, size u32, write_protected u8, type u8, name\0}. */
    fun parseDir(msg: ByteArray): List<Entry> {
        val out = mutableListOf<Entry>()
        var pos = 5
        while (pos + 10 <= msg.size) {
            val size = rd32(msg, pos + 4)
            val type = msg[pos + 9].toInt().toChar()
            pos += 10
            var e = pos
            while (e < msg.size && msg[e] != 0.toByte()) e++
            out += Entry(String(msg, pos, e - pos, CP1252), size, type == 'D')
            pos = e + 1
        }
        return out
    }
}

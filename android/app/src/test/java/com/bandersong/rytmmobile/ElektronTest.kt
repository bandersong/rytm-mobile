package com.bandersong.rytmmobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Byte-for-byte check against SysEx produced by Elektroid's own C functions (conformance/ref.c). */
class ElektronTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun matchesElektroidUpload() {
        val ref = javaClass.classLoader!!.getResource("elektroid_ref.txt")!!.readText().trim().lines()
        val n = 10000
        val pcm = ShortArray(n) { ((it * 37) % 65536 - 32768).toShort() }
        val out = mutableListOf<String>()
        var seq = 0
        out += hex(Elektron.toSysex(Elektron.openWriter("/test/abc", n * 2), seq++))
        val id = 0x12345678
        var at = 0; var blk = 0
        while (at < n) {
            val (m, c) = Elektron.writeBlock(id, blk, pcm, at, 1, n - 1, n - 1, true)
            out += hex(Elektron.toSysex(m, seq++)); at += c; blk++
        }
        out += hex(Elektron.toSysex(Elektron.closeWriter(id, n * 2), seq++))
        assertEquals(ref.size, out.size)
        ref.indices.forEach { assertEquals("message $it", ref[it], out[it]) }
    }

    /** First bytes of a real ping reply from the founder's MK1 (2026-10-19 log). */
    @Test fun decodesRealMk1PingReply() {
        val raw = byteArrayOf(0xF0.toByte(), 0x00, 0x20, 0x3C, 0x10, 0x00,
            0x04, 0x00, 0x07, 0x00, 0x00, 0x01, 0x08, 0x2C, 0xF7.toByte())
        val m = Elektron.fromSysex(raw)!!
        assertEquals(0, ((m[2].toInt() and 0xFF) shl 8) or (m[3].toInt() and 0xFF)) // echoed seq 0
        assertEquals(0x81, m[4].toInt() and 0xFF)  // ping reply type
        assertEquals(8, m[5].toInt())              // device id 8 = Analog Rytm (Elektroid devices.json)
    }

    @Test fun sevenBitRoundTrip() {
        for (len in 0..40) {
            val src = ByteArray(len) { (it * 73 + 129).toByte() }
            assertArrayEquals(src, Elektron.decode7(Elektron.encode7(src)))
        }
    }

    @Test fun parsesDirListing() {
        val m = byteArrayOf(0, 0, 0, 0, 0x90.toByte()) +
            byteArrayOf(0, 0, 0, 1, 0, 0, 0x10, 0, 0, 'F'.code.toByte()) + "kick".toByteArray() + byteArrayOf(0) +
            byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0, 0, 'D'.code.toByte()) + "stems".toByteArray() + byteArrayOf(0)
        val e = Elektron.parseDir(m)
        assertEquals(listOf("kick", "stems"), e.map { it.name })
        assertEquals(listOf(false, true), e.map { it.isDir })
        assertEquals(4096, e[0].size)
    }
}

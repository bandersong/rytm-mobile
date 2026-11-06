package com.bandersong.rytmmobile

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneOffset

class WavIoTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    private fun writeWav(name: String, rate: Int, ch: Int, pcm: ShortArray): File {
        val f = File(tmp.root, name)
        WavWriter(f, rate, ch).use { it.write(pcm, pcm.size / ch) }
        return f
    }

    @Test fun headerEqualsAudioWavBytes() {
        for (n in listOf(0, 1, 4800)) for (rate in listOf(48000, 44100)) for (ch in listOf(1, 2)) {
            val samples = if (ch == 2 && n % 2 == 1) n + 1 else n
            val want = Audio.wavBytes(ShortArray(samples), rate, ch).copyOf(44)
            val got = WavIo.header(rate, ch, samples * 2L)
            assertArrayEquals("n=$samples rate=$rate ch=$ch", want, got)
        }
    }

    @Test fun headerSizesFor30MinStereo() {
        val h = le(WavIo.header(48000, 2, 345_600_000L))
        assertEquals(345_600_036, h.getInt(4))
        assertEquals(345_600_000, h.getInt(40))
        assertEquals(44, WavIo.header(48000, 1, 0).size)
    }

    @Test fun parseRoundTrip() {
        val i = WavIo.parse(WavIo.header(44100, 2, 4000))!!
        assertEquals(44100, i.rate); assertEquals(2, i.channels); assertEquals(4000L, i.dataBytes); assertEquals(1000L, i.frames)
    }

    @Test fun parseRejectsForeign() {
        val good = WavIo.header(48000, 1, 100)
        assertNotNull(WavIo.parse(good))
        assertNull("RIFX", WavIo.parse(good.copyOf().also { it[3] = 'X'.code.toByte() }))
        assertNull("fmt 18", WavIo.parse(good.copyOf().also { le(it).putInt(16, 18) }))
        assertNull("24-bit", WavIo.parse(good.copyOf().also { le(it).putShort(34, 24) }))
        assertNull("float", WavIo.parse(good.copyOf().also { le(it).putShort(20, 3) }))
        assertNull("short", WavIo.parse(good.copyOf(30)))
        assertNull("data tag moved", WavIo.parse(good.copyOf().also { it[36] = 'L'.code.toByte() }))
        assertNull("bad byte rate", WavIo.parse(good.copyOf().also { le(it).putInt(28, 5) }))
    }

    @Test fun writerPatchesOnClose() {
        val pcm = ShortArray(2000) { (it * 7 - 3000).toShort() }         // 1000 stereo frames
        val f = writeWav("a.wav", 48000, 2, pcm)
        assertEquals(4044L, f.length())
        val b = le(f.readBytes())
        assertEquals(4036, b.getInt(4)); assertEquals(4000, b.getInt(40))
        for (i in pcm.indices) assertEquals(pcm[i], b.getShort(44 + i * 2))
        assertEquals("RIFF", String(f.readBytes(), 0, 4))
    }

    @Test fun writerCountsFramesAndCloseIsIdempotent() {
        val f = File(tmp.root, "c.wav")
        val w = WavWriter(f, 48000, 2)
        w.write(ShortArray(40), 20); w.write(ShortArray(40), 20)
        assertEquals(40L, w.frames)
        w.close(); w.close()
        assertEquals(44L + 160, f.length())
        assertEquals(160, le(f.readBytes()).getInt(40))
    }

    @Test fun repairDropsPartialFrame() {
        val f = File(tmp.root, "p.wav")
        f.writeBytes(WavIo.header(48000, 2, 0) + ByteArray(4001) { 1 })
        val i = WavIo.repair(f)
        assertEquals(4000L, i.dataBytes); assertEquals(1000L, i.frames)
        assertEquals(4044L, f.length())
        assertEquals(4000, le(f.readBytes()).getInt(40)); assertEquals(4036, le(f.readBytes()).getInt(4))
    }

    @Test fun repairZeroSizesFromKill() {
        val f = File(tmp.root, "k.wav")
        f.writeBytes(WavIo.header(48000, 1, 0) + ByteArray(960) { 2 })
        val i = WavIo.repair(f)
        assertEquals(960L, i.dataBytes); assertEquals(480L, i.frames)
        assertEquals(960, le(f.readBytes()).getInt(40))
        // repairing a good file changes nothing
        val before = f.readBytes()
        WavIo.repair(f)
        assertArrayEquals(before, f.readBytes())
    }

    @Test fun repairThrowsOnForeignOrShortFiles() {
        val junk = File(tmp.root, "j.wav"); junk.writeBytes(ByteArray(100) { 7 })
        try { WavIo.repair(junk); fail("expected IOException") } catch (e: IOException) { }
        val short = File(tmp.root, "s.wav"); short.writeBytes(ByteArray(10))
        try { WavIo.repair(short); fail("expected IOException") } catch (e: IOException) { }
        assertEquals(100L, junk.length())      // untouched
    }

    @Test fun readMonoExactSizeAndValues() {
        val pcm = ShortArray(2 * 20000) { f -> if (f % 2 == 0) 16384 else 0 }     // L 0.5, R 0
        val f = writeWav("m.wav", 44100, 2, pcm)
        val mix = WavIo.readMono(f, ChannelPick.MIX)
        assertEquals(20000, mix.data.size); assertEquals(44100, mix.rate)
        assertEquals(0.25f, mix.data[0], 0f); assertEquals(0.25f, mix.data[19999], 0f)
        assertEquals(0.5f, WavIo.readMono(f, ChannelPick.LEFT).data[12345], 0f)
        assertEquals(0f, WavIo.readMono(f, ChannelPick.RIGHT).data[12345], 0f)
        // a mono file ignores the pick
        val mono = writeWav("mono.wav", 48000, 1, ShortArray(10000) { 8192 })
        assertEquals(0.25f, WavIo.readMono(mono, ChannelPick.RIGHT).data[9999], 0f)
    }

    @Test fun readMonoMatchesAudioDecodeScale() {
        val pcm = shortArrayOf(-32768, 32767, 12345, -1)
        val f = writeWav("sc.wav", 48000, 1, pcm)
        val d = WavIo.readMono(f, ChannelPick.MIX).data
        for (i in pcm.indices) assertEquals(pcm[i] / 32768f, d[i], 0f)
    }

    @Test fun scanColumnsAndPeakDb() {
        // 1200 stereo frames, 120 columns of 10 frames; column 5 holds a half-scale left peak, right channel quieter
        val pcm = ShortArray(2400)
        for (fr in 50 until 60) pcm[fr * 2] = 16384
        pcm[1000 * 2 + 1] = 3277
        val f = writeWav("sc2.wav", 48000, 2, pcm)
        val s = WavIo.scan(f)
        assertEquals(120, s.peaks.size)
        assertEquals(0.5f, s.peaks[5], 1e-6f)
        assertEquals(3277 / 32768f, s.peaks[100], 1e-6f)
        assertEquals(0f, s.peaks[0], 0f)
        assertEquals(-6.02f, s.peakDb[0], 0.01f)
        assertEquals(-20f, s.peakDb[1], 0.05f)
    }

    @Test fun scanOfEmptyFile() {
        val s = WavIo.scan(writeWav("e.wav", 48000, 1, ShortArray(0)))
        assertEquals(120, s.peaks.size); assertEquals(listOf(-90f), s.peakDb)
    }

    @Test fun chunkReaderMatchesWholeRead() {
        val pcm = ShortArray(2 * 30001) { (it * 13 - 20000).toShort() }
        val f = writeWav("cr.wav", 48000, 2, pcm)
        val got = ArrayList<Short>()
        WavIo.ChunkReader(f).use { r ->
            assertEquals(30001L, r.info.frames)
            val buf = ShortArray(4096 * 2)
            while (true) { val n = r.read(buf, 4096); if (n <= 0) break; for (i in 0 until n * 2) got.add(buf[i]) }
        }
        assertEquals(pcm.toList(), got)
    }

    @Test fun chunkReaderClampsToWhatTheFileHolds() {
        val f = File(tmp.root, "tr.wav")
        f.writeBytes(WavIo.header(48000, 1, 1000) + ByteArray(100))     // header claims 500 frames, file has 50
        WavIo.ChunkReader(f).use { assertEquals(50L, it.info.frames) }
    }
}

class TakeStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = TakeStore(tmp.newFolder("takes"))
    private val meta = TakeMeta(48000, 2, 24000, "USB · Scarlett 2i2 USB", "VOICE_RECOGNITION", 2, 1, StopReason.USER)

    /** A part file with n stereo frames of a quiet ramp and one loud sample, header sizes zero (as after a kill). */
    private fun part(s: TakeStore, id: String, frames: Int, closed: Boolean = true): File {
        val f = s.partFile(id)
        val w = WavWriter(f, 48000, 2)
        val pcm = ShortArray(frames * 2) { (it % 100).toShort() }
        if (frames > 0) pcm[2] = 16384
        w.write(pcm, frames)
        if (closed) w.close() else { w.close(); RandomAccessFile(f, "rw").use { WavIo.patchSizes(it, 0) } }
        return f
    }

    @Test fun newIdFormat() {
        val s = store()
        val id = s.newId(1793986953000L, ZoneOffset.UTC)
        assertEquals("R1106-174233", id)
        assertEquals(12, id.length)
        assertTrue(Regex("^R\\d{4}-\\d{6}$").matches(id))
        assertEquals(id, AppState.clean(id))
        assertEquals(id, AppState.clean(id).take(12))
        assertEquals(id, StemClient.fileSafe(id))
    }

    @Test fun newIdSkipsTaken() {
        val s = store()
        File(s.dir, "R1106-174233.wav").writeText("x")
        assertEquals("R1106-174234", s.newId(1793986953000L, ZoneOffset.UTC))
        s.partFile("R1106-174234").writeText("x")
        assertEquals("R1106-174235", s.newId(1793986953000L, ZoneOffset.UTC))
        File(s.dir, "R1106-174235.json").writeText("{}")
        assertEquals("R1106-174236", s.newId(1793986953000L, ZoneOffset.UTC))
    }

    @Test fun finalizeRenamesAndWritesSidecar() {
        val s = store()
        part(s, "R1106-174233", 4800)
        val t = s.finalize("R1106-174233", meta)
        assertFalse(s.partFile("R1106-174233").exists())
        assertTrue(File(s.dir, "R1106-174233.wav").exists())
        assertEquals(File(s.dir, "R1106-174233.wav"), t.file)
        assertEquals(4800L, t.frames); assertEquals(0.1, t.seconds, 1e-9)
        assertEquals(44L + 4800 * 4, t.bytes)
        assertEquals(StopReason.USER, t.stop); assertEquals("USB · Scarlett 2i2 USB", t.inputName)
        assertEquals(24000, t.preRollFrames); assertEquals(2, t.clips); assertEquals(1, t.dropouts)
        assertEquals(120, t.peaks.size); assertEquals(2, t.peakDb.size)
        assertEquals(-6.02f, t.peakDb[0], 0.01f)
        // sidecar JSON has the spec's keys
        val j = JSONObject(File(s.dir, "R1106-174233.json").readText())
        for (k in listOf("id", "created", "rate", "channels", "frames", "preRollFrames", "inputName", "source", "peakDb", "clips", "dropouts", "stop", "peaks"))
            assertTrue(k, j.has(k))
        assertFalse(j.has("savedUri"))
        assertEquals("USER", j.getString("stop"))
        // and the list gives back the same take
        assertEquals(listOf(t), s.list())
    }

    @Test fun finalizeRepairsAHeaderWithZeroSizes() {
        val s = store()
        part(s, "R1106-174233", 960, closed = false)
        val t = s.finalize("R1106-174233", meta)
        assertEquals(960L, t.frames)
        assertEquals(960 * 4, ByteBuffer.wrap(t.file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }

    @Test fun listNewestFirst() {
        val s = store()
        for ((id, created) in listOf("R0001-000001" to 100L, "R0001-000002" to 300L, "R0001-000003" to 200L)) {
            part(s, id, 48); val t = s.finalize(id, meta)
            val j = JSONObject(File(s.dir, "$id.json").readText()); j.put("created", created)
            File(s.dir, "$id.json").writeText(j.toString())
            assertEquals(id, t.id)
        }
        assertEquals(listOf("R0001-000002", "R0001-000003", "R0001-000001"), s.list().map { it.id })
    }

    @Test fun listSkipsSidecarsWithoutWavAndBrokenJson() {
        val s = store()
        part(s, "R1106-100000", 48); s.finalize("R1106-100000", meta)
        File(s.dir, "R1106-100000.wav").delete()
        File(s.dir, "broken.json").writeText("{not json")
        assertEquals(emptyList<Take>(), s.list())
    }

    @Test fun recoverOrphanPart() {
        val s = store()
        part(s, "R1106-174233", 2400, closed = false)
        val rec = s.recover()
        assertEquals(1, rec.size)
        val t = rec[0]
        assertEquals(StopReason.RECOVERED, t.stop); assertEquals("Unknown input", t.inputName)
        assertEquals(2400L, t.frames)
        assertFalse(s.partFile("R1106-174233").exists())
        assertTrue(t.file.exists())
        assertEquals(2400 * 4, ByteBuffer.wrap(t.file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        assertEquals(listOf(t), s.list())
        assertEquals("a second pass finds nothing", 0, s.recover().size)
    }

    @Test fun recoverWavWithoutSidecar() {
        val s = store()
        val f = File(s.dir, "R1106-170000.wav")
        f.writeBytes(WavIo.header(48000, 1, 0) + ByteArray(2000))
        val rec = s.recover()
        assertEquals(1, rec.size)
        assertEquals(StopReason.RECOVERED, rec[0].stop); assertEquals(1000L, rec[0].frames)
        assertTrue(File(s.dir, "R1106-170000.json").exists())
    }

    @Test fun recoverLeavesGoodTakesAlone() {
        val s = store()
        part(s, "R1106-174233", 480); s.finalize("R1106-174233", meta)
        val before = File(s.dir, "R1106-174233.json").readText()
        assertEquals(0, s.recover().size)
        assertEquals(before, File(s.dir, "R1106-174233.json").readText())
    }

    @Test fun recoverMovesGarbageToBad() {
        val s = store()
        File(s.partFile("R1106-174233").path).writeBytes(ByteArray(300) { 5 })
        File(s.dir, "R1106-170000.wav").writeBytes(ByteArray(20))
        assertEquals(0, s.recover().size)
        assertTrue(File(s.dir, "R1106-174233.wav.part.bad").exists())
        assertTrue(File(s.dir, "R1106-170000.wav.bad").exists())
        assertEquals(300L, File(s.dir, "R1106-174233.wav.part.bad").length())
        assertFalse(s.partFile("R1106-174233").exists())
        // a second garbage file of the same name never overwrites the first .bad
        File(s.partFile("R1106-174233").path).writeBytes(ByteArray(7))
        s.recover()
        assertTrue(File(s.dir, "R1106-174233.wav.part.bad").exists())
        assertTrue(File(s.dir, "R1106-174233.wav.part.bad1").exists())
        assertEquals(300L, File(s.dir, "R1106-174233.wav.part.bad").length())
    }

    @Test fun markSavedPersists() {
        val s = store()
        part(s, "R1106-174233", 480)
        val t = s.finalize("R1106-174233", meta)
        val saved = s.markSaved(t, "content://media/external/audio/media/42")
        assertEquals("content://media/external/audio/media/42", saved.savedUri)
        assertEquals("content://media/external/audio/media/42", s.list().single().savedUri)
        assertNull(t.savedUri)
    }

    @Test fun deleteRemovesBoth() {
        val s = store()
        part(s, "R1106-174233", 480)
        val t = s.finalize("R1106-174233", meta)
        s.delete(t)
        assertFalse(t.file.exists()); assertFalse(File(s.dir, "R1106-174233.json").exists())
        assertEquals(0, s.list().size)
    }

    @Test fun totalBytes() {
        val s = store()
        assertEquals(0L, s.totalBytes())
        part(s, "R1106-174233", 480); val a = s.finalize("R1106-174233", meta)
        part(s, "R1106-174234", 960); val b = s.finalize("R1106-174234", meta)
        assertEquals(a.bytes + b.bytes, s.totalBytes())
    }
}

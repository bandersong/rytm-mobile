package com.bandersong.rytmmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SaveTest {
    private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)

    @Test fun wavHeaderMatchesTheSamples() {
        val pcm = ShortArray(1000) { (it * 31 - 15000).toShort() }
        val w = Audio.wavBytes(pcm, 48000, 1)
        val b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 2000, w.size)
        assertEquals("RIFF", ascii(w, 0)); assertEquals(36 + 2000, b.getInt(4))
        assertEquals("WAVE", ascii(w, 8))
        assertEquals("fmt ", ascii(w, 12)); assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt())          // PCM
        assertEquals(1, b.getShort(22).toInt())          // mono
        assertEquals(48000, b.getInt(24))
        assertEquals(96000, b.getInt(28))                // byte rate
        assertEquals(2, b.getShort(32).toInt())          // block align
        assertEquals(16, b.getShort(34).toInt())         // bits
        assertEquals("data", ascii(w, 36)); assertEquals(2000, b.getInt(40))
        for (i in pcm.indices) assertEquals(pcm[i], b.getShort(44 + i * 2))
    }

    @Test fun wavOfNothingIsAValidEmptyFile() {
        val w = Audio.wavBytes(ShortArray(0))
        assertEquals(44, w.size)
        assertEquals(36, ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        assertEquals(0, ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }

    @Test fun jobsJsonParsesNewestFirst() {
        val l = StemClient.parseJobs("""{"jobs":[
            {"id":"a1","name":"old one","model":"vocals","status":"done","stems":["vocals","instrumental"],"created":1000},
            {"id":"b2","name":"bombs","model":"stems","status":"done","stems":["bass","drums","guitar","other","piano","vocals"],"created":2000},
            {"id":"c3","name":"","model":"demucs","status":"running","stems":[],"created":1500}]}""")
        assertEquals(listOf("b2", "c3", "a1"), l.map { it.id })
        assertEquals("bombs", l[0].name); assertEquals("stems", l[0].model); assertEquals(6, l[0].stems.size)
        assertEquals(2000L, l[0].created)
        assertEquals("track", l[1].name)          // blank name
        assertEquals(listOf("vocals", "instrumental"), l[2].stems)
    }

    @Test fun jobsJsonWithoutJobsIsEmpty() {
        assertTrue(StemClient.parseJobs("""{"jobs":[]}""").isEmpty())
        assertTrue(StemClient.parseJobs("""{}""").isEmpty())
    }

    @Test fun fileNamesMatchTheServerZip() {
        assertEquals("bombs_vocals.flac", StemClient.stemFileName("bombs", "vocals", StemFormat.FLAC))
        assertEquals("bombs_drums_full.wav", StemClient.stemFileName("bombs", "drums_full", StemFormat.WAV))
        assertEquals("AC_DC Live_bass.wav", StemClient.stemFileName("AC/DC Live", "bass", StemFormat.WAV))
        assertEquals("bombs_stems_full.zip", StemClient.zipFileName("bombs", StemFormat.FLAC))
        assertEquals("bombs_stems_rytm.zip", StemClient.zipFileName("bombs", StemFormat.WAV))
    }

    @Test fun fileSafeKeepsAccentsAndDropsBadCharacters() {
        assertEquals("Café del Mar", StemClient.fileSafe("Café del Mar"))
        assertEquals("a_b_c_d", StemClient.fileSafe("a:b?c*d"))
        assertEquals("track", StemClient.fileSafe("  ..  "))
        assertEquals(80, StemClient.fileSafe("x".repeat(200)).length)
    }

    @Test fun magicBytesTellFormatsApart() {
        assertTrue(StemClient.looksLike("fLaC".toByteArray(), "flac"))
        assertFalse(StemClient.looksLike("RIFF".toByteArray(), "flac"))   // an older server's WAV
        assertTrue(StemClient.looksLike("RIFF".toByteArray(), "wav"))
        assertTrue(StemClient.looksLike(byteArrayOf(0x50, 0x4B, 3, 4), "zip"))
        assertFalse(StemClient.looksLike("{\"de".toByteArray(), "zip"))
        assertFalse(StemClient.looksLike(ByteArray(2), "zip"))
    }

    @Test fun relativeDates() {
        val now = 1_800_000_000L
        assertEquals("just now", StemClient.ago(now - 30, now))
        assertEquals("5 min ago", StemClient.ago(now - 300, now))
        assertEquals("3 h ago", StemClient.ago(now - 3 * 3600, now))
        assertEquals("yesterday", StemClient.ago(now - 30 * 3600, now))
        assertEquals("4 days ago", StemClient.ago(now - 4 * 86400, now))
        assertEquals("", StemClient.ago(0, now))
        assertTrue(StemClient.ago(now - 30 * 86400, now).matches(Regex("\\d{1,2} [A-Z][a-z]{2}")))
    }

    @Test fun differentSplitsOfOneSongGetDifferentFolders() {
        assertEquals("bombs (Full band)", StemClient.saveFolder("bombs", "stems", "a1b2c3d4e5f6"))
        assertEquals("bombs (Vocals)", StemClient.saveFolder("bombs", "vocals", "zzz"))
        // same job saved again -> same folder (replaces); a mode the app doesn't know yet falls back to the job id
        assertEquals(StemClient.saveFolder("bombs", "stems", "a1b2c3d4e5f6"), StemClient.saveFolder("bombs", "stems", "a1b2c3d4e5f6"))
        assertEquals("bombs (a1b2c3)", StemClient.saveFolder("bombs", null, "a1b2c3d4e5f6"))
        assertEquals("AC_DC (Drum kit)", StemClient.saveFolder("AC/DC", "drumkit", null))
        assertEquals("bombs", StemClient.saveFolder("bombs", null, null))
        assertEquals("bombs (Fast)_stems_full.zip", StemClient.zipFileName(StemClient.saveFolder("bombs", "demucs", "x"), StemFormat.FLAC))
    }

    @Test fun missingStemsHeader() {
        assertEquals(listOf("vocals", "drums_full"), StemClient.parseMissing("vocals, drums_full"))
        assertTrue(StemClient.parseMissing(null).isEmpty())
        assertTrue(StemClient.parseMissing("").isEmpty())
        assertEquals(" 5 of 6 stems in the zip; no full-quality copy of vocals.", StemClient.zipNote(6, listOf("vocals")))
        assertEquals("", StemClient.zipNote(6, emptyList()))
    }

    @Test fun saveAllSaysWhichStemsCameOutAsWav() {
        assertEquals("Saved 6 stems to Music/Rytm Mobile/b (Full band) (full-quality FLAC)",
            StemClient.saveAllSummary(6, "b (Full band)", StemFormat.FLAC, emptyList()))
        val mixed = StemClient.saveAllSummary(6, "b", StemFormat.FLAC, listOf("vocals", "bass"))
        assertTrue(mixed, mixed.contains("4 FLAC") && mixed.contains("vocals, bass") && mixed.contains("those are 48k WAV"))
        assertTrue(StemClient.saveAllSummary(4, "b", StemFormat.FLAC, List(4) { "s$it" }).contains("as 48k WAV"))
    }
}

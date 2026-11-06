package com.bandersong.rytmmobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTest {
    @Test fun toLe() {
        val out = ByteArray(8)
        Pcm.toLe(shortArrayOf(1, -2, 32767, -32768), 4, out)
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte()), out)
    }

    @Test fun toLeHonoursN() {
        val out = ByteArray(8) { 9 }
        Pcm.toLe(shortArrayOf(1, 2, 3, 4), 2, out)
        assertArrayEquals(byteArrayOf(1, 0, 2, 0, 9, 9, 9, 9), out)
    }

    @Test fun monoMixIsAverage() {
        val d = FloatArray(1)
        Pcm.mono(shortArrayOf(16384, 0), 1, 2, ChannelPick.MIX, d, 0)
        assertEquals(0.25f, d[0], 0f)
    }

    @Test fun leftRightPicks() {
        val d = FloatArray(2)
        Pcm.mono(shortArrayOf(16384, 0, 0, 16384), 2, 2, ChannelPick.LEFT, d, 0)
        assertEquals(0.5f, d[0], 0f); assertEquals(0f, d[1], 0f)
        Pcm.mono(shortArrayOf(16384, 0, 0, 16384), 2, 2, ChannelPick.RIGHT, d, 0)
        assertEquals(0f, d[0], 0f); assertEquals(0.5f, d[1], 0f)
    }

    @Test fun monoIgnoresPick() {
        for (p in ChannelPick.values()) {
            val d = FloatArray(2)
            Pcm.mono(shortArrayOf(16384, -16384), 2, 1, p, d, 0)
            assertEquals(0.5f, d[0], 0f); assertEquals(-0.5f, d[1], 0f)
        }
    }

    @Test fun scaleMatchesDecode() {
        val d = FloatArray(1)
        Pcm.mono(shortArrayOf(-32768), 1, 1, ChannelPick.MIX, d, 0)
        assertEquals(-1f, d[0], 0f)
    }

    @Test fun writesAtTheOffset() {
        val d = FloatArray(4) { 7f }
        Pcm.mono(shortArrayOf(16384, 16384), 2, 1, ChannelPick.MIX, d, 2)
        assertArrayEquals(floatArrayOf(7f, 7f, 0.5f, 0.5f), d, 0f)
    }
}

class ChannelPickTest {
    @Test fun suggestions() {
        assertEquals(ChannelPick.LEFT, ChannelPick.suggest(listOf(-3f, -90f)))
        assertEquals(ChannelPick.RIGHT, ChannelPick.suggest(listOf(-90f, -10f)))
        assertEquals(ChannelPick.MIX, ChannelPick.suggest(listOf(-3f, -4f)))
        assertEquals(ChannelPick.MIX, ChannelPick.suggest(listOf(-3f)))
        assertEquals(ChannelPick.MIX, ChannelPick.suggest(emptyList()))
        assertEquals(ChannelPick.MIX, ChannelPick.suggest(listOf(-3f, -90f, -90f)))
    }

    @Test fun thresholds() {
        assertEquals(ChannelPick.LEFT, ChannelPick.suggest(listOf(-39.9f, -60.1f)))
        assertEquals(ChannelPick.MIX, ChannelPick.suggest(listOf(-40f, -61f)))
        assertEquals(ChannelPick.MIX, ChannelPick.suggest(listOf(-39.9f, -60f)))
    }
}

class DbAndWordsTest {
    private fun reading(hold: List<Float>, clipped: Boolean = false) =
        MeterReading(hold, hold, clipped, 0, 0, 0, 0, 48000)

    @Test fun db() {
        assertEquals(-90f, Db.of(0f), 0f)
        assertEquals(0f, Db.of(1f), 0f)
        assertEquals(-6.02f, Db.of(0.5f), 0.01f)
        assertEquals(-90f, Db.of(-1f), 0f)
        assertEquals(-90f, Db.of(1e-9f), 0f)
    }

    @Test fun wordsBoundaries() {
        assertEquals("no signal", MeterWords.of(reading(listOf(-60.1f))))
        assertEquals("low", MeterWords.of(reading(listOf(-60f))))
        assertEquals("low", MeterWords.of(reading(listOf(-30.1f))))
        assertEquals("good", MeterWords.of(reading(listOf(-30f))))
        assertEquals("good", MeterWords.of(reading(listOf(-6f))))
        assertEquals("hot", MeterWords.of(reading(listOf(-5.9f))))
        assertEquals("clipping", MeterWords.of(reading(listOf(-80f), clipped = true)))
        assertEquals("no signal", MeterWords.of(MeterReading.EMPTY))
        // the loudest channel decides
        assertEquals("good", MeterWords.of(reading(listOf(-80f, -12f))))
    }

    @Test fun scaleLit() {
        assertEquals(0, MeterScale.lit(-48f))
        assertEquals(24, MeterScale.lit(0f))
        assertEquals(18, MeterScale.lit(-12f))
        assertEquals(0, MeterScale.lit(-90f))
        assertEquals(24, MeterScale.lit(6f))
        assertEquals(0, MeterScale.lit(-47.9f))
        assertEquals(1, MeterScale.lit(-46f))
    }

    @Test fun scaleZones() {
        assertEquals(0, MeterScale.zone(0))
        assertEquals(0, MeterScale.zone(17))
        assertEquals(1, MeterScale.zone(18))
        assertEquals(1, MeterScale.zone(21))
        assertEquals(2, MeterScale.zone(22))
        assertEquals(2, MeterScale.zone(23))
        assertEquals(24, MeterScale.SEGMENTS)
    }
}

class LevelMeterTest {
    private val rate = 48000
    private fun chunk(v: Short, ch: Int = 1, frames: Int = 960) = ShortArray(frames * ch) { v }

    @Test fun fullScaleIsAboutZeroDb() {
        val m = LevelMeter(1, rate)
        m.feed(chunk(32767), 960)
        assertTrue(m.snapshot().peakDb[0] > -0.01f)
    }

    @Test fun silenceIsFloor() {
        val m = LevelMeter(1, rate)
        m.feed(chunk(0), 960)
        assertEquals(-90f, m.snapshot().peakDb[0], 0f)
        assertEquals(-90f, m.snapshot().holdDb[0], 0f)
    }

    @Test fun clipLatchesCountsAndResets() {
        val m = LevelMeter(1, rate)
        m.feed(chunk(1000), 960)
        assertFalse(m.snapshot().clipped)
        m.feed(chunk(32767), 960)
        assertTrue(m.snapshot().clipped); assertEquals(1, m.snapshot().clipCount)
        m.feed(chunk(1000), 960)
        assertTrue("latched", m.snapshot().clipped)
        m.feed(chunk(32767), 960)
        assertEquals(2, m.snapshot().clipCount)
        m.resetClip()
        assertTrue("reset is consumed by the next feed", m.snapshot().clipped)
        m.feed(chunk(1000), 960)
        assertFalse(m.snapshot().clipped)
        assertEquals("the count keeps counting", 2, m.snapshot().clipCount)
        // exactly at the threshold counts; just under does not
        val m2 = LevelMeter(1, rate)
        m2.feed(chunk(32000), 960)   // 0.9765
        assertFalse(m2.snapshot().clipped)
        m2.feed(chunk(-32768), 960)
        assertTrue(m2.snapshot().clipped)
    }

    @Test fun releaseIs24DbPerSecond() {
        val m = LevelMeter(1, rate)
        m.feed(chunk(32767), 960)
        repeat(50) { m.feed(chunk(0), 960) }
        assertEquals(-24f, m.snapshot().peakDb[0], 0.5f)
    }

    @Test fun releaseNeverGoesBelowFloorAndRisesInstantly() {
        val m = LevelMeter(1, rate)
        repeat(500) { m.feed(chunk(0), 960) }
        assertEquals(-90f, m.snapshot().peakDb[0], 0f)
        m.feed(chunk(16384), 960)
        assertEquals(-6.02f, m.snapshot().peakDb[0], 0.01f)
    }

    @Test fun holdLasts1500ms() {
        val m = LevelMeter(1, rate)
        m.feed(chunk(16384), 960)
        repeat(70) { m.feed(chunk(0), 960) }              // 1.4 s later
        assertEquals(-6.02f, m.snapshot().holdDb[0], 0.01f)
        repeat(10) { m.feed(chunk(0), 960) }              // 1.6 s later
        assertEquals(-90f, m.snapshot().holdDb[0], 0f)
    }

    @Test fun perChannelPeaks() {
        val m = LevelMeter(2, rate)
        val inter = ShortArray(960 * 2) { if (it % 2 == 0) 16384 else 3277 }   // L -6 dB, R about -20 dB
        m.feed(inter, 960)
        val p = m.snapshot().peakDb
        assertEquals(2, p.size)
        assertEquals(-6.02f, p[0], 0.01f)
        assertEquals(-20f, p[1], 0.05f)
    }

    @Test fun silentMsCountsAndResets() {
        val m = LevelMeter(1, rate)
        repeat(150) { m.feed(chunk(0), 960) }
        assertEquals(3000L, m.snapshot().silentMs)
        m.feed(chunk(1), 960)
        assertEquals(0L, m.snapshot().silentMs)
    }

    @Test fun silentOnlyWhenEveryChannelIsZero() {
        val m = LevelMeter(2, rate)
        m.feed(ShortArray(960 * 2) { if (it % 2 == 0) 0 else 5 }, 960)
        assertEquals(0L, m.snapshot().silentMs)
    }

    @Test fun snapshotCarriesFramesAndTake() {
        val m = LevelMeter(1, rate)
        m.feed(chunk(10), 960); m.feed(chunk(10), 960)
        val s = m.snapshot(1234)
        assertEquals(1920L, s.frames); assertEquals(1234L, s.takeFrames); assertEquals(rate, s.rate)
    }
}

class PreRollTest {
    private fun ramp(from: Int, n: Int) = ShortArray(n) { (from + it).toShort() }
    private fun drained(p: PreRoll, ch: Int = 1): ShortArray {
        var out = ShortArray(0)
        p.drain { d, n -> assertEquals(d.size, n * ch); out += d }
        return out
    }

    @Test fun keepsLastCapacityInOrder() {
        val p = PreRoll(24000, 1)
        p.write(ramp(0, 30000), 30000)
        assertEquals(24000, p.frames)
        val d = drained(p)
        assertEquals(24000, d.size)
        for (i in d.indices) assertEquals((6000 + i).toShort(), d[i])
    }

    @Test fun wrapsWith960Chunks() {
        val p = PreRoll(24000, 1)
        var next = 0
        repeat(30) { p.write(ramp(next, 960), 960); next += 960 }   // 28800 frames, ring wraps once
        val d = drained(p)
        assertEquals(24000, d.size)
        for (i in d.indices) assertEquals((4800 + i).toShort(), d[i])
    }

    @Test fun shorterThanCapacityDrainsAll() {
        val p = PreRoll(24000, 2)
        p.write(ShortArray(2000) { it.toShort() }, 1000)
        assertEquals(1000, p.frames)
        val d = drained(p, 2)
        assertEquals(2000, d.size)
        for (i in d.indices) assertEquals(i.toShort(), d[i])
    }

    @Test fun drainEmpties() {
        val p = PreRoll(100, 1)
        p.write(ramp(0, 50), 50)
        drained(p)
        assertEquals(0, p.frames)
        assertEquals(0, drained(p).size)
        // and it is reusable
        p.write(ramp(7, 3), 3)
        assertArrayEquals(shortArrayOf(7, 8, 9), drained(p))
    }

    @Test fun stereoFramesStayTogether() {
        val p = PreRoll(3, 2)
        p.write(shortArrayOf(1, 10, 2, 20, 3, 30, 4, 40), 4)
        assertArrayEquals(shortArrayOf(2, 20, 3, 30, 4, 40), drained(p, 2))
    }
}

class OverviewTest {
    @Test fun keepsLast200() {
        val o = Overview(10, 200)
        // 250 blocks, block k has peak (k+1)/250 of full scale
        for (k in 0 until 250) o.feed(ShortArray(10) { (32768 * (k + 1) / 251).toShort() }, 10, 1)
        val l = o.last()
        assertEquals(200, l.size)
        assertEquals(51f / 251f, l[0], 1e-3f)
        assertEquals(250f / 251f, l[199], 1e-3f)
    }

    @Test fun newestLast() {
        val o = Overview(4, 200)
        o.feed(ShortArray(4) { 8192 }, 4, 1)
        o.feed(ShortArray(4) { 16384 }, 4, 1)
        assertArrayEquals(floatArrayOf(0.25f, 0.5f), o.last(), 0f)
    }

    @Test fun blocksSpanChunksAndAllChannels() {
        val o = Overview(4, 10)
        o.feed(shortArrayOf(100, 200, 100, 200), 2, 2)        // 2 frames of a 4-frame block
        assertEquals(0, o.last().size)
        o.feed(shortArrayOf(100, 16384, 100, 100), 2, 2)
        assertArrayEquals(floatArrayOf(0.5f), o.last(), 0f)
    }

    @Test fun lastIsACopy() {
        val o = Overview(1, 5)
        o.feed(shortArrayOf(16384), 1, 1)
        o.last()[0] = 99f
        assertEquals(0.5f, o.last()[0], 0f)
    }
}

class DropoutTest {
    private val ms = 1_000_000L

    @Test fun onTimeIsClean() {
        val d = DropoutCheck(48000)
        for (i in 1..50) d.onFrames(960, i * 20 * ms)
        assertEquals(0, d.dropouts)
    }

    @Test fun gapOver150msCounts() {
        val d = DropoutCheck(48000)
        for (i in 1..10) d.onFrames(960, i * 20 * ms)
        d.onFrames(960, 10 * 20 * ms + 300 * ms)
        assertEquals(1, d.dropouts)
        // re-baselined: on-time frames after the gap are clean
        for (i in 1..20) d.onFrames(960, 500 * ms + i * 20 * ms)
        assertEquals(1, d.dropouts)
    }

    @Test fun smallJitterIsTolerated() {
        val d = DropoutCheck(48000)
        for (i in 1..20) d.onFrames(960, i * 20 * ms)
        d.onFrames(960, 20 * 20 * ms + 100 * ms)
        assertEquals(0, d.dropouts)
    }
}

class TrimTest {
    @Test fun boundsHugBlockWithPadding() {
        val x = FloatArray(14400)
        for (i in 4800 until 9600) x[i] = 0.5f
        val (s, e) = Trim.soundBounds(x, 48000)
        assertEquals(0.3f, s, 1e-4f)
        assertEquals(0.7f, e, 1e-4f)
    }

    @Test fun allSilenceIsWhole() {
        assertEquals(0f to 1f, Trim.soundBounds(FloatArray(1000), 48000))
        // under the threshold is silence too (-50 dB = 0.00316)
        assertEquals(0f to 1f, Trim.soundBounds(FloatArray(1000) { 0.003f }, 48000))
    }

    @Test fun emptyIsWhole() {
        assertEquals(0f to 1f, Trim.soundBounds(FloatArray(0), 48000))
    }

    @Test fun padClampsAtTheEdges() {
        val x = FloatArray(1000) { 0.5f }
        assertEquals(0f to 1f, Trim.soundBounds(x, 48000))
    }

    @Test fun tooShortToUseIsWhole() {
        val x = FloatArray(1_000_000)
        x[500_000] = 0.9f
        // 1 sample + 2 * 480 pad of 1e6 = 0.00096 < 0.002
        assertEquals(0f to 1f, Trim.soundBounds(x, 48000))
    }
}

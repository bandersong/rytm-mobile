package com.bandersong.rytmmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** A clock the tests advance by hand; sleep just moves it forward (rounded up to whole ms, like the real call). */
private class TestClock {
    var nanos = 0L
    val clock: () -> Long = { nanos }
    val sleep: (Long) -> Unit = { ms -> nanos += ms * 1_000_000L }
}

private fun peak(buf: ShortArray, n: Int) = (0 until n).maxOf { abs(buf[it].toInt()) }

class FakeSourceTest {
    private val buf = ShortArray(960 * 2)

    private fun src(spec: FakeSpec, c: TestClock = TestClock()) = FakeSource(spec, 48000, c.clock, c.sleep) to c

    @Test fun pacesToClock() {
        val (s, c) = src(FakeSpec())
        repeat(50) { assertEquals(960, s.read(buf, 960)) }
        // 50 chunks of 20 ms is one second of real time, to within the 1 ms sleep granularity
        assertTrue("clock at ${c.nanos}", c.nanos in 1_000_000_000L..1_001_000_000L)
        // a reader that is already late isn't slowed down
        c.nanos += 5_000_000_000L
        val before = c.nanos
        s.read(buf, 960)
        assertEquals(before, c.nanos)
    }

    @Test fun levelMinus12() {
        val (s, _) = src(FakeSpec(hz = 1000f))
        s.read(buf, 960)
        val want = Math.pow(10.0, -12.0 / 20) * 32767
        assertEquals(want, peak(buf, 960).toDouble(), 3.0)
        assertTrue(abs(peak(buf, 960) - 0.2512 * 32768) < 12)
    }

    @Test fun sineIsContinuousAcrossReads() {
        val (s, _) = src(FakeSpec(hz = 100f, levelDb = 0f))
        val all = ShortArray(960 * 4)
        for (k in 0 until 4) { s.read(buf, 960); System.arraycopy(buf, 0, all, k * 960, 960) }
        val w = 2.0 * Math.PI * 100 / 48000
        for (i in all.indices step 97) assertEquals(32767 * Math.sin(w * i), all[i].toDouble(), 1.0)
    }

    @Test fun silenceAfter() {
        val (s, _) = src(FakeSpec(silenceAt = 0.5f))
        var loud = 0; var quiet = 0
        repeat(50) { k ->
            s.read(buf, 960)
            if (peak(buf, 960) == 0) quiet++ else loud++
            if (k < 25) assertTrue("chunk $k", peak(buf, 960) > 0) else assertEquals("chunk $k", 0, peak(buf, 960))
        }
        assertEquals(25, loud); assertEquals(25, quiet)
    }

    @Test fun deadReturnsNegativeOnlyAfterMarkTake() {
        val (s, _) = src(FakeSpec(deadAt = 0.1f))
        repeat(20) { assertEquals(960, s.read(buf, 960)) }          // 0.4 s, past t, never marked: still alive
        s.markTake()
        assertEquals(960, s.read(buf, 960))                          // take time 0
        var neg = 0
        repeat(10) { val n = s.read(buf, 960); if (n < 0) { assertEquals(-6, n); neg++ } }
        assertTrue(neg > 0)
        // dead from t = 0.1 s of take time: 5 chunks of audio, then errors
        val (s2, _) = src(FakeSpec(deadAt = 0.1f))
        s2.markTake()
        val results = (0 until 8).map { s2.read(buf, 960) }
        assertEquals(listOf(960, 960, 960, 960, 960, -6, -6, -6), results)
    }

    @Test fun clipCountsFromMarkTake() {
        val (s, _) = src(FakeSpec(clipAt = 0.1f, levelDb = -12f))
        repeat(10) { s.read(buf, 960); assertTrue("never marked, so no clip", peak(buf, 960) < 32000) }
        val (s2, _) = src(FakeSpec(clipAt = 0.1f, levelDb = -12f))
        s2.markTake()
        val peaks = (0 until 15).map { s2.read(buf, 960); peak(buf, 960) }
        // 0.1 s = chunks 5..14 (0.2 s burst) are full-scale squares; before that, -12 dB
        for (k in 0 until 5) assertTrue("chunk $k ${peaks[k]}", peaks[k] < 10000)
        for (k in 5 until 14) assertEquals("chunk $k", 32767, peaks[k])
        // the burst clips the meter
        val m = LevelMeter(1, 48000)
        val (s3, _) = src(FakeSpec(clipAt = 0.0f)); s3.markTake(); s3.read(buf, 960); m.feed(buf, 960)
        assertTrue(m.snapshot().clipped)
    }

    @Test fun deadRightZeroesRight() {
        val (s, _) = src(FakeSpec(stereo = true, deadRight = true, levelDb = -6f))
        assertEquals(2, s.channels)
        s.read(buf, 960)
        var l = 0; var r = 0
        for (f in 0 until 960) { l = maxOf(l, abs(buf[f * 2].toInt())); r = maxOf(r, abs(buf[f * 2 + 1].toInt())) }
        assertTrue(l > 10000); assertEquals(0, r)
    }

    @Test fun unpluggedFlagAfterMarkTake() {
        val (s, c) = src(FakeSpec(unplugAt = 0.1f))
        repeat(20) { s.read(buf, 960) }
        assertFalse("not before markTake", s.unplugged)
        s.markTake()
        assertFalse(s.unplugged)
        repeat(4) { assertEquals(960, s.read(buf, 960)); assertFalse(s.unplugged) }
        assertEquals(960, s.read(buf, 960))                          // the chunk that reaches 0.1 s
        assertTrue(s.unplugged)
        val t0 = c.nanos
        assertEquals("a dead cable returns 0 frames", 0, s.read(buf, 960))
        assertTrue("and still blocks for one chunk", c.nanos - t0 >= 20_000_000L)
        assertTrue(s.unplugged)
    }

    @Test fun reportsItsIdentity() {
        val (s, _) = src(FakeSpec())
        assertEquals(1, s.channels); assertEquals(48000, s.rate); assertEquals(-1, s.routedId); assertEquals("Fake input", s.routedName)
        assertEquals(2, src(FakeSpec(stereo = true)).first.channels)
    }
}

class CaptureLoopTest {
    private val rate = 48000

    private class Rig(val src: PcmSource, val maxQueue: Int = Int.MAX_VALUE) {
        val sunk = ArrayList<ShortArray>()
        val sunkFrames get() = sunk.sumOf { it.size } / src.channels
        val meter = LevelMeter(src.channels, src.rate)
        val preRoll = PreRoll(FormatLadder.PREROLL_MS * src.rate / 1000, src.channels)
        val loop = CaptureLoop(src, preRoll, meter, Overview(4800), DropoutCheck(src.rate)) { buf, _ ->
            if (sunk.size < maxQueue) { sunk.add(buf); true } else false
        }
        var now = 0L
        fun step(): Int { now += 20_000_000L; return loop.step(now) }
    }

    private fun fake(spec: FakeSpec = FakeSpec()): PcmSource { val c = TestClock(); return FakeSource(spec, 48000, c.clock, c.sleep) }

    @Test fun preRollPrecedesTake() {
        val r = Rig(fake())
        repeat(30) { r.step() }
        assertEquals("nothing is kept while armed", 0, r.sunkFrames)
        r.loop.beginTake(1_000_000)
        assertTrue(r.loop.taking)
        assertEquals(24000, r.loop.preRollFrames)
        repeat(10) { r.step() }
        assertEquals(24000 + 9600, r.sunkFrames)
        assertEquals(24000 + 9600L, r.loop.takeFrames)
        assertEquals(24000, r.loop.preRollFrames)
        // the first sunk array is the pre-roll
        assertEquals(24000, r.sunk[0].size)
    }

    @Test fun stopsAtExactMaxFrame() {
        val r = Rig(fake())
        r.loop.beginTake(1000)
        assertFalse(r.loop.reachedMax)
        repeat(5) { r.step() }
        assertEquals(1000, r.sunkFrames)
        assertEquals(1000L, r.loop.takeFrames)
        assertTrue(r.loop.reachedMax)
        assertEquals("the last chunk was cut to fit", 40, r.sunk.last().size)
    }

    @Test fun maxFramesCountsThePreRoll() {
        val r = Rig(fake())
        repeat(30) { r.step() }
        r.loop.beginTake(30000)
        repeat(8) { r.step() }     // 24000 of pre-roll, then 6 full chunks (29760) and the 7th cut to 240
        assertEquals(30000, r.sunkFrames)
        assertEquals(240, r.sunk.last().size)
        assertTrue(r.loop.reachedMax)
    }

    @Test fun afterEndTakeFramesGoBackToPreRoll() {
        val r = Rig(fake())
        r.loop.beginTake(1_000_000)
        repeat(3) { r.step() }
        r.loop.endTake()
        assertFalse(r.loop.taking)
        val kept = r.sunkFrames
        repeat(5) { r.step() }
        assertEquals(kept, r.sunkFrames)
        assertEquals(4800, r.preRoll.frames)
    }

    @Test fun negativeReadReturned() {
        val dead = object : PcmSource {
            override val rate = 48000; override val channels = 1; override val routedId: Int? = null; override val routedName = ""
            override fun read(dst: ShortArray, frames: Int) = -3
            override fun stopReading() {}
            override fun close() {}
        }
        val r = Rig(dead)
        assertEquals(-3, r.step())
        assertEquals(0L, r.meter.snapshot().frames)
    }

    @Test fun fullQueueCountsDrops() {
        val r = Rig(fake(), maxQueue = 2)
        r.loop.beginTake(1_000_000)
        repeat(5) { r.step() }
        assertEquals(2, r.sunk.size)
        assertEquals(3, r.loop.queueDrops)
        assertEquals(5 * 960L, r.loop.takeFrames)
    }

    @Test fun meterFedWhileArmed() {
        val r = Rig(fake())
        repeat(5) { r.step() }
        val s = r.meter.snapshot()
        assertEquals(4800L, s.frames)
        assertTrue(s.peakDb[0] > -13f && s.peakDb[0] < -11f)
        assertFalse(r.loop.taking)
    }

    @Test fun zeroReadsCounted() {
        val unplugged = fake(FakeSpec(unplugAt = 0f)).also { it.markTake() }
        val r = Rig(unplugged)
        repeat(3) { assertEquals(0, r.step()) }
        assertEquals(3, r.loop.zeroReads)
    }

    @Test fun aGoodReadResetsZeroReads() {
        var calls = 0
        val flaky = object : PcmSource {
            override val rate = 48000; override val channels = 1; override val routedId: Int? = null; override val routedName = ""
            override fun read(dst: ShortArray, frames: Int): Int = if (++calls == 3) { dst.fill(5, 0, frames); frames } else 0
            override fun stopReading() {}
            override fun close() {}
        }
        val r = Rig(flaky)
        r.step(); r.step()
        assertEquals(2, r.loop.zeroReads)
        r.step()
        assertEquals(0, r.loop.zeroReads)
    }

    @Test fun chunkIsTwentyMs() {
        assertEquals(960, Rig(fake()).loop.chunk)
        val s44 = FakeSource(FakeSpec(), 44100, TestClock().clock, TestClock().sleep)
        assertEquals(882, Rig(s44).loop.chunk)
    }

    @Test fun pollMaxFiresOncePerTakeEvenOnTheSameLoop() {
        val r = Rig(fake())
        assertFalse("not while armed", r.loop.pollMax())
        for (take in 1..3) {
            r.loop.beginTake(1000)
            assertFalse(r.loop.pollMax())
            repeat(5) { r.step() }
            assertTrue("take $take reached its limit", r.loop.reachedMax)
            assertTrue("take $take reports it", r.loop.pollMax())
            repeat(3) { r.step(); assertFalse("but only once", r.loop.pollMax()) }
            r.loop.endTake()
            assertFalse(r.loop.pollMax())
        }
    }

    @Test fun sinkGetsCopiesNotTheReadBuffer() {
        var k = 0
        val src = object : PcmSource {
            override val rate = 48000; override val channels = 1
            override val routedId: Int? = null; override val routedName = ""
            override fun read(dst: ShortArray, frames: Int): Int { k++; dst.fill(k.toShort(), 0, frames); return frames }
            override fun stopReading() {}
            override fun close() {}
        }
        val r = Rig(src)
        r.loop.beginTake(1_000_000)
        repeat(4) { r.step() }
        assertEquals(4, r.sunk.size)
        for (i in 0 until 4) {
            assertEquals("chunk $i keeps the audio it was handed", (i + 1).toShort(), r.sunk[i][0])
            assertEquals((i + 1).toShort(), r.sunk[i][959])
            for (j in 0 until i) assertTrue("chunks $i and $j are different arrays", r.sunk[i] !== r.sunk[j])
        }
    }

    @Test fun stereoFramesAreKeptInterleaved() {
        val r = Rig(fake(FakeSpec(stereo = true)))
        r.loop.beginTake(1_000_000)
        r.step()
        assertEquals(960 * 2, r.sunk[0].size)
        assertEquals(960, r.sunkFrames)
    }
}

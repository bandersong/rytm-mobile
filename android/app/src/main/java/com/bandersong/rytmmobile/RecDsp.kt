package com.bandersong.rytmmobile

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Pure recording DSP (no android.* imports, so the JVM tests run it directly).

/** Which channel of a multichannel take becomes the mono Chop/Listen source. */
enum class ChannelPick {
    MIX, LEFT, RIGHT;

    companion object {
        /** Exactly two channels where one is dead (< -60 dB) and the other is live (> -40 dB): use the live side. */
        fun suggest(peakDb: List<Float>): ChannelPick {
            if (peakDb.size != 2) return MIX
            val l = peakDb[0]; val r = peakDb[1]
            return when {
                l < -60f && r > -40f -> RIGHT
                r < -60f && l > -40f -> LEFT
                else -> MIX
            }
        }
    }
}

object Pcm {
    /** n samples to 2n little-endian bytes. */
    fun toLe(src: ShortArray, n: Int, dst: ByteArray) {
        var o = 0
        for (i in 0 until n) {
            val v = src[i].toInt()
            dst[o++] = v.toByte()
            dst[o++] = (v shr 8).toByte()
        }
    }

    /**
     * Interleaved 16-bit frames to mono floats at dst[dstOff...]. MIX is (sum of s/32768f) / ch, the same scale
     * and average as Audio.decode; LEFT / RIGHT take that channel alone. ch == 1 ignores the pick.
     */
    fun mono(inter: ShortArray, frames: Int, ch: Int, pick: ChannelPick, dst: FloatArray, dstOff: Int) {
        if (ch == 1) {
            for (f in 0 until frames) dst[dstOff + f] = inter[f] / 32768f
            return
        }
        when (pick) {
            ChannelPick.MIX -> for (f in 0 until frames) {
                var s = 0f
                for (c in 0 until ch) s += inter[f * ch + c] / 32768f
                dst[dstOff + f] = s / ch
            }
            ChannelPick.LEFT -> for (f in 0 until frames) dst[dstOff + f] = inter[f * ch] / 32768f
            ChannelPick.RIGHT -> for (f in 0 until frames) dst[dstOff + f] = inter[f * ch + 1] / 32768f
        }
    }
}

object Db {
    const val FLOOR = -90f
    fun of(lin: Float): Float = if (lin <= 0f) FLOOR else max(FLOOR, (20.0 * log10(lin.toDouble())).toFloat())
}

data class MeterReading(
    val peakDb: List<Float>, val holdDb: List<Float>, val clipped: Boolean, val clipCount: Int,
    val silentMs: Long, val frames: Long, val takeFrames: Long, val rate: Int,
) {
    companion object { val EMPTY = MeterReading(emptyList(), emptyList(), false, 0, 0, 0, 0, 0) }
}

/** Per-channel peak meter with a fixed release, a peak hold, a latched clip flag and a digital-silence timer. */
class LevelMeter(
    private val channels: Int, private val rate: Int,
    private val releaseDbPerSec: Float = 24f, private val holdMs: Int = 1500, private val clipAt: Float = 0.999f,
) {
    private val shown = FloatArray(channels) { Db.FLOOR }
    // hold history: per chunk, the end time (in frames) and each channel's peak dB
    private val histT = ArrayDeque<Long>()
    private val histDb = ArrayDeque<FloatArray>()
    private var totalFrames = 0L
    private var clipped = false
    private var clipCount = 0
    private var silentMs = 0L
    @Volatile private var clearClip = false

    fun feed(inter: ShortArray, frames: Int) {
        if (clearClip) { clearClip = false; clipped = false }
        if (frames <= 0) return
        val chunkSec = frames.toFloat() / rate
        val chunkDb = FloatArray(channels)
        var anyClip = false
        var anyNonZero = false
        for (c in 0 until channels) {
            var pk = 0
            var i = c
            for (f in 0 until frames) {
                val v = inter[i]
                if (v.toInt() != 0) anyNonZero = true
                val a = abs(v.toInt())
                if (a > pk) pk = a
                i += channels
            }
            val lin = pk / 32768f
            if (lin >= clipAt) anyClip = true
            chunkDb[c] = Db.of(lin)
            shown[c] = max(chunkDb[c], shown[c] - releaseDbPerSec * chunkSec)
        }
        if (anyClip) { clipped = true; clipCount += 1 }
        silentMs = if (anyNonZero) 0L else silentMs + frames * 1000L / rate
        totalFrames += frames
        histT.addLast(totalFrames); histDb.addLast(chunkDb)
        while (histT.isNotEmpty() && (totalFrames - histT.first()) * 1000L >= holdMs.toLong() * rate) {
            histT.removeFirst(); histDb.removeFirst()
        }
    }

    fun snapshot(takeFrames: Long = 0): MeterReading {
        val hold = List(channels) { c -> histDb.maxOfOrNull { it[c] } ?: Db.FLOOR }
        return MeterReading(shown.toList(), hold, clipped, clipCount, silentMs, totalFrames, takeFrames, rate)
    }

    /** Thread-safe: consumed on the next feed. */
    fun resetClip() { clearClip = true }
}

object MeterWords {
    fun of(r: MeterReading): String {
        if (r.clipped) return "clipping"
        val p = r.holdDb.maxOrNull() ?: Db.FLOOR
        return when {
            p < -60f -> "no signal"
            p < -30f -> "low"
            p <= -6f -> "good"
            else -> "hot"
        }
    }
}

object MeterScale {
    const val SEGMENTS = 24
    fun lit(peakDb: Float): Int = floor((peakDb + 48f) / 2f).toInt().coerceIn(0, SEGMENTS)
    /** 0 = P.Audio, 1 = P.Cv, 2 = P.Signal, by the segment's top edge in dB. */
    fun zone(segment: Int): Int {
        val top = -48 + 2 * (segment + 1)
        return when {
            top <= -12 -> 0
            top <= -3 -> 1
            else -> 2
        }
    }
}

/** Always-running ring of the most recent frames, drained chronologically when a take starts. */
class PreRoll(private val capacityFrames: Int, private val channels: Int) {
    private val buf = ShortArray(capacityFrames * channels)
    private var head = 0          // next write position, in frames
    var frames: Int = 0; private set

    fun write(inter: ShortArray, frames: Int) {
        if (capacityFrames == 0 || frames <= 0) return
        // only the last capacityFrames can survive
        val skip = max(0, frames - capacityFrames)
        for (f in skip until frames) {
            System.arraycopy(inter, f * channels, buf, head * channels, channels)
            head++; if (head == capacityFrames) head = 0
        }
        this.frames = min(capacityFrames, this.frames + (frames - skip))
    }

    /** Hands the held frames, oldest first, to the sink as one fresh array, then empties the ring. */
    fun drain(sink: (ShortArray, Int) -> Unit) {
        val n = frames
        if (n == 0) return
        val out = ShortArray(n * channels)
        val start = (head - n + capacityFrames) % capacityFrames
        val firstPart = min(n, capacityFrames - start)
        System.arraycopy(buf, start * channels, out, 0, firstPart * channels)
        if (firstPart < n) System.arraycopy(buf, 0, out, firstPart * channels, (n - firstPart) * channels)
        frames = 0; head = 0
        sink(out, n)
    }
}

/** Rolling block peaks (0..1) for the live waveform. */
class Overview(private val blockFrames: Int, private val keep: Int = 200) {
    private val ring = FloatArray(keep)
    private var count = 0          // blocks stored, up to keep
    private var next = 0
    private var blockPeak = 0f
    private var blockFill = 0

    fun feed(inter: ShortArray, frames: Int, ch: Int) {
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                val a = abs(inter[f * ch + c].toInt()) / 32768f
                if (a > blockPeak) blockPeak = a
            }
            blockFill++
            if (blockFill == blockFrames) {
                ring[next] = blockPeak; next = (next + 1) % keep; if (count < keep) count++
                blockPeak = 0f; blockFill = 0
            }
        }
    }

    /** Oldest to newest, a copy. */
    fun last(): FloatArray = FloatArray(count) { ring[(next - count + it + keep) % keep] }
}

/** Counts stretches where the source delivered fewer frames than real time says it should have. */
class DropoutCheck(private val rate: Int, private val toleranceMs: Int = 150) {
    private var t0 = 0L
    private var received = 0L
    private var started = false
    var dropouts: Int = 0; private set

    fun onFrames(n: Int, nowNanos: Long) {
        if (!started) { started = true; t0 = nowNanos - n * 1_000_000_000L / rate; received = 0 }
        received += n
        val expected = (nowNanos - t0) * rate / 1_000_000_000L
        if (expected - received > toleranceMs.toLong() * rate / 1000L) {
            dropouts++
            t0 = nowNanos - n * 1_000_000_000L / rate; received = n.toLong()
        }
    }
}

object Trim {
    /** Fractions (start, end) of the sound in [data], padded; the whole clip when nothing (or almost nothing) is found. */
    fun soundBounds(data: FloatArray, rate: Int, thresholdDb: Float = -50f, padMs: Int = 10): Pair<Float, Float> {
        if (data.isEmpty()) return 0f to 1f
        val thr = 10.0.pow(thresholdDb / 20.0).toFloat()
        var first = -1; var last = -1
        for (i in data.indices) if (abs(data[i]) >= thr) { first = i; break }
        if (first < 0) return 0f to 1f
        for (i in data.indices.reversed()) if (abs(data[i]) >= thr) { last = i; break }
        val pad = padMs * rate / 1000
        val size = data.size.toFloat()
        val start = max(0, first - pad) / size
        val end = min(data.size, last + 1 + pad) / size
        if (end - start < 0.002f) return 0f to 1f
        return start to end
    }
}

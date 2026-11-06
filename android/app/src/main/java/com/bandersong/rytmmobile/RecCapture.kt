package com.bandersong.rytmmobile

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// Capture plumbing with no android.* imports: the PCM source contract, a deterministic fake source for
// emulator tests, and the loop that feeds meter / pre-roll / take sink from one source.

interface PcmSource : AutoCloseable {
    val rate: Int
    val channels: Int
    val routedId: Int?
    val routedName: String
    /** Frames read (>= 0) or an error code < 0. */
    fun read(dst: ShortArray, frames: Int): Int
    /** Unblock a pending read if possible (AudioRecord.stop). */
    fun stopReading()
    /** Called by CaptureLoop.beginTake; FakeSource uses it as the take clock. */
    fun markTake() {}
    /** FakeSource only. */
    val unplugged: Boolean get() = false
}

/**
 * Deterministic stand-in for a microphone (debug builds: the rec_fake intent extra).
 * Paces reads to real time on [clock]. The sine is amplitude 10^(levelDb/20) of full scale.
 * silence@t counts from construction. clip@t, unplug@t and dead@t count from the last markTake()
 * and are inactive before the first one. Time is measured in delivered frames, so it is exact.
 */
class FakeSource(
    private val spec: FakeSpec,
    override val rate: Int = 48000,
    private val clock: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : PcmSource {
    override val channels = if (spec.stereo) 2 else 1
    override val routedId: Int? = -1
    override val routedName = "Fake input"

    private val t0 = clock()
    private val amp = 10.0.pow(spec.levelDb / 20.0) * 32767.0
    private var delivered = 0L          // frames delivered since construction
    private var markedAt = -1L          // delivered-frames value at the last markTake(); -1 = never
    @Volatile private var deliveredPub = 0L
    @Volatile private var markedPub = -1L

    /** Seconds in the spec are Floats; compare in whole frames so 0.1f means frame 4800, not 4800.0001. */
    private fun frames(sec: Float): Long = Math.round(sec.toDouble() * rate)
    private val unplugFrame = spec.unplugAt?.let(::frames)
    private val deadFrame = spec.deadAt?.let(::frames)
    private val clipFrame = spec.clipAt?.let(::frames)
    private val clipEnd = clipFrame?.let { it + frames(0.2f) }
    private val silenceFrame = spec.silenceAt?.let(::frames)

    override val unplugged: Boolean
        get() {
            val at = unplugFrame ?: return false
            val m = markedPub
            return m >= 0 && deliveredPub - m >= at
        }

    override fun markTake() { markedAt = delivered; markedPub = markedAt }

    /** Frames since the last markTake(), or -1 while no take was ever marked. */
    private fun takeFrame(abs: Long): Long = if (markedAt < 0) -1 else abs - markedAt

    override fun read(dst: ShortArray, frames: Int): Int {
        // real-time pacing: this chunk is due when its last frame has elapsed
        val dueNanos = t0 + (delivered + frames) * 1_000_000_000L / rate
        val wait = dueNanos - clock()
        if (wait > 0) sleep((wait + 999_999) / 1_000_000)
        val tf = takeFrame(delivered)
        if (unplugFrame != null && tf >= unplugFrame) { sleep(frames * 1000L / rate); return 0 }   // a dead cable still blocks for one chunk
        if (deadFrame != null && tf >= deadFrame) return -6
        val w = 2.0 * PI * spec.hz / rate
        for (f in 0 until frames) {
            val abs = delivered + f
            var v: Double
            if (silenceFrame != null && abs >= silenceFrame) v = 0.0
            else {
                v = amp * sin(w * abs)
                if (clipFrame != null && clipEnd != null) {
                    val t = takeFrame(abs)
                    if (t >= clipFrame && t < clipEnd) v = if (sin(w * abs) >= 0) 32767.0 else -32767.0
                }
            }
            val s = Math.round(v).toInt().coerceIn(-32768, 32767).toShort()
            for (c in 0 until channels) dst[f * channels + c] = if (spec.deadRight && c == 1) 0 else s
        }
        delivered += frames
        deliveredPub = delivered
        return frames
    }

    override fun stopReading() {}
    override fun close() {}
}

/**
 * One capture thread's worth of logic. step() reads a chunk and routes it; beginTake()/endTake() may be called
 * from another thread (they and the post-read part of step() share one lock).
 */
class CaptureLoop(
    private val src: PcmSource,
    private val preRoll: PreRoll,
    private val meter: LevelMeter,
    private val overview: Overview,
    private val drop: DropoutCheck,
    private val sink: (ShortArray, Int) -> Boolean /* false = queue full */,
) {
    val chunk: Int = FormatLadder.chunkFrames(src.rate)
    private val ch = src.channels
    private val buf = ShortArray(chunk * ch)
    private val lock = Any()
    private var maxFrames = 0L

    @Volatile var taking: Boolean = false; private set
    @Volatile var takeFrames: Long = 0; private set
    @Volatile var preRollFrames: Int = 0; private set
    @Volatile var queueDrops: Int = 0; private set
    @Volatile var zeroReads: Int = 0; private set
    val reachedMax: Boolean get() = taking && takeFrames >= maxFrames

    /** Reads one chunk. Returns frames read, or the negative error code. */
    fun step(nowNanos: Long): Int {
        val n = src.read(buf, chunk)
        if (n < 0) return n
        if (n == 0) { zeroReads++; return 0 }
        zeroReads = 0
        synchronized(lock) {
            meter.feed(buf, n)
            overview.feed(buf, n, ch)
            drop.onFrames(n, nowNanos)
            if (taking) {
                val offer = min(n.toLong(), maxFrames - takeFrames).toInt()
                if (offer > 0) {
                    val copy = buf.copyOf(offer * ch)
                    if (!sink(copy, offer)) queueDrops++
                    takeFrames += offer
                }
            } else preRoll.write(buf, n)
        }
        return n
    }

    fun beginTake(maxFrames: Long) {
        synchronized(lock) {
            src.markTake()
            this.maxFrames = maxFrames
            takeFrames = 0; queueDrops = 0
            preRollFrames = 0
            taking = true
            preRoll.drain { data, frames ->
                val keep = min(frames.toLong(), maxFrames).toInt()
                if (keep > 0) {
                    val out = if (keep == frames) data else data.copyOfRange((frames - keep) * ch, frames * ch)
                    if (!sink(out, keep)) queueDrops++
                    takeFrames += keep
                    preRollFrames = keep
                }
            }
        }
    }

    fun endTake() { synchronized(lock) { taking = false } }
}

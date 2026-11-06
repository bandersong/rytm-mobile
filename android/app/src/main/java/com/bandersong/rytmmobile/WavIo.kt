package com.bandersong.rytmmobile

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Canonical 44-byte-header, 16-bit PCM WAV reading and writing (java.io only; no android.* imports). */
object WavIo {
    const val HEADER_BYTES = 44

    data class Info(val rate: Int, val channels: Int, val dataBytes: Long) {
        val frames: Long get() = dataBytes / (2L * channels)
    }

    data class Scan(val peaks: FloatArray, val peakDb: List<Float>)

    fun header(rate: Int, channels: Int, dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            .putShort(1)
            .putShort(channels.toShort())
            .putInt(rate)
            .putInt(rate * channels * 2)
            .putShort((channels * 2).toShort())
            .putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes.toInt())
        return b.array()
    }

    /** Only our canonical header (RIFF/WAVE, 16-byte fmt, PCM, 16-bit, data chunk at 36); anything else is null. */
    fun parse(head: ByteArray): Info? {
        if (head.size < HEADER_BYTES) return null
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(head, at, 4, Charsets.US_ASCII)
        if (tag(0) != "RIFF" || tag(8) != "WAVE" || tag(12) != "fmt " || tag(36) != "data") return null
        if (b.getInt(16) != 16 || b.getShort(20).toInt() != 1 || b.getShort(34).toInt() != 16) return null
        val ch = b.getShort(22).toInt()
        val rate = b.getInt(24)
        if (ch < 1 || ch > 8 || rate <= 0) return null
        if (b.getInt(28) != rate * ch * 2 || b.getShort(32).toInt() != ch * 2) return null
        return Info(rate, ch, b.getInt(40).toLong() and 0xFFFFFFFFL)
    }

    private fun readHead(file: File): ByteArray {
        val head = ByteArray(HEADER_BYTES)
        FileInputStream(file).use { ins ->
            var got = 0
            while (got < HEADER_BYTES) { val n = ins.read(head, got, HEADER_BYTES - got); if (n < 0) break; got += n }
            if (got < HEADER_BYTES) throw IOException("${file.name} is shorter than a WAV header")
        }
        return head
    }

    /**
     * Makes the sizes in the header true again after a crash: data = (length - 44) rounded down to whole frames,
     * the file truncated to that, RIFF and data sizes patched. Throws IOException if the header isn't ours.
     */
    fun repair(file: File): Info {
        val head = readHead(file)
        val base = parse(head) ?: throw IOException("${file.name} is not a WAV this app wrote")
        val align = 2L * base.channels
        val data = max(0L, file.length() - HEADER_BYTES) / align * align
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() != HEADER_BYTES + data) raf.setLength(HEADER_BYTES + data)
            patchSizes(raf, data)
        }
        return Info(base.rate, base.channels, data)
    }

    internal fun patchSizes(raf: RandomAccessFile, dataBytes: Long) {
        val sz = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        raf.seek(4); raf.write(sz.putInt(0, (36 + dataBytes).toInt()).array())
        raf.seek(40); raf.write(sz.putInt(0, dataBytes.toInt()).array())
    }

    /** The whole file as mono floats in one exactly-sized array (no growth). */
    fun readMono(file: File, pick: ChannelPick): Clip {
        ChunkReader(file).use { r ->
            val frames = r.info.frames
            if (frames > Int.MAX_VALUE) throw IOException("${file.name} is too long to load")
            val out = FloatArray(frames.toInt())
            val ch = r.info.channels
            val buf = ShortArray(CHUNK_FRAMES * ch)
            var at = 0
            while (at < out.size) {
                val n = r.read(buf, min(CHUNK_FRAMES, out.size - at))
                if (n <= 0) break
                Pcm.mono(buf, n, ch, pick, out, at)
                at += n
            }
            return Clip(out, r.info.rate)
        }
    }

    private const val CHUNK_FRAMES = 8192

    /** 120 (by default) column peaks, each the max |s|/32768 over all channels, plus every channel's peak in dB. */
    fun scan(file: File, columns: Int = 120): Scan {
        ChunkReader(file).use { r ->
            val ch = r.info.channels
            val total = r.info.frames
            val peaks = FloatArray(columns)
            val chPeak = IntArray(ch)
            val buf = ShortArray(CHUNK_FRAMES * ch)
            var done = 0L
            while (done < total) {
                val n = r.read(buf, CHUNK_FRAMES)
                if (n <= 0) break
                for (f in 0 until n) {
                    val col = ((done + f) * columns / total).toInt().coerceAtMost(columns - 1)
                    var colPk = 0
                    for (c in 0 until ch) {
                        val a = abs(buf[f * ch + c].toInt())
                        if (a > chPeak[c]) chPeak[c] = a
                        if (a > colPk) colPk = a
                    }
                    val v = colPk / 32768f
                    if (v > peaks[col]) peaks[col] = v
                }
                done += n
            }
            return Scan(peaks, chPeak.map { Db.of(it / 32768f) })
        }
    }

    /** Sequential reader over the sample data. info.dataBytes is clamped to what the file really holds. */
    class ChunkReader(file: File) : Closeable {
        val info: Info
        private val ins = FileInputStream(file)
        private var remaining: Long
        private var bytes = ByteArray(0)

        init {
            try {
                val head = ByteArray(HEADER_BYTES)
                var got = 0
                while (got < HEADER_BYTES) { val n = ins.read(head, got, HEADER_BYTES - got); if (n < 0) break; got += n }
                val p = (if (got == HEADER_BYTES) parse(head) else null) ?: throw IOException("${file.name} is not a WAV this app wrote")
                val avail = max(0L, file.length() - HEADER_BYTES) / (2L * p.channels) * (2L * p.channels)
                info = Info(p.rate, p.channels, min(p.dataBytes, avail))
                remaining = info.frames
            } catch (e: Throwable) { ins.close(); throw e }
        }

        /** Reads up to [frames] frames, interleaved, into dst; returns frames read, 0 at the end. */
        fun read(dst: ShortArray, frames: Int): Int {
            val want = min(frames.toLong(), remaining).toInt()
            if (want <= 0) return 0
            val ch = info.channels
            val need = want * ch * 2
            if (bytes.size < need) bytes = ByteArray(need)
            var got = 0
            while (got < need) { val n = ins.read(bytes, got, need - got); if (n < 0) break; got += n }
            val n = got / (ch * 2)
            ByteBuffer.wrap(bytes, 0, n * ch * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(dst, 0, n * ch)
            remaining -= n
            if (n < want) remaining = 0
            return n
        }

        override fun close() { ins.close() }
    }
}

/** Streams 16-bit frames to a WAV file: header first (sizes 0), patched on close. */
class WavWriter(private val file: File, val rate: Int, val channels: Int) : Closeable {
    private val fos = FileOutputStream(file)
    private val out = BufferedOutputStream(fos, 256 * 1024)
    private var scratch = ByteArray(0)
    private var closed = false
    var frames: Long = 0; private set

    init {
        try { out.write(WavIo.header(rate, channels, 0)) } catch (e: Throwable) { runCatching { fos.close() }; throw e }
    }

    fun write(src: ShortArray, frames: Int) {
        val n = frames * channels
        if (scratch.size < n * 2) scratch = ByteArray(n * 2)
        Pcm.toLe(src, n, scratch)
        out.write(scratch, 0, n * 2)
        this.frames += frames
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            out.flush()
            fos.fd.sync()
        } finally {
            fos.close()
        }
        RandomAccessFile(file, "rw").use { WavIo.patchSizes(it, frames * channels * 2L) }
    }
}

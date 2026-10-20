package com.bandersong.rytmmobile

import android.content.Context
import android.media.*
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.*

/** Mono float audio at a given rate. */
class Clip(val data: FloatArray, val rate: Int) {
    val seconds get() = data.size / rate.toDouble()
}

object Audio {
    /** Decode any Android-supported file (wav, mp3, m4a, flac, ogg...) and downmix to mono. */
    fun decode(ctx: Context, uri: Uri): Clip {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        return decodeFrom(ex)
    }

    fun decode(file: File): Clip {
        val ex = MediaExtractor(); ex.setDataSource(file.absolutePath); return decodeFrom(ex)
    }

    private fun decodeFrom(ex: MediaExtractor): Clip {
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("No sound in this file. For screen recordings, turn on media sound in the screen recorder settings.")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        // audio/raw (WAV) is passed through untouched, so its own encoding is what arrives.
        val rawEnc = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        if (mime != MediaFormat.MIMETYPE_AUDIO_RAW) fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var enc = AudioFormat.ENCODING_PCM_16BIT
        val out = FloatList()
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        while (!outDone) {
            if (!inDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = codec.outputFormat
                rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                enc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            } else if (oi >= 0) {
                // Trust the buffer's own format: some decoders (raw WAV) ignore the float request.
                val bf = codec.getOutputFormat(oi)
                ch = bf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                rate = bf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                enc = if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) rawEnc
                    else if (bf.containsKey(MediaFormat.KEY_PCM_ENCODING)) bf.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                val b = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                b.position(info.offset); b.limit(info.offset + info.size)
                when (enc) {
                    AudioFormat.ENCODING_PCM_FLOAT -> { val fb = b.asFloatBuffer()
                        while (fb.remaining() >= ch) { var s = 0f; repeat(ch) { s += fb.get() }; out.add(s / ch) } }
                    AudioFormat.ENCODING_PCM_32BIT -> { val ib = b.asIntBuffer()
                        while (ib.remaining() >= ch) { var s = 0f; repeat(ch) { s += ib.get() / 2147483648f }; out.add(s / ch) } }
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> while (b.remaining() >= 3 * ch) {
                        var s = 0f
                        repeat(ch) { val v = (b.get().toInt() and 0xFF) or ((b.get().toInt() and 0xFF) shl 8) or (b.get().toInt() shl 16); s += v / 8388608f }
                        out.add(s / ch) }
                    AudioFormat.ENCODING_PCM_8BIT -> while (b.remaining() >= ch) {
                        var s = 0f; repeat(ch) { s += ((b.get().toInt() and 0xFF) - 128) / 128f }; out.add(s / ch) }
                    else -> { val sb = b.asShortBuffer()
                        while (sb.remaining() >= ch) { var s = 0f; repeat(ch) { s += sb.get() / 32768f }; out.add(s / ch) } }
                }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        android.util.Log.d("RytmAudio", "decoded ${out.size} frames rate=$rate ch=$ch enc=$enc fmt=$fmt")
        codec.stop(); codec.release(); ex.release()
        return Clip(out.toArray(), rate)
    }

    /**
     * Band-limited resampling: Kaiser-windowed sinc, 64 zero crossings each side, beta 9
     * (stopband well below the 16-bit floor). Cutoff tracks the lower Nyquist, so downsampling
     * anti-aliases.
     */
    fun resample(c: Clip, target: Int = Elektron.SAMPLE_RATE): Clip {
        if (c.rate == target) return c
        val ratio = target.toDouble() / c.rate
        val cutoff = min(1.0, ratio) * 0.97
        val zc = 64
        val half = ceil(zc / cutoff).toInt()
        val n = floor(c.data.size * ratio).toInt()
        val out = FloatArray(n)
        val beta = 9.0
        val i0b = besselI0(beta)
        // Precomputed kernel table, 512 phases per input sample.
        val res = 512
        val tbl = FloatArray(half * res + 1)
        for (k in tbl.indices) {
            val x = k.toDouble() / res
            val s = if (x == 0.0) 1.0 else sin(PI * x * cutoff) / (PI * x * cutoff)
            val w = x / half
            val win = if (w >= 1) 0.0 else besselI0(beta * sqrt(1 - w * w)) / i0b
            tbl[k] = (cutoff * s * win).toFloat()
        }
        for (i in 0 until n) {
            val t = i / ratio
            val c0 = floor(t).toInt()
            val frac = t - c0
            var acc = 0.0
            for (j in -half + 1..half) {
                val idx = c0 + j
                if (idx < 0 || idx >= c.data.size) continue
                val d = abs(j - frac) * res
                val di = d.toInt()
                if (di >= tbl.size - 1) continue
                val f = d - di
                val k = tbl[di] + (tbl[di + 1] - tbl[di]) * f
                acc += c.data[idx] * k
            }
            out[i] = acc.toFloat()
        }
        return Clip(out, target)
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0; var term = 1.0; var k = 1
        while (term > 1e-12 * sum) { term *= (x / (2 * k)) * (x / (2 * k)); sum += term; k++ }
        return sum
    }

    fun peak(d: FloatArray) = d.maxOfOrNull { abs(it) } ?: 0f

    /** Trim [startS, endS), optional normalize to -0.3 dBFS, 2 ms fades to avoid clicks at cut points. */
    fun prepare(c: Clip, startS: Double, endS: Double, normalize: Boolean, fades: Boolean): Clip {
        val a = (startS * c.rate).toInt().coerceIn(0, c.data.size)
        val b = (endS * c.rate).toInt().coerceIn(a, c.data.size)
        val d = c.data.copyOfRange(a, b)
        if (normalize) {
            val p = peak(d)
            if (p > 0) { val g = 10f.pow(-0.3f / 20) / p; for (i in d.indices) d[i] *= g }
        }
        if (fades) {
            val f = min(d.size / 2, c.rate / 500)
            for (i in 0 until f) { val g = i.toFloat() / f; d[i] *= g; d[d.size - 1 - i] *= g }
        }
        return Clip(d, c.rate)
    }

    /** Float to 16-bit with TPDF dither (what a careful export does). */
    fun toPcm16(c: Clip, seed: Long = 1): ShortArray {
        val r = java.util.Random(seed)
        return ShortArray(c.data.size) {
            val dither = (r.nextFloat() - r.nextFloat())
            val v = c.data[it] * 32767f + dither
            v.roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private var track: AudioTrack? = null
    fun play(c: Clip) {
        stop()
        val t = AudioTrack.Builder()
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(c.rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(maxOf(4, c.data.size * 4)).build()
        t.write(c.data, 0, c.data.size, AudioTrack.WRITE_BLOCKING)
        t.play(); track = t
    }
    fun stop() { track?.runCatching { stop(); release() }; track = null }
}

class FloatList {
    private var a = FloatArray(1 shl 16); var size = 0; private set
    fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun toArray() = a.copyOf(size)
}

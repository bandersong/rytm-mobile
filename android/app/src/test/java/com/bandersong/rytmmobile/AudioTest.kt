package com.bandersong.rytmmobile

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.*

class AudioTest {
    private fun sine(f: Double, rate: Int, secs: Double) =
        Clip(FloatArray((rate * secs).toInt()) { (0.5 * sin(2 * PI * f * it / rate)).toFloat() }, rate)

    private fun db(x: Double) = 20 * log10(x)

    /** Residual after least-squares fitting a sine at f, over the middle of the clip. */
    private fun residualDb(c: Clip, f: Double): Double {
        val a = c.data.size / 4; val b = c.data.size * 3 / 4
        var ss = 0.0; var sc = 0.0; var cc = 0.0; var ys = 0.0; var yc = 0.0
        for (i in a until b) { val s = sin(2 * PI * f * i / c.rate); val k = cos(2 * PI * f * i / c.rate)
            ss += s * s; cc += k * k; sc += s * k; ys += c.data[i] * s; yc += c.data[i] * k }
        val det = ss * cc - sc * sc; val A = (ys * cc - yc * sc) / det; val B = (yc * ss - ys * sc) / det
        var e = 0.0; var p = 0.0
        for (i in a until b) { val fit = A * sin(2 * PI * f * i / c.rate) + B * cos(2 * PI * f * i / c.rate); e += (c.data[i] - fit).pow(2); p += fit * fit }
        return 10 * log10(e / p)
    }

    @Test fun upsample441To48IsClean() {
        val r = Audio.resample(sine(1000.0, 44100, 1.0))
        assertTrue(r.rate == 48000)
        val res = residualDb(r, 1000.0); println("44.1k->48k 1 kHz residual $res dB")
        assertTrue(res < -100)
    }

    @Test fun downsampleRejectsAliases() {
        val r = Audio.resample(sine(30000.0, 96000, 1.0)) // above the new Nyquist: must vanish
        val rms = sqrt(r.data.slice(r.data.size / 4 until r.data.size * 3 / 4).map { it.toDouble().pow(2) }.average())
        val lvl = db(rms / (0.5 / sqrt(2.0))); println("30 kHz alias level $lvl dB")
        assertTrue(lvl < -90)
    }

    @Test fun ditheredPcmIsUnbiasedAndInRange() {
        val pcm = Audio.toPcm16(Audio.prepare(sine(440.0, 48000, 0.5), 0.0, 1.0, true, true))
        assertTrue(pcm.maxOrNull()!! <= 32767 && pcm.minOrNull()!! >= -32768)
        assertTrue(abs(pcm.maxOrNull()!! - 31685) < 40) // -0.3 dBFS peak
    }
}

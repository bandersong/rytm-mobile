package com.bandersong.rytmmobile

import android.content.Context
import android.media.midi.*
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** USB-MIDI link to an Elektron box. All transfer calls block; run them off the main thread. */
class RytmLink private constructor(
    private val device: MidiDevice,
    /** The USB device this link is on; compared with MidiManager's removed/added callbacks. */
    val info: MidiDeviceInfo,
    private val toDevice: MidiInputPort,
    internal val fromDevice: MidiOutputPort,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val rx = LinkedBlockingQueue<ByteArray>()
    /** Raw SysEx dump in the log; on while connecting, off for transfers (one line per call instead). */
    @Volatile var verbose = true
    private var seq = 0
    var name = ""; private set
    var version = ""; private set

    internal val receiver = object : MidiReceiver() {
        private val buf = ByteArrayOutputStream()
        private var inSysex = false
        override fun onSend(msg: ByteArray, off: Int, count: Int, ts: Long) {
            for (i in off until off + count) {
                val b = msg[i].toInt() and 0xFF
                if (b == 0xF0) { buf.reset(); inSysex = true }
                if (!inSysex) continue
                if (b >= 0xF8) continue // realtime bytes may interleave
                buf.write(b)
                if (b == 0xF7) {
                    val m = buf.toByteArray(); rx.put(m); inSysex = false
                    if (verbose) log("  ← SysEx ${m.size} B: " + m.take(24).joinToString(" ") { "%02X".format(it) } + if (m.size > 24) " …" else "")
                }
            }
        }
    }

    init { fromDevice.connect(receiver) }

    private fun send(raw: ByteArray) {
        var o = 0
        while (o < raw.size) {
            val n = minOf(512, raw.size - o)
            toDevice.send(raw, o, n); o += n
        }
    }

    @Synchronized
    fun call(msg: ByteArray, timeoutMs: Long = 5000): ByteArray {
        val mySeq = seq and 0xFFFF
        seq++
        val type = (msg[4].toInt() and 0xFF) or 0x80
        rx.clear()
        val t0 = System.currentTimeMillis()
        log("→ 0x%02x seq %d (%d B)".format(msg[4], mySeq, msg.size))
        send(Elektron.toSysex(msg, mySeq))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val raw = rx.poll(maxOf(1, left), TimeUnit.MILLISECONDS)
                ?: run {
                    log("  ✗ no answer to 0x%02x after %d ms".format(msg[4], timeoutMs))
                    throw IOException("Rytm did not answer (type 0x%02x)".format(msg[4]))
                }
            val m = Elektron.fromSysex(raw) ?: continue
            if (m.size < 5) continue
            // Replies echo the request's seq in bytes 2-3 (Elektroid: exp_seq = data[2]).
            val s = ((m[2].toInt() and 0xFF) shl 8) or (m[3].toInt() and 0xFF)
            if (s != mySeq) continue
            if ((m[4].toInt() and 0xFF) != type) throw IOException("unexpected reply type")
            log("  ← 0x%02x status %d in %d ms".format(m[4], if (m.size > 5) m[5].toInt() else -1, System.currentTimeMillis() - t0))
            return m
        }
    }

    /** MIDI Universal Identity Request; logs whatever comes back. Purely diagnostic. */
    fun identity() {
        rx.clear()
        send(byteArrayOf(0xF0.toByte(), 0x7E, 0x7F, 0x06, 0x01, 0xF7.toByte()))
        rx.poll(1000, TimeUnit.MILLISECONDS) ?: log("  (no reply to MIDI identity request)")
    }

    private fun handshake() {
        val p = call(Elektron.ping(), 3000)
        // ping reply: [5]=device id, [6]=len, then overbridge name at 7+len
        val start = 7 + (p[6].toInt() and 0xFF)
        var e = start; while (e < p.size && p[e] != 0.toByte()) e++
        name = if (start < p.size) String(p, start, e - start) else "Elektron"
        Thread.sleep(50)
        val v = call(Elektron.swVersion())
        var ve = 10; while (ve < v.size && v[ve] != 0.toByte()) ve++
        version = if (v.size > 10) String(v, 10, ve - 10) else ""
    }

    fun list(dir: String): List<Elektron.Entry> {
        val r = call(Elektron.readDir(dir))
        return Elektron.parseDir(r).sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    fun mkdir(path: String) {
        val r = call(Elektron.createDir(path))
        if (r[5].toInt() == 0) throw IOException("create dir failed")
    }

    fun delete(path: String) {
        val r = call(Elektron.deleteFile(path))
        if (r[5].toInt() == 0) throw IOException("delete failed")
    }

    /** True if the Rytm still answers a ping. */
    fun alive(): Boolean = runCatching { call(Elektron.ping(), 2000); true }.getOrDefault(false)

    /** Upload 48 kHz 16-bit mono PCM to +Drive at [path] (e.g. "/stems/kick"). */
    fun upload(path: String, pcm: ShortArray, progress: (Float) -> Unit = {}) {
        val bytes = pcm.size * 2
        // Opening a file makes the Rytm allocate it on +Drive; give a big file more time than a block.
        val open = call(Elektron.openWriter(path, bytes), 20_000)
        if (open[5].toInt() != 1) throw IOException("Rytm refused the file (status ${open[5]})")
        val id = Elektron.rd32(open, 6)
        var at = 0; var blk = 0
        while (at < pcm.size || blk == 0) {
            // Same header Elektroid writes for a WAV without a loop: start = end = last frame, type fwd.
            val last = maxOf(0, pcm.size - 1)
            val (m, n) = Elektron.writeBlock(id, blk, pcm, at, 1, last, last, true)
            val r = call(m)
            if (r[5].toInt() == 0) throw IOException("write block $blk failed")
            at += n; blk++
            progress(at.toFloat() / pcm.size)
            Thread.sleep(REST_MS)
        }
        val c = call(Elektron.closeWriter(id, bytes))
        if (c[5].toInt() == 0) throw IOException("close failed")
    }

    override fun close() {
        runCatching { fromDevice.disconnect(receiver) }
        runCatching { toDevice.close() }; runCatching { fromDevice.close() }; runCatching { device.close() }
    }

    companion object {
        const val REST_MS = 50L // Elektroid's BE_REST_TIME_US between blocks

        fun elektronDevices(ctx: Context): List<MidiDeviceInfo> {
            val mm = ctx.getSystemService(Context.MIDI_SERVICE) as MidiManager
            @Suppress("DEPRECATION")
            return mm.devices.filter {
                val p = it.properties
                val txt = "${p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)} ${p.getString(MidiDeviceInfo.PROPERTY_NAME)} ${p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)}"
                txt.contains("Elektron", true) || txt.contains("Rytm", true)
            }.filter { it.inputPortCount > 0 && it.outputPortCount > 0 }
        }

        fun describe(info: MidiDeviceInfo): String {
            val p = info.properties
            return "${p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)} / ${p.getString(MidiDeviceInfo.PROPERTY_NAME)} / " +
                "${p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)} type=${info.type} in=${info.inputPortCount} out=${info.outputPortCount}"
        }

        /**
         * Tries every Elektron device and every input/output port pair (the Rytm can expose
         * more than one), logging all traffic, and returns the first pair that answers the ping.
         */
        suspend fun scan(ctx: Context, log: (String) -> Unit): RytmLink {
            val mm = ctx.getSystemService(Context.MIDI_SERVICE) as MidiManager
            @Suppress("DEPRECATION")
            val all = mm.devices.toList()
            log("MIDI devices seen by Android: ${all.size}")
            all.forEach { log("• " + describe(it)) }
            var cands = elektronDevices(ctx)
            if (cands.isEmpty()) cands = all.filter { it.type == MidiDeviceInfo.TYPE_USB && it.inputPortCount > 0 && it.outputPortCount > 0 }
            if (cands.isEmpty()) throw IOException("No USB-MIDI device. Is the Rytm's USB mode set to USB MIDI, and the OTG cable in?")
            for (info in cands) {
                val dev = suspendCancellableCoroutine<MidiDevice?> { c ->
                    mm.openDevice(info, { c.resume(it) }, Handler(Looper.getMainLooper()))
                } ?: run { log("could not open ${describe(info)}"); null } ?: continue
                for (i in 0 until info.inputPortCount) for (o in 0 until info.outputPortCount) {
                    log("Trying send port $i / receive port $o")
                    val inP = dev.openInputPort(i) ?: run { log("  send port $i busy"); null } ?: continue
                    val outP = dev.openOutputPort(o) ?: run { log("  receive port $o busy"); inP.close(); null } ?: continue
                    val link = RytmLink(dev, info, inP, outP, log)
                    try {
                        link.identity()
                        link.handshake()
                        log("  ✓ ${link.name} ${link.version}")
                        link.verbose = false
                        return link
                    } catch (e: Exception) {
                        log("  ✗ ${e.message}")
                        runCatching { link.fromDevice.disconnect(link.receiver) }
                        runCatching { inP.close() }; runCatching { outP.close() }
                    }
                }
                runCatching { dev.close() }
            }
            throw IOException("The Rytm didn't answer on any port. See the log below.")
        }
    }
}

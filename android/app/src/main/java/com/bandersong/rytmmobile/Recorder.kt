package com.bandersong.rytmmobile

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "RytmRec"

/** Maps AudioManager devices to the pure InputInfo model. The only place that knows AudioDeviceInfo types. */
object AudioInputs {
    fun kind(type: Int): InputKind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputKind.BUILTIN_MIC
        AudioDeviceInfo.TYPE_USB_DEVICE -> InputKind.USB_DEVICE
        AudioDeviceInfo.TYPE_USB_HEADSET -> InputKind.USB_HEADSET
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> InputKind.LINE_IN
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> InputKind.WIRED_HEADSET
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> InputKind.BLUETOOTH
        else -> InputKind.HIDDEN
    }

    fun info(d: AudioDeviceInfo): InputInfo = InputInfo(
        d.id, kind(d.type), d.productName?.toString() ?: "", d.sampleRates.toList(), d.channelCounts.toList(),
    )

    fun list(am: AudioManager): List<InputInfo> =
        InputPicker.visible(am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }.map(::info))
}

/** A real microphone: AudioRecord 16-bit PCM, opened through the format ladder. */
class AudioRecordSource private constructor(
    private val record: AudioRecord,
    override val rate: Int,
    override val channels: Int,
    override val routedId: Int?,
    override val routedName: String,
    private val detach: () -> Unit,
) : PcmSource {

    sealed interface Attempt {
        class Ok(val src: AudioRecordSource, val source: Source, val rung: Rung, val bufferFrames: Int) : Attempt
        data object DeviceInvalid : Attempt
        class AllFailed(val tried: Int, val lastError: String) : Attempt
    }

    override fun read(dst: ShortArray, frames: Int): Int {
        val n = record.read(dst, 0, frames * channels, AudioRecord.READ_BLOCKING)
        return if (n > 0) n / channels else n
    }

    override fun stopReading() { record.stop() }

    override fun close() {
        runCatching { detach() }
        runCatching { record.stop() }
        runCatching { record.release() }
    }

    companion object {
        private fun androidSource(s: Source) = when (s) {
            Source.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
            Source.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            Source.MIC -> MediaRecorder.AudioSource.MIC
        }

        /**
         * Walks [plan] until one source/rate/channel combination delivers real frames. `cancelled` lets the engine
         * abandon a ladder whose input was closed or replaced meanwhile.
         */
        fun open(
            ctx: Context, device: AudioDeviceInfo?, plan: List<Pair<Source, Rung>>, callbackExecutor: Executor,
            onSilenced: (Boolean) -> Unit, onRoute: (Int?, String) -> Unit, log: (String) -> Unit,
            cancelled: () -> Boolean = { false },
        ): Attempt {
            var tried = 0
            var lastError = "no attempt"
            for ((source, rung) in plan) {
                if (cancelled()) return Attempt.AllFailed(tried, "cancelled")
                val tag = "try $source ${rung.rate} ${rung.channels}"
                val mask = if (rung.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                var min = 0
                if (rung.rate > 0) {
                    min = AudioRecord.getMinBufferSize(rung.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                    if (min <= 0) { log("$tag: skip min=$min"); lastError = "not supported (min=$min)"; continue }
                }
                tried++
                val buf = FormatLadder.bufferBytes(maxOf(min, 0), rung.rate, rung.channels)
                val rec = try {
                    AudioRecord.Builder()
                        .setAudioSource(androidSource(source))
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(mask)
                            .apply { if (rung.rate > 0) setSampleRate(rung.rate) }
                            .build())
                        .setBufferSizeInBytes(buf)
                        .build()
                } catch (e: Exception) {   // UnsupportedOperation / IllegalArgument / Security, and anything else a driver throws
                    log("$tag: build failed: ${e.javaClass.simpleName} ${e.message}"); lastError = "build failed: ${e.message}"; continue
                }
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    log("$tag: not initialized"); lastError = "not initialized"; rec.release(); continue
                }
                if (device != null && !rec.setPreferredDevice(device)) {
                    rec.release(); log("$tag: preferred device rejected"); return Attempt.DeviceInvalid
                }
                val handler = Handler(Looper.getMainLooper())
                val recCb = object : android.media.AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) {
                        val mine = configs.firstOrNull { it.clientAudioSessionId == rec.audioSessionId } ?: configs.firstOrNull() ?: return
                        onSilenced(mine.isClientSilenced)
                    }
                }
                val routeCb = AudioRouting.OnRoutingChangedListener { r ->
                    val d = r.routedDevice
                    onRoute(d?.id, d?.let { InputPicker.label(AudioInputs.info(it)) } ?: "")
                }
                rec.registerAudioRecordingCallback(callbackExecutor, recCb)
                rec.addOnRoutingChangedListener(routeCb, handler)
                val detach = {
                    runCatching { rec.unregisterAudioRecordingCallback(recCb) }
                    runCatching { rec.removeOnRoutingChangedListener(routeCb) }
                    Unit
                }
                val started = try { rec.startRecording(); rec.recordingState == AudioRecord.RECORDSTATE_RECORDING }
                catch (e: Exception) { false }
                if (!started) {
                    log("$tag: start failed"); lastError = "start failed"; detach(); rec.release(); continue
                }
                // Probe: a source can "start" and still never deliver. Wait for real frames.
                val probe = ShortArray(256 * rung.channels.coerceAtLeast(1) * 2)
                var gotFrames = false
                val t0 = System.nanoTime()
                while (!gotFrames && (System.nanoTime() - t0) / 1_000_000 < FormatLadder.PROBE_MS && !cancelled()) {
                    val n = rec.read(probe, 0, probe.size, AudioRecord.READ_NON_BLOCKING)
                    if (n > 0) gotFrames = true else if (n < 0) break else Thread.sleep(10)
                }
                if (!gotFrames) {
                    log("$tag: no frames"); lastError = "no frames"; detach(); runCatching { rec.stop() }; rec.release(); continue
                }
                log("$tag: ok")
                val routed = rec.routedDevice
                val routedLabel = routed?.let { InputPicker.label(AudioInputs.info(it)) } ?: ""
                val src = AudioRecordSource(rec, rec.sampleRate, rec.channelCount, routed?.id, routedLabel, detach)
                return Attempt.Ok(src, source, rung, buf / (2 * rec.channelCount))
            }
            return Attempt.AllFailed(tried, lastError)
        }
    }
}

data class Notice(val seq: Int, val text: String)

/**
 * Owns the microphone, its threads and the take files. Compose reads the StateFlows; every method just
 * enqueues onto the control thread, where RecMachine.reduce runs and its effects are executed in order.
 *
 * Threads: main (device callbacks), control `rytm-rec-ctl`, one capture thread `rytm-rec-in` per open input,
 * one writer thread `rytm-rec-wr` per take, and `rytm-rec-open` for the (slow) format ladder.
 */
class RecorderEngine(private val ctx: Context, private val prefs: SharedPreferences) {
    private val _model = MutableStateFlow(RecModel())
    val model: StateFlow<RecModel> = _model
    private val _meter = MutableStateFlow(MeterReading.EMPTY)
    val meter: StateFlow<MeterReading> = _meter
    private val _live = MutableStateFlow(FloatArray(0))
    val live: StateFlow<FloatArray> = _live
    private val _takes = MutableStateFlow<List<Take>>(emptyList())
    val takes: StateFlow<List<Take>> = _takes
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice

    val takeDir: File = File(ctx.filesDir, "takes")
    private val store = TakeStore(takeDir)
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    private val ctl = Executors.newSingleThreadScheduledExecutor { Thread(it, "rytm-rec-ctl") }
    private val opener = Executors.newSingleThreadExecutor { Thread(it, "rytm-rec-open") }
    private val openGen = AtomicInteger()

    @Volatile private var fake: FakeSpec? = null
    @Volatile private var fakeUnplugged = false
    @Volatile private var active: Active? = null
    @Volatile private var take: TakeCtx? = null
    private var watchdog: ScheduledFuture<*>? = null
    private var noticeSeq = 0

    private val deviceCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { post(RecEvent.Devices(inputs())) }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { post(RecEvent.Devices(inputs())) }
    }
    private var cbRegistered = false

    init {
        ctl.execute { runCatching { _takes.value = store.list() } }
    }

    // --- public API: all enqueue onto the control thread -------------------------------------------------------

    fun show(granted: Boolean, rationale: Boolean) = ctl.execute {
        if (_model.value.state != RecState.Idle) return@execute
        takeDir.mkdirs()
        val rec = runCatching { store.recover() }.getOrDefault(emptyList())
        if (rec.isNotEmpty()) { say(TakeText.recovered(rec.size)); reloadTakes() }
        apply(RecEvent.Show(granted, rationale, inputs(), prefs.getString("recInput", null)))
    }

    fun askAgain() = post(RecEvent.AskAgain)
    fun permissionResult(granted: Boolean, rationale: Boolean) = ctl.execute {
        apply(RecEvent.PermissionResult(granted, rationale, inputs()))
    }
    fun pick(key: String) = ctl.execute {
        val m = _model.value
        if (!m.capturing && m.inputs.any { it.key == key }) prefs.edit().putString("recInput", key).apply()
        apply(RecEvent.Pick(key))
    }
    fun retry() = post(RecEvent.Retry)
    fun rec() = ctl.execute {
        val s = _model.value.state as? RecState.Armed ?: return@execute
        val bps = s.open.rate * s.open.channels * 2
        takeDir.mkdirs()
        val budget = TakeBudget.maxSeconds(takeDir.usableSpace, bps)
        apply(RecEvent.Rec(budget, store.newId(System.currentTimeMillis())))
    }
    fun stop() = post(RecEvent.Stop)
    fun close() = post(RecEvent.Close)
    fun hostStopped() = post(RecEvent.HostStopped)
    fun hostStarted(granted: Boolean, rationale: Boolean) = ctl.execute {
        apply(RecEvent.HostStarted(granted, rationale, inputs()))
    }
    fun markSaved(t: Take, uri: String) = ctl.execute {
        val u = runCatching { store.markSaved(t, uri) }.getOrNull() ?: return@execute
        apply(RecEvent.TakeUpdated(u)); reloadTakes()
    }
    fun deleteTake(t: Take) = ctl.execute {
        runCatching { store.delete(t) }
        apply(RecEvent.TakeDeleted(t.id)); reloadTakes()
    }
    fun resetClip() { active?.meter?.resetClip() }

    /** Debug builds only (the caller checks BuildConfig.DEBUG). null = real inputs. */
    fun setFake(spec: FakeSpec?) = ctl.execute {
        fake = spec; fakeUnplugged = false
        apply(RecEvent.Devices(inputs()))
    }

    // --- control thread ----------------------------------------------------------------------------------------

    private fun post(e: RecEvent) { ctl.execute { apply(e) } }

    private fun apply(e: RecEvent) {
        val old = _model.value
        val (m, fx) = RecMachine.reduce(old, e)
        _model.value = m
        if (m.open != old.open) setDeviceCallback(m.open)
        for (f in fx) exec(f)
    }

    private fun setDeviceCallback(on: Boolean) {
        main.post {
            if (on && !cbRegistered) { am.registerAudioDeviceCallback(deviceCb, main); cbRegistered = true }
            else if (!on && cbRegistered) { am.unregisterAudioDeviceCallback(deviceCb); cbRegistered = false }
        }
    }

    private fun say(t: String) { noticeSeq += 1; _notice.value = Notice(noticeSeq, t) }
    private fun reloadTakes() { runCatching { _takes.value = store.list() } }

    private fun fakeInput(spec: FakeSpec) =
        InputInfo(-1, InputKind.USB_DEVICE, "Fake input", listOf(48000), if (spec.stereo) listOf(2) else listOf(1))

    private fun inputs(): List<InputInfo> {
        val f = fake
        if (f != null) return if (fakeUnplugged) emptyList() else listOf(fakeInput(f))
        return AudioInputs.list(am)
    }

    private fun exec(f: RecEffect) {
        when (f) {
            is RecEffect.OpenInput -> openInput(f.input)
            RecEffect.CloseInput -> closeInput()
            is RecEffect.StartTake -> startTake(f.takeId, f.maxSeconds)
            is RecEffect.EndTake -> endTake(f.reason)
            is RecEffect.Say -> say(f.text)
            RecEffect.ReloadTakes -> reloadTakes()
        }
    }

    // --- input open / close --------------------------------------------------------------------------------------

    /** One open microphone: its source, the DSP objects and the capture thread. */
    private class Active(
        val input: InputInfo, val src: PcmSource, val source: Source,
        val loop: CaptureLoop, val meter: LevelMeter, val overview: Overview, val drop: DropoutCheck,
    ) {
        @Volatile var running = true
        lateinit var thread: Thread
    }

    /** What a take in progress needs from the engine thread to the writer thread. */
    private class TakeCtx(
        val id: String, val queue: ArrayBlockingQueue<ShortArray>, val writer: WavWriter?,
    ) {
        lateinit var thread: Thread
        @Volatile var meta: TakeMeta? = null
        var clipsAtStart = 0
        var dropsAtStart = 0
        val end = ShortArray(0)       // sentinel, compared by identity
    }

    private fun closeInput() {
        openGen.incrementAndGet()
        val a = active ?: run { _meter.value = MeterReading.EMPTY; _live.value = FloatArray(0); return }
        active = null
        a.running = false
        a.thread.join(500)
        if (a.thread.isAlive) {
            runCatching { a.src.stopReading() }
            a.thread.join(1000)
            if (a.thread.isAlive) Log.i(TAG, "capture thread did not stop; dropping it")
        }
        runCatching { a.src.close() }
        _meter.value = MeterReading.EMPTY
        _live.value = FloatArray(0)
    }

    private fun openInput(i: InputInfo) {
        closeInput()
        val gen = openGen.incrementAndGet()
        val spec = fake
        if (spec != null) {
            val src = FakeSource(spec)
            install(i, src, Source.MIC)
            apply(RecEvent.Opened(i.id, OpenFormat(Source.MIC, 48000, src.channels, -1, "USB · Fake input", true)))
            return
        }
        opener.execute { openReal(i, gen) }
    }

    /** Runs on the opener thread: the ladder can take seconds, so it never blocks the control thread. */
    private fun openReal(i: InputInfo, gen: Int) {
        val label = InputPicker.label(i)
        try {
            val device = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == i.id }
            if (device == null) { if (gen == openGen.get()) post(RecEvent.Devices(inputs())); return }
            val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                ?.let { !it.equals("false", true) } ?: false
            val plan = FormatLadder.plan(i, unprocessed, prefs.getString("recRung:${i.key}", null))
            val attempt = AudioRecordSource.open(
                ctx, device, plan, ctl,
                onSilenced = { post(RecEvent.Silenced(it)) },
                onRoute = { id, name -> post(RecEvent.RouteChanged(routeId(id, i), name)) },
                log = { Log.i(TAG, it) },
                cancelled = { gen != openGen.get() },
            )
            when (attempt) {
                is AudioRecordSource.Attempt.Ok -> ctl.execute {
                    if (gen != openGen.get()) { runCatching { attempt.src.close() }; return@execute }
                    prefs.edit().putString("recRung:${i.key}", FormatLadder.encode(attempt.source, attempt.rung)).apply()
                    install(i, attempt.src, attempt.source)
                    val s = attempt.src
                    val routedKind = s.routedId?.let { rid -> am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == rid } }
                        ?.let { AudioInputs.kind(it.type) }
                    val matches = s.routedId == null || s.routedId == i.id ||
                        (routedKind == InputKind.BUILTIN_MIC && i.kind == InputKind.BUILTIN_MIC)
                    Log.i(TAG, "open ok source=${attempt.source} rate=${s.rate} ch=${s.channels} bufferFrames=${attempt.bufferFrames} routed=${s.routedName}")
                    apply(RecEvent.Opened(i.id, OpenFormat(attempt.source, s.rate, s.channels, s.routedId,
                        s.routedName.ifEmpty { label }, matches)))
                }
                AudioRecordSource.Attempt.DeviceInvalid -> if (gen == openGen.get()) {
                    post(RecEvent.Devices(inputs()))
                    post(RecEvent.OpenFailed(i.id, "$label isn't available any more."))
                }
                is AudioRecordSource.Attempt.AllFailed -> if (gen == openGen.get()) post(RecEvent.OpenFailed(i.id,
                    "Couldn't open $label. Tried ${attempt.tried} ways, at 48 and 44.1 kHz, stereo and mono. Pick another input, or unplug it and plug it back in."))
            }
        } catch (e: Throwable) {
            Log.i(TAG, "open failed: $e")
            if (gen == openGen.get()) post(RecEvent.OpenFailed(i.id, "Couldn't open $label: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    /** A built-in mic that Android routes to a different built-in entry is still "the phone mic". */
    private fun routeId(rid: Int?, input: InputInfo): Int? {
        if (rid == null || input.kind != InputKind.BUILTIN_MIC) return rid
        val kind = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == rid }?.let { AudioInputs.kind(it.type) }
        return if (kind == InputKind.BUILTIN_MIC) input.id else rid
    }

    /** Control thread: builds the DSP objects and starts the capture thread. */
    private fun install(i: InputInfo, src: PcmSource, source: Source) {
        val rate = src.rate
        val meter = LevelMeter(src.channels, rate)
        val overview = Overview(rate / 10)
        val drop = DropoutCheck(rate)
        val loop = CaptureLoop(src, PreRoll(FormatLadder.PREROLL_MS * rate / 1000, src.channels), meter, overview, drop) { buf, _ ->
            take?.queue?.offer(buf) ?: false
        }
        val a = Active(i, src, source, loop, meter, overview, drop)
        active = a
        a.thread = Thread({ captureLoop(a) }, "rytm-rec-in").also { it.start() }
    }

    private fun captureLoop(a: Active) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        var published = 0
        var maxPosted = false
        var unplugPosted = false
        while (a.running) {
            val n = a.loop.step(System.nanoTime())
            if (n < 0 || a.loop.zeroReads > 50) {
                if (a.running) post(RecEvent.Interrupt(StopReason.READ_ERROR))
                break
            }
            if (a.loop.reachedMax && !maxPosted) { maxPosted = true; post(RecEvent.Interrupt(StopReason.MAX_LENGTH)) }
            if (++published % 2 == 0) {
                _meter.value = a.meter.snapshot(a.loop.takeFrames)
                _live.value = a.overview.last()
            }
            if (a.src.unplugged && !unplugPosted) {
                unplugPosted = true
                fakeUnplugged = true
                post(RecEvent.Devices(inputs()))
            }
        }
    }

    // --- takes -------------------------------------------------------------------------------------------------

    private fun startTake(id: String, maxSeconds: Int) {
        val a = active ?: run { post(RecEvent.Interrupt(StopReason.READ_ERROR)); return }
        Audio.stop()
        val queue = ArrayBlockingQueue<ShortArray>(250)
        val writer = try { WavWriter(store.partFile(id), a.src.rate, a.src.channels) }
        catch (e: IOException) { post(RecEvent.Interrupt(StopReason.DISK_FULL)); null }
        val t = TakeCtx(id, queue, writer)
        take = t
        if (writer != null) {
            t.thread = Thread({ writeLoop(t, a) }, "rytm-rec-wr").also { it.start() }
        }
        t.clipsAtStart = a.meter.snapshot().clipCount
        t.dropsAtStart = a.drop.dropouts
        Log.i(TAG, "take $id start")
        a.loop.beginTake(maxSeconds.toLong() * a.src.rate)
        watchdog?.cancel(false)
        var silentTicks = 0
        watchdog = ctl.scheduleWithFixedDelay({
            if (_model.value.silenced) silentTicks++ else silentTicks = 0
            if (silentTicks >= 8) {               // 8 x 250 ms = 2 s silenced during this take
                silentTicks = 0
                apply(RecEvent.Interrupt(StopReason.SILENCED))
            }
        }, 250, 250, TimeUnit.MILLISECONDS)
    }

    private fun endTake(reason: StopReason) {
        watchdog?.cancel(false); watchdog = null
        val t = take ?: run { post(RecEvent.FinalizeFailed("no take was started")); return }
        val a = active
        if (a != null) {
            a.loop.endTake()
            val clips = a.meter.snapshot().clipCount - t.clipsAtStart
            val drops = a.drop.dropouts - t.dropsAtStart + a.loop.queueDrops
            t.meta = TakeMeta(a.src.rate, a.src.channels, a.loop.preRollFrames, InputPicker.label(a.input), a.source.name,
                clips.coerceAtLeast(0), drops.coerceAtLeast(0), reason)
            Log.i(TAG, "take ${t.id} end reason=$reason frames=${a.loop.takeFrames} drops=${a.loop.queueDrops} dropouts=${a.drop.dropouts - t.dropsAtStart}")
        }
        take = null
        if (t.writer == null || t.meta == null) {
            post(RecEvent.FinalizeFailed("the take file couldn't be created"))
            return
        }
        t.queue.put(t.end)                               // sentinel; may wait for the writer to drain
    }

    /** Writer thread: queue to disk; on the sentinel, close and hand the finalize to the control thread. */
    private fun writeLoop(t: TakeCtx, a: Active) {
        val w = t.writer!!
        val ch = a.src.channels
        var failed = false
        while (true) {
            val b = try { t.queue.take() } catch (e: InterruptedException) { break }
            if (b === t.end) break
            if (failed) continue
            try { w.write(b, b.size / ch) }
            catch (e: Exception) { failed = true; post(RecEvent.Interrupt(StopReason.DISK_FULL)) }   // any write failure: keep draining so put() never blocks
        }
        runCatching { w.close() }        // finalize() repairs the header anyway if this failed
        ctl.execute {
            val meta = t.meta
            if (meta == null) { apply(RecEvent.FinalizeFailed("no take details")); return@execute }
            try { apply(RecEvent.Finalized(store.finalize(t.id, meta))) }
            catch (e: Exception) { apply(RecEvent.FinalizeFailed(e.message ?: e.javaClass.simpleName)) }
        }
    }
}

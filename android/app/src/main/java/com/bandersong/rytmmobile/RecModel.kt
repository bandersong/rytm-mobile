package com.bandersong.rytmmobile

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// Recording model: the pure reducer, input choice, format ladder, budgets and text.
// No android.* imports; RecorderEngine (Recorder.kt) executes the effects.

enum class InputKind { BUILTIN_MIC, USB_DEVICE, USB_HEADSET, LINE_IN, WIRED_HEADSET, BLUETOOTH, HIDDEN }

data class InputInfo(
    val id: Int,                     // AudioDeviceInfo.getId(): valid while attached; never persisted
    val kind: InputKind,
    val name: String,                // getProductName()?.toString() ?: ""
    val rates: List<Int>,            // getSampleRates(); empty = any
    val channelCounts: List<Int>,    // getChannelCounts(); empty = any
    val isNew: Boolean = false,      // appeared after the recorder opened (UI tag NEW)
) { val key: String get() = "${kind.name}|$name" }

enum class Source { UNPROCESSED, VOICE_RECOGNITION, MIC }          // nothing else, ever
data class Rung(val rate: Int /* 0 = SAMPLE_RATE_UNSPECIFIED */, val channels: Int /* 1 or 2 */)
data class OpenFormat(
    val source: Source, val rate: Int, val channels: Int,
    val routedId: Int?, val routedName: String, val routedMatches: Boolean,
)

enum class Perm { ASKING, RATIONALE, BLOCKED }
enum class StopReason { USER, UNPLUGGED, ROUTE_CHANGED, SILENCED, READ_ERROR, BACKGROUND, MAX_LENGTH, DISK_FULL, RECOVERED }

sealed interface RecState {
    data object Idle : RecState                                              // recorder not shown, mic closed
    data class NeedsPermission(val why: Perm) : RecState
    data object NoInput : RecState
    data class Opening(val input: InputInfo) : RecState                      // ladder running
    data class Armed(val input: InputInfo, val open: OpenFormat) : RecState  // monitoring, nothing kept
    data class Recording(val input: InputInfo, val open: OpenFormat, val takeId: String, val maxSeconds: Int) : RecState
    data class Finalizing(val input: InputInfo, val open: OpenFormat, val takeId: String, val reason: StopReason) : RecState
    data class Failed(val input: InputInfo?, val message: String) : RecState
    data object Paused : RecState                                            // app left the screen; mic closed
}

data class RecModel(
    val state: RecState = RecState.Idle,
    val open: Boolean = false,                 // recorder shown inside Chop
    val inputs: List<InputInfo> = emptyList(), // already InputPicker.visible()
    val selectedKey: String? = null,
    val userPicked: Boolean = false,           // a Pick happened since the last Show
    val hostVisible: Boolean = true,
    val silenced: Boolean = false,
    val note: String? = null,                  // routing mismatch; cleared on Opening
    val lastTake: Take? = null,
) {
    val capturing: Boolean get() = state is RecState.Recording || state is RecState.Finalizing
    val current: InputInfo? get() = when (val s = state) {
        is RecState.Opening -> s.input; is RecState.Armed -> s.input; is RecState.Recording -> s.input
        is RecState.Finalizing -> s.input; is RecState.Failed -> s.input; else -> null
    }
}

sealed interface RecEvent {
    data class Show(val granted: Boolean, val rationale: Boolean, val inputs: List<InputInfo>, val savedKey: String?) : RecEvent
    data object AskAgain : RecEvent
    data class PermissionResult(val granted: Boolean, val rationale: Boolean, val inputs: List<InputInfo>) : RecEvent
    data class Devices(val inputs: List<InputInfo>) : RecEvent
    data class Pick(val key: String) : RecEvent
    data object Retry : RecEvent
    data class Opened(val inputId: Int, val open: OpenFormat) : RecEvent
    data class OpenFailed(val inputId: Int, val message: String) : RecEvent
    data class Rec(val budgetSeconds: Int, val takeId: String) : RecEvent
    data object Stop : RecEvent
    data class Interrupt(val reason: StopReason) : RecEvent
    data class RouteChanged(val routedId: Int?, val routedName: String) : RecEvent
    data class Silenced(val on: Boolean) : RecEvent
    data class Finalized(val take: Take) : RecEvent
    data class FinalizeFailed(val message: String) : RecEvent
    data class TakeUpdated(val take: Take) : RecEvent
    data class TakeDeleted(val id: String) : RecEvent
    data object Close : RecEvent
    data object HostStopped : RecEvent
    data class HostStarted(val granted: Boolean, val rationale: Boolean, val inputs: List<InputInfo>) : RecEvent
}

sealed interface RecEffect {
    data class OpenInput(val input: InputInfo) : RecEffect     // engine closes any open input first
    data object CloseInput : RecEffect
    data class StartTake(val takeId: String, val maxSeconds: Int) : RecEffect
    data class EndTake(val reason: StopReason) : RecEffect
    data class Say(val text: String) : RecEffect
    data object ReloadTakes : RecEffect
}

private typealias Step = Pair<RecModel, List<RecEffect>>

object RecMachine {
    private fun L(i: InputInfo) = InputPicker.label(i)
    private fun best(list: List<InputInfo>, key: String?) = InputPicker.choose(list, key)

    private fun noteFor(i: InputInfo, routedName: String, matches: Boolean): String? =
        if (matches) null else "Android is recording from $routedName, not ${L(i)}."

    /** open(i): Opening(i), note cleared, effects [OpenInput(i)] + extra. */
    private fun open(m: RecModel, i: InputInfo, vararg extra: RecEffect): Step =
        m.copy(state = RecState.Opening(i), note = null) to (listOf<RecEffect>(RecEffect.OpenInput(i)) + extra)

    private fun openBestOrNone(m: RecModel, list: List<InputInfo>, vararg extra: RecEffect): Step {
        val b = best(list, m.selectedKey)
        return if (b != null) open(m, b, *extra)
        else m.copy(state = RecState.NoInput, note = null) to (extra.toList() + RecEffect.CloseInput)
    }

    private fun permFor(rationale: Boolean) = RecState.NeedsPermission(if (rationale) Perm.RATIONALE else Perm.BLOCKED)

    fun reduce(m: RecModel, e: RecEvent): Pair<RecModel, List<RecEffect>> {
        val s = m.state
        return when (e) {
            is RecEvent.Show -> {
                if (m.open || s != RecState.Idle) return m to emptyList()
                val base = m.copy(open = true, userPicked = false, selectedKey = e.savedKey,
                    inputs = InputPicker.markNew(emptyList(), e.inputs))
                when {
                    e.granted -> openBestOrNone(base, base.inputs)
                    e.rationale -> base.copy(state = RecState.NeedsPermission(Perm.RATIONALE)) to emptyList()
                    else -> base.copy(state = RecState.NeedsPermission(Perm.ASKING)) to emptyList()
                }
            }

            RecEvent.AskAgain ->
                if (s is RecState.NeedsPermission) m.copy(state = RecState.NeedsPermission(Perm.ASKING)) to emptyList() else m to emptyList()

            is RecEvent.PermissionResult -> {
                if (s !is RecState.NeedsPermission) return m to emptyList()
                if (e.granted) {
                    val inputs = InputPicker.markNew(emptyList(), e.inputs)
                    openBestOrNone(m.copy(inputs = inputs), inputs)
                } else m.copy(state = permFor(e.rationale)) to emptyList()
            }

            is RecEvent.Devices -> devices(m, e.inputs)

            is RecEvent.Pick -> when (s) {
                is RecState.Opening, is RecState.Armed, is RecState.Failed, RecState.NoInput -> {
                    val target = m.inputs.firstOrNull { it.key == e.key }
                    if (target == null) m to emptyList()
                    else open(m.copy(selectedKey = e.key, userPicked = true), best(m.inputs, e.key) ?: target)
                }
                else -> m to emptyList()
            }

            RecEvent.Retry -> if (s is RecState.Failed) {
                val i = s.input
                if (i != null && m.inputs.any { it.id == i.id }) open(m, i) else openBestOrNone(m, m.inputs)
            } else m to emptyList()

            is RecEvent.Opened ->
                if (s is RecState.Opening && e.inputId == s.input.id)
                    m.copy(state = RecState.Armed(s.input, e.open), note = noteFor(s.input, e.open.routedName, e.open.routedMatches)) to emptyList()
                else m to emptyList()

            is RecEvent.OpenFailed ->
                if (s is RecState.Opening && e.inputId == s.input.id) m.copy(state = RecState.Failed(s.input, e.message)) to emptyList()
                else m to emptyList()

            is RecEvent.Rec -> if (s is RecState.Armed) {
                if (TakeBudget.allows(e.budgetSeconds)) {
                    val max = min(e.budgetSeconds, FormatLadder.MAX_TAKE_S)
                    m.copy(state = RecState.Recording(s.input, s.open, e.takeId, max)) to listOf(
                        RecEffect.StartTake(e.takeId, max), RecEffect.Say("Recording from ${s.open.routedName}."))
                } else m to listOf(RecEffect.Say("Phone storage is full. Free some space to record."))
            } else m to emptyList()

            RecEvent.Stop -> if (s is RecState.Recording)
                m.copy(state = RecState.Finalizing(s.input, s.open, s.takeId, StopReason.USER)) to listOf(RecEffect.EndTake(StopReason.USER))
            else m to emptyList()

            is RecEvent.Interrupt -> when {
                s is RecState.Recording ->
                    m.copy(state = RecState.Finalizing(s.input, s.open, s.takeId, e.reason)) to listOf(RecEffect.EndTake(e.reason))
                s is RecState.Armed && e.reason == StopReason.READ_ERROR ->
                    m.copy(state = RecState.Failed(s.input, "Lost ${L(s.input)}. Tap Try again or pick another input.")) to listOf(RecEffect.CloseInput)
                else -> m to emptyList()
            }

            is RecEvent.RouteChanged -> when (s) {
                is RecState.Recording ->
                    if (e.routedId != null && e.routedId != s.open.routedId)
                        m.copy(state = RecState.Finalizing(s.input, s.open, s.takeId, StopReason.ROUTE_CHANGED)) to listOf(RecEffect.EndTake(StopReason.ROUTE_CHANGED))
                    else m to emptyList()
                is RecState.Armed -> {
                    val matches = e.routedId == null || e.routedId == s.input.id
                    val f = s.open.copy(routedId = e.routedId, routedName = e.routedName, routedMatches = matches)
                    m.copy(state = RecState.Armed(s.input, f), note = noteFor(s.input, e.routedName, matches)) to emptyList()
                }
                else -> m to emptyList()
            }

            is RecEvent.Silenced -> m.copy(silenced = e.on) to emptyList()

            is RecEvent.Finalized -> if (s is RecState.Finalizing) {
                val withTake = m.copy(lastTake = e.take)
                next(withTake, s, listOf(RecEffect.ReloadTakes, RecEffect.Say(TakeText.stopLine(s.reason, e.take))))
            } else m to emptyList()

            is RecEvent.FinalizeFailed -> if (s is RecState.Finalizing)
                next(m, s, listOf(RecEffect.Say("Couldn't finish the take: ${e.message}. It is recovered the next time you open the recorder.")))
            else m to emptyList()

            is RecEvent.TakeUpdated -> (if (m.lastTake?.id == e.take.id) m.copy(lastTake = e.take) else m) to emptyList()
            is RecEvent.TakeDeleted -> (if (m.lastTake?.id == e.id) m.copy(lastTake = null) else m) to emptyList()

            RecEvent.Close -> if (m.capturing) m to emptyList()
            else m.copy(state = RecState.Idle, open = false, note = null, silenced = false) to listOf(RecEffect.CloseInput)

            RecEvent.HostStopped -> when (s) {
                is RecState.Recording ->
                    m.copy(hostVisible = false, state = RecState.Finalizing(s.input, s.open, s.takeId, StopReason.BACKGROUND)) to
                        listOf(RecEffect.EndTake(StopReason.BACKGROUND), RecEffect.CloseInput)
                is RecState.Opening, is RecState.Armed, is RecState.Failed, RecState.NoInput ->
                    m.copy(hostVisible = false, state = RecState.Paused) to listOf(RecEffect.CloseInput)
                else -> m.copy(hostVisible = false) to emptyList()
            }

            is RecEvent.HostStarted -> {
                val shown = m.copy(hostVisible = true)
                when {
                    s == RecState.Paused && e.granted -> {
                        val inputs = InputPicker.markNew(emptyList(), e.inputs)
                        openBestOrNone(shown.copy(inputs = inputs), inputs)
                    }
                    s == RecState.Paused -> shown.copy(state = permFor(e.rationale)) to emptyList()
                    s is RecState.NeedsPermission && e.granted -> {
                        val inputs = InputPicker.markNew(emptyList(), e.inputs)
                        openBestOrNone(shown.copy(inputs = inputs), inputs)
                    }
                    else -> shown to emptyList()
                }
            }
        }
    }

    private fun devices(m: RecModel, list: List<InputInfo>): Step {
        val s = m.state
        if (s == RecState.Idle) return m to emptyList()              // recorder not shown: nothing listens
        val marked = InputPicker.markNew(m.inputs, list)
        val base = m.copy(inputs = marked)
        return when (s) {
            is RecState.Opening, is RecState.Armed -> {
                val i = (s as? RecState.Opening)?.input ?: (s as RecState.Armed).input
                if (list.none { it.id == i.id }) {
                    val nxt = best(marked, m.selectedKey)
                    if (nxt != null) open(base, nxt, RecEffect.Say("${L(i)} unplugged. Using ${L(nxt)}."))
                    else base.copy(state = RecState.NoInput, note = null) to
                        listOf(RecEffect.CloseInput, RecEffect.Say("${L(i)} unplugged."))
                } else {
                    val t = InputPicker.autoSwitchTarget(i, marked, m.userPicked)
                    if (t != null) open(base, t, RecEffect.Say("Using ${L(t)}.")) else base to emptyList()
                }
            }
            is RecState.Recording ->
                if (list.none { it.id == s.input.id })
                    base.copy(state = RecState.Finalizing(s.input, s.open, s.takeId, StopReason.UNPLUGGED)) to listOf(RecEffect.EndTake(StopReason.UNPLUGGED))
                else base to emptyList()                               // never switch mid-take
            RecState.NoInput -> {
                val b = best(marked, m.selectedKey)
                if (b != null) open(base, b) else base to emptyList()
            }
            // Finalizing / Failed / Paused / NeedsPermission: keep the list fresh, change nothing else.
            else -> base to emptyList()
        }
    }

    /** What follows the end of a take. [lead] effects run first. */
    private fun next(m: RecModel, s: RecState.Finalizing, lead: List<RecEffect>): Step {
        val i = s.input
        val r = s.reason
        val (m2, fx) = when {
            r == StopReason.BACKGROUND -> m.copy(state = RecState.Paused) to emptyList()    // input already closed
            !m.hostVisible -> m.copy(state = RecState.Paused) to listOf<RecEffect>(RecEffect.CloseInput)
            r == StopReason.UNPLUGGED -> openBestOrNone(m, m.inputs.filter { it.id != i.id })
            r == StopReason.READ_ERROR || r == StopReason.ROUTE_CHANGED ->
                if (m.inputs.any { it.id == i.id }) open(m, i) else openBestOrNone(m, m.inputs)
            else -> m.copy(state = RecState.Armed(i, s.open)) to emptyList()                // monitoring continues
        }
        return m2 to (lead + fx)
    }
}

object InputPicker {
    /** Drops BLUETOOTH and HIDDEN, collapses the built-in mics to the lowest id, orders by rank then id. */
    fun visible(all: List<InputInfo>): List<InputInfo> {
        val shown = all.filter { it.kind != InputKind.BLUETOOTH && it.kind != InputKind.HIDDEN }
        val oneMic = shown.filter { it.kind == InputKind.BUILTIN_MIC }.minByOrNull { it.id }
        val keep = shown.filter { it.kind != InputKind.BUILTIN_MIC } + listOfNotNull(oneMic)
        return keep.sortedWith(compareBy<InputInfo>({ rank(it.kind) }, { it.id }))
    }

    fun rank(k: InputKind): Int = when (k) {
        InputKind.USB_DEVICE -> 0
        InputKind.USB_HEADSET -> 1
        InputKind.LINE_IN -> 2
        InputKind.WIRED_HEADSET -> 3
        InputKind.BUILTIN_MIC -> 4
        else -> 9
    }

    fun choose(inputs: List<InputInfo>, selectedKey: String?): InputInfo? {
        val cmp = compareBy<InputInfo>({ rank(it.kind) }, { it.id })
        if (selectedKey != null) inputs.filter { it.key == selectedKey }.minWithOrNull(cmp)?.let { return it }
        return inputs.minWithOrNull(cmp)
    }

    fun markNew(old: List<InputInfo>, new: List<InputInfo>): List<InputInfo> {
        if (old.isEmpty()) return new.map { it.copy(isNew = false) }
        val keys = old.map { it.key }.toSet()
        return new.map { it.copy(isNew = it.key !in keys) }
    }

    fun autoSwitchTarget(current: InputInfo, list: List<InputInfo>, userPicked: Boolean): InputInfo? {
        if (userPicked) return null
        val b = choose(list, null) ?: return null
        val fast = b.kind == InputKind.USB_DEVICE || b.kind == InputKind.USB_HEADSET || b.kind == InputKind.LINE_IN
        return if (b.isNew && fast && b.id != current.id) b else null
    }

    fun label(i: InputInfo): String = when (i.kind) {
        InputKind.BUILTIN_MIC -> "Phone mic"
        InputKind.WIRED_HEADSET -> "Headset mic"
        InputKind.LINE_IN -> "Line in"
        InputKind.USB_DEVICE, InputKind.USB_HEADSET -> "USB · " + (if (i.name.isBlank()) "audio" else i.name.take(16))
        InputKind.BLUETOOTH -> "Bluetooth"
        InputKind.HIDDEN -> "Other input"
    }

    fun detail(i: InputInfo): String {
        val base = when (i.kind) {
            InputKind.BUILTIN_MIC -> "Built‑in · mono"
            InputKind.WIRED_HEADSET -> "Headset jack · mono"
            InputKind.LINE_IN -> "Line input"
            InputKind.USB_DEVICE, InputKind.USB_HEADSET ->
                if (i.channelCounts.isEmpty() || 2 in i.channelCounts) "USB · up to 2 ch" else "USB · mono"
            else -> ""
        }
        return if (i.isNew) "$base · NEW" else base
    }
}

object FormatLadder {
    const val TARGET_RATE = 48000
    const val CHUNK_MS = 20
    const val PREROLL_MS = 500
    const val MAX_TAKE_S = 1800
    const val MIN_TAKE_S = 10
    const val PROBE_MS = 1000

    fun sources(unprocessedSupported: Boolean): List<Source> =
        if (unprocessedSupported) listOf(Source.UNPROCESSED, Source.VOICE_RECOGNITION, Source.MIC)
        else listOf(Source.VOICE_RECOGNITION, Source.MIC)

    fun rungs(i: InputInfo): List<Rung> = when (i.kind) {
        InputKind.USB_DEVICE, InputKind.USB_HEADSET, InputKind.LINE_IN -> {
            val stereo = i.channelCounts.isEmpty() || 2 in i.channelCounts
            val rates = listOf(48000, 44100).filter { i.rates.isEmpty() || it in i.rates }
                .ifEmpty { listOf(i.rates.max()) }
            buildList {
                for (r in rates) { if (stereo) add(Rung(r, 2)); add(Rung(r, 1)) }
                if (stereo) add(Rung(0, 2))
                add(Rung(0, 1))
            }
        }
        else -> listOf(Rung(48000, 1), Rung(44100, 1), Rung(0, 1))
    }

    /** Source-major: for each source, every rung. A cached "SOURCE|rate|ch" that is in the plan goes first. */
    fun plan(i: InputInfo, unprocessedSupported: Boolean, cached: String?): List<Pair<Source, Rung>> {
        val rungs = rungs(i)
        val all = sources(unprocessedSupported).flatMap { s -> rungs.map { s to it } }
        val hit = cached?.let { decode(it) }?.takeIf { it in all } ?: return all
        return listOf(hit) + all.filter { it != hit }
    }

    fun encode(s: Source, r: Rung): String = "${s.name}|${r.rate}|${r.channels}"

    fun decode(s: String): Pair<Source, Rung>? {
        val p = s.split("|")
        if (p.size != 3) return null
        val src = Source.values().firstOrNull { it.name == p[0] } ?: return null
        val rate = p[1].toIntOrNull() ?: return null
        val ch = p[2].toIntOrNull() ?: return null
        if (rate < 0 || ch !in 1..2) return null
        return src to Rung(rate, ch)
    }

    fun bufferBytes(minBytes: Int, rate: Int, channels: Int): Int {
        val half = (if (rate > 0) rate else 48000) * channels * 2 / 2
        val want = max(4 * minBytes, half)
        val unit = 2 * channels
        return (want + unit - 1) / unit * unit
    }

    fun chunkFrames(rate: Int): Int = rate * CHUNK_MS / 1000
}

object TakeBudget {
    const val RESERVE_BYTES = 64L shl 20

    fun maxSeconds(usableBytes: Long, bytesPerSec: Int, cap: Int = FormatLadder.MAX_TAKE_S): Int {
        if (bytesPerSec <= 0 || usableBytes <= RESERVE_BYTES) return 0
        return ((usableBytes - RESERVE_BYTES) / bytesPerSec).coerceIn(0L, cap.toLong()).toInt()
    }

    fun allows(seconds: Int): Boolean = seconds >= FormatLadder.MIN_TAKE_S
}

object ChopBudget {
    const val HEADROOM = 16L shl 20

    fun frames48(frames: Long, rate: Int): Long = (frames * 48000L + rate - 1) / rate

    fun need(frames: Long, rate: Int): Long {
        val f48 = frames48(frames, rate)
        return max(4 * frames + (if (rate != 48000) 4 * f48 else 0L), 12 * f48) + HEADROOM
    }

    fun fits(frames: Long, rate: Int, maxMemory: Long, usedBytes: Long): Boolean = need(frames, rate) <= maxMemory - usedBytes
}

object Clock {
    fun of(seconds: Double): String {
        val tenths = Math.floor(max(0.0, seconds) * 10).toLong()
        val whole = tenths / 10
        return String.format(Locale.ROOT, "%d:%02d.%d", whole / 60, whole % 60, tenths % 10)
    }

    fun khz(rate: Int): String =
        if (rate % 1000 == 0) "${rate / 1000} kHz" else String.format(Locale.ROOT, "%.1f kHz", rate / 1000.0)

    fun spoken(seconds: Double): String {
        val total = Math.floor(max(0.0, seconds)).toLong()
        return "Recorded ${total / 60} minutes ${total % 60} seconds"
    }
}

/** Every user-visible line the reducer emits. */
object TakeText {
    fun stopLine(reason: StopReason, t: Take): String {
        val c = Clock.of(t.seconds)
        val n = t.inputName
        return when (reason) {
            StopReason.USER -> "Recorded $c."
            StopReason.UNPLUGGED -> "Stopped: $n was unplugged. The $c before that is kept."
            StopReason.ROUTE_CHANGED -> "Stopped: Android moved the input away from $n. The $c before that is kept."
            StopReason.SILENCED -> "Stopped: a call or another app took the microphone. The $c before that is kept."
            StopReason.READ_ERROR -> "Stopped: $n stopped sending audio. The $c before that is kept."
            StopReason.BACKGROUND -> "Stopped when Rytm Mobile left the screen. The $c before that is kept."
            StopReason.MAX_LENGTH -> "Stopped at the take limit ($c)."
            StopReason.DISK_FULL -> "Stopped: phone storage is full. The $c before that is kept."
            StopReason.RECOVERED -> "Recovered after the app closed during a take ($c)."
        }
    }

    fun recovered(n: Int): String = if (n == 1) "Recovered 1 unfinished take." else "Recovered $n unfinished takes."
}

data class FakeSpec(
    val hz: Float = 440f, val levelDb: Float = -12f, val clipAt: Float? = null,
    val unplugAt: Float? = null, val deadAt: Float? = null, val silenceAt: Float? = null,
    val stereo: Boolean = false, val deadRight: Boolean = false,
) {
    companion object {
        /** Comma-separated tokens: sine<hz>, level<dB>, clip@<s>, unplug@<s>, dead@<s>, silence@<s>, stereo, deadright. */
        fun parse(s: String): FakeSpec {
            var f = FakeSpec()
            for (raw in s.split(",")) {
                val t = raw.trim().lowercase(Locale.ROOT)
                f = when {
                    t == "stereo" -> f.copy(stereo = true)
                    t == "deadright" -> f.copy(deadRight = true)
                    t.startsWith("sine") -> t.removePrefix("sine").toFloatOrNull()?.let { f.copy(hz = it) } ?: f
                    t.startsWith("level") -> t.removePrefix("level").toFloatOrNull()?.let { f.copy(levelDb = it) } ?: f
                    t.startsWith("clip@") -> t.removePrefix("clip@").toFloatOrNull()?.let { f.copy(clipAt = it) } ?: f
                    t.startsWith("unplug@") -> t.removePrefix("unplug@").toFloatOrNull()?.let { f.copy(unplugAt = it) } ?: f
                    t.startsWith("dead@") -> t.removePrefix("dead@").toFloatOrNull()?.let { f.copy(deadAt = it) } ?: f
                    t.startsWith("silence@") -> t.removePrefix("silence@").toFloatOrNull()?.let { f.copy(silenceAt = it) } ?: f
                    else -> f
                }
            }
            return f
        }
    }
}

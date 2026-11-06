package com.bandersong.rytmmobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bandersong.rytmmobile.*

/** The Activity behind a Compose Context (it may be wrapped by themes or dialogs). */
internal fun Context.findActivity(): Activity {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    error("no Activity behind this Context")
}

private fun RecState.format(): OpenFormat? = when (this) {
    is RecState.Armed -> open; is RecState.Recording -> open; is RecState.Finalizing -> open; else -> null
}

/** The recorder, shown inside the Chop tab: input choice, level, the take just made, all takes, and REC. */
@Composable
fun RecorderScreen(vm: AppState, onAskAgain: () -> Unit, onOpenFile: () -> Unit) {
    val m = vm.recModel
    val r by vm.rec.meter.collectAsState()
    val live by vm.rec.live.collectAsState()
    val takes by vm.rec.takes.collectAsState()
    val act = LocalContext.current.findActivity()
    val st = m.state

    // Back closes the recorder; it never ends a take by accident (leaving the screen would).
    BackHandler { if (vm.capturing) vm.status = "Stop recording first." else vm.closeRecorder() }

    // A take that stops by itself, a recovery, a new input: say it, because the header status is not a live region.
    val notice by vm.rec.notice.collectAsState()
    val view = LocalView.current
    val firstSeq = remember { notice?.seq }
    LaunchedEffect(notice?.seq) {
        val n = notice
        if (n != null && n.seq != firstSeq) view.announceForAccessibility(n.text)
    }

    // The screen stays on while the mic is open: a locked screen ends the take.
    val awake = st is RecState.Opening || st is RecState.Armed || st is RecState.Recording || st is RecState.Finalizing
    DisposableEffect(awake) {
        if (awake) act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val showCards = st is RecState.NeedsPermission || st is RecState.NoInput || st is RecState.Paused
    val showInput = st is RecState.Opening || st is RecState.Armed || st is RecState.Recording ||
        st is RecState.Finalizing || st is RecState.Failed || st is RecState.Paused
    val showLevel = st is RecState.Armed || st is RecState.Recording || st is RecState.Finalizing
    val showTransport = showInput

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val short = maxHeight < 520.dp
        val cards: @Composable () -> Unit = { if (showCards) StateCards(vm, m, onAskAgain, onOpenFile) }
        val input: @Composable () -> Unit = { if (showInput) InputPanel(vm, m, st) }
        val level: @Composable () -> Unit = { if (showLevel) LevelPanel(vm, m, r) }
        val mid: @Composable () -> Unit = {
            if (st is RecState.Recording) LiveWave(live)
            else if (!m.capturing && m.lastTake != null && showLevel) TakeCard(vm, m.lastTake!!)
        }
        val list: @Composable () -> Unit = { TakesPanel(vm, m, takes) }
        val transport: @Composable () -> Unit = { if (showTransport) TransportBar(vm, m, r, short) }

        if (!wide) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 34.dp, end = 18.dp, top = 4.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) { cards(); input(); level(); mid(); list() }
                Box(Modifier.padding(start = 34.dp, end = 18.dp, bottom = 8.dp)) { transport() }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(start = 34.dp, end = 9.dp, top = 4.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) { cards(); input(); list() }
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = 9.dp, end = 18.dp)) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) { level(); mid() }
                    Box(Modifier.padding(bottom = 8.dp)) { transport() }
                }
            }
        }
    }
}

/* ───────────────────────────── state cards ───────────────────────────── */

@Composable
private fun StateCards(vm: AppState, m: RecModel, onAskAgain: () -> Unit, onOpenFile: () -> Unit) {
    when (val s = m.state) {
        is RecState.NeedsPermission -> Panel("Microphone", accent = P.Signal) {
            when (s.why) {
                Perm.ASKING -> {
                    Text("Allow the microphone in Android's prompt to record.", style = T.Body)
                    Spacer(Modifier.height(12.dp))
                    GhostButton("Close", vm::closeRecorder, tall = true)
                }
                Perm.RATIONALE -> {
                    Text("Recording needs the microphone. Rytm Mobile listens only while this screen is open, and keeps nothing until you tap REC (then the half second before the tap, so a take never starts late). Opening, chopping, sending and splitting all work without it.", style = T.Body)
                    Spacer(Modifier.height(14.dp))
                    HardButton("Allow microphone", onAskAgain, color = P.Signal)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Open a file instead", onOpenFile, tall = true)
                        GhostButton("Close", vm::closeRecorder, tall = true)
                    }
                }
                Perm.BLOCKED -> {
                    Text("Microphone access is off for Rytm Mobile. To record, allow Microphone for Rytm Mobile in the phone's Settings, under Apps, then come back here. Opening files still works.", style = T.Body)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Ask again", onAskAgain, color = P.Signal, tall = true)
                        GhostButton("Open a file instead", onOpenFile, tall = true)
                        GhostButton("Close", vm::closeRecorder, tall = true)
                    }
                }
            }
        }
        is RecState.NoInput -> Panel("Input", accent = P.Signal) {
            Row(verticalAlignment = Alignment.Top) {
                Jack(P.Signal, false, 22.dp); Spacer(Modifier.width(12.dp))
                Text("No microphone or audio input found. Plug in a USB mic or audio interface with a USB‑C OTG cable; it shows up here by itself. $MK1_NOTE", style = T.Body, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Open a file instead", onOpenFile, tall = true)
                GhostButton("Close", vm::closeRecorder, tall = true)
            }
        }
        is RecState.Paused -> Text("Paused while the app was in the background.", style = T.Small)
        else -> Unit
    }
}

/* ───────────────────────────── input ───────────────────────────── */

@Composable
private fun InputPanel(vm: AppState, m: RecModel, st: RecState) {
    val open = st.format()
    Panel(
        "Input",
        open?.let { "${Clock.khz(it.rate)} · ${if (it.channels == 2) "stereo" else "mono"} · 16‑bit" } ?: "phone or USB",
        accent = P.Signal,
        trailing = {
            Led(open != null, P.Signal, blink = st is RecState.Opening)
            Spacer(Modifier.width(8.dp))
            GhostButton("Close", vm::closeRecorder, tall = true, enabled = !m.capturing)
        },
    ) {
        Column(Modifier.selectableGroup()) {
            m.inputs.forEach { i ->
                val selected = i.id == m.current?.id
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(selected, enabled = !m.capturing, role = Role.RadioButton) { vm.selectInput(i.key) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Jack(P.Signal, selected, 22.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(InputPicker.label(i).uppercase(),
                            style = T.Title.copy(fontSize = 18.sp, color = if (selected) P.Ink else P.Muted),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(InputPicker.detail(i), style = T.Small)
                    }
                }
            }
        }
        if (open != null) {
            Spacer(Modifier.height(6.dp))
            Text((if (st is RecState.Armed) "Listening on " else "Recording from ") + open.routedName, style = T.Small)
        }
        m.note?.let { Spacer(Modifier.height(6.dp)); Text(it, style = T.Small.copy(color = P.Cv)) }
        if (st is RecState.Opening) { Spacer(Modifier.height(6.dp)); Text("Opening ${InputPicker.label(st.input)}…", style = T.Small) }
        if (st is RecState.Failed) {
            Spacer(Modifier.height(6.dp))
            Text(st.message, style = T.Small.copy(color = P.Signal))
            Spacer(Modifier.height(8.dp))
            GhostButton("Try again", vm::retryRecorder, color = P.Signal, tall = true)
        }
        // Always shown: the reader with a Rytm on the single USB port has no USB audio input to trigger it.
        Spacer(Modifier.height(8.dp))
        Text(MK1_NOTE, style = T.Small)
    }
}

private const val MK1_NOTE = "Rytm MK1: its USB port carries MIDI only. Patch its Main Out or Headphones into a USB interface to record it."

/* ───────────────────────────── level ───────────────────────────── */

@Composable
private fun LevelPanel(vm: AppState, m: RecModel, r: MeterReading) {
    val ch = m.state.format()?.channels ?: 1
    val words = MeterWords.of(r)
    val peaks = if (r.peakDb.size >= ch) r.peakDb else List(ch) { Db.FLOOR }
    val holds = if (r.holdDb.size >= ch) r.holdDb else List(ch) { Db.FLOOR }
    Panel("Level", words, accent = P.Signal, trailing = {
        Row(
            Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(role = Role.Button) { vm.resetClipLight() }
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = if (r.clipped) "Clipped. Double-tap to reset" else "No clipping"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Led(r.clipped, P.Signal); Spacer(Modifier.width(6.dp)); Text("CLIP", style = T.Label)
        }
    }) {
        for (c in 0 until ch) {
            MeterBar(peaks[c], holds[c], if (ch == 2) (if (c == 0) "L" else "R") else null, words, Modifier.fillMaxWidth())
            if (c < ch - 1) Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().padding(start = if (ch == 2) 22.dp else 0.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("−48", "−36", "−24", "−12", "0").forEach { Text(it, style = T.Label.copy(fontSize = 10.sp)) }
        }
        Spacer(Modifier.height(10.dp))
        val peak = peaks.maxOrNull() ?: Db.FLOOR
        val hold = holds.maxOrNull() ?: Db.FLOOR
        Row(Modifier.fillMaxWidth()) {
            Readout("Peak", "%.1f dB".format(peak), Modifier.weight(1f))
            Readout("Hold", "%.1f dB".format(hold), Modifier.weight(1f))
            Readout("Clips", "${r.clipCount}", Modifier.weight(1f))
        }
        if (ch == 2) {
            Spacer(Modifier.height(12.dp))
            // After a take, show what chopping that take will use (its peaks); before one, the live suggestion.
            val take = if (!m.capturing) m.lastTake else null
            val pick = ChannelPick.shown(vm.recPick, take?.peakDb, r.holdDb)
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch("L+R", pick == ChannelPick.MIX, { vm.recPick = ChannelPick.MIX }, exclusive = true)
                Switch("L only", pick == ChannelPick.LEFT, { vm.recPick = ChannelPick.LEFT }, exclusive = true)
                Switch("R only", pick == ChannelPick.RIGHT, { vm.recPick = ChannelPick.RIGHT }, exclusive = true)
            }
            Spacer(Modifier.height(6.dp))
            Text(if (take != null || vm.recPick != null) "Used when you chop or listen. The take keeps both channels."
                else "Suggested from the live level; each take is checked again when you chop or listen. The take keeps both channels.", style = T.Small)
        }
        val hint: Pair<String, Color>? = when {
            m.silenced -> "Another app or a call has the mic. You'd be recording silence." to P.Signal
            r.silentMs >= 3000 -> "No signal from ${m.current?.let { InputPicker.label(it) } ?: "the input"}. Check its cable and level, and that microphone access is on in Quick Settings." to P.Muted
            r.clipped -> "Clipped. Turn the source down and take it again." to P.Muted
            words == "hot" -> "Hot. Back the source off a little." to P.Muted
            words == "low" -> "Low. Turn the source up or move closer." to P.Muted
            else -> null
        }
        hint?.let { (t, c) -> Spacer(Modifier.height(10.dp)); Text(t, style = T.Small.copy(color = c)) }
    }
}

@Composable
private fun MeterBar(peakDb: Float, holdDb: Float, label: String?, words: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (label != null) { Text(label, style = T.Label, modifier = Modifier.width(22.dp)) }
        Canvas(
            Modifier.weight(1f).height(26.dp).clearAndSetSemantics {
                contentDescription = "Input level" + (label?.let { ", $it" } ?: "")
                stateDescription = words
            },
        ) {
            val n = MeterScale.SEGMENTS
            val gap = 3.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            val lit = MeterScale.lit(peakDb)
            for (k in 0 until n) {
                val x = k * (w + gap)
                if (k < lit) {
                    val c = when (MeterScale.zone(k)) { 0 -> P.Audio; 1 -> P.Cv; else -> P.Signal }
                    drawRoundRect(c, Offset(x, 0f), Size(w, size.height), CornerRadius(2f))
                } else {
                    drawRoundRect(P.Line, Offset(x, 0f), Size(w, size.height), CornerRadius(2f), style = Stroke(1.5f))
                }
            }
            val hx = ((holdDb + 48f) / 48f).coerceIn(0f, 1f) * size.width
            if (holdDb > -48f) drawRect(P.Ink, Offset((hx - 3.dp.toPx()).coerceAtLeast(0f), 0f), Size(3.dp.toPx(), size.height))
        }
    }
}

/* ───────────────────────────── live wave and take ───────────────────────────── */

@Composable
private fun LiveWave(cols: FloatArray) {
    Canvas(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(4.dp)).background(P.Bg).clearAndSetSemantics { }) {
        val slots = 200
        val w = size.width / slots; val mid = size.height / 2
        drawLine(P.Line, Offset(0f, mid), Offset(size.width, mid), 1f)
        val bw = maxOf(1.5f, w * .55f)
        for (i in cols.indices) {
            val x = size.width - (cols.size - i - .5f) * w
            val h = maxOf(1f, cols[i].coerceIn(0f, 1f) * mid * .94f)
            drawLine(P.Signal, Offset(x, mid - h), Offset(x, mid + h), bw)
        }
    }
}

@Composable
private fun TakeCard(vm: AppState, t: Take) {
    Panel(
        t.id,
        "${Clock.of(t.seconds)} · ${Clock.khz(t.rate)} ${if (t.channels == 2) "stereo" else "mono"} · peak ${"%.1f".format(t.peakDb.maxOrNull() ?: Db.FLOOR)} dB",
        accent = P.Cv,
    ) {
        Canvas(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(4.dp)).background(P.Bg).clearAndSetSemantics { }) {
            val n = t.peaks.size.coerceAtLeast(1); val w = size.width / n; val mid = size.height / 2
            drawLine(P.Line, Offset(0f, mid), Offset(size.width, mid), 1f)
            t.peaks.forEachIndexed { i, v ->
                val x = i * w + w / 2; val h = maxOf(1f, v.coerceIn(0f, 1f) * mid * .94f)
                drawLine(P.Cv, Offset(x, mid - h), Offset(x, mid + h), maxOf(1.5f, w * .55f))
            }
        }
        Spacer(Modifier.height(10.dp))
        if (t.stop != StopReason.USER) Text(TakeText.stopLine(t.stop, t), style = T.Small.copy(color = P.Signal))
        if (t.clips > 0) Text("Clipped in ${t.clips} places. Turn the source down and retake.", style = T.Small)
        if (t.dropouts > 0) Text("${t.dropouts} dropouts: the phone fell behind. Close other apps and retake.", style = T.Small.copy(color = P.Cv))
        Spacer(Modifier.height(10.dp))
        Switch("Auto‑trim", vm.autoTrim, vm::setAutoTrimPref)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HardButton("Chop it", { vm.useTake(t) }, color = P.Cv, small = true, enabled = !vm.busy)
            Spacer(Modifier.width(8.dp))
            if (vm.listening) GhostButton("Stop", vm::stopListen, color = P.Signal, tall = true)
            else GhostButton("Listen", { vm.listenTake(t) }, tall = true, enabled = !vm.busy)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Save", { vm.saveTake(t) }, color = P.Audio, tall = true, enabled = !vm.busy)
            GhostButton("Share", { vm.shareTake(t) }, color = P.Audio, tall = true, enabled = !vm.busy)
            GhostButton("Split", { vm.splitTake(t) }, color = P.Audio, tall = true, enabled = !vm.busy)
        }
    }
}

/* ───────────────────────────── takes ───────────────────────────── */

@Composable
private fun TakesPanel(vm: AppState, m: RecModel, takes: List<Take>) {
    var all by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Take?>(null) }
    confirm?.let { t ->
        AlertDialog(
            onDismissRequest = { confirm = null }, containerColor = P.Panel,
            title = { Text("DELETE ${t.id}?", style = T.Title) },
            text = { Text("It goes from the phone's take list. A copy you saved to Music stays.", style = T.Body) },
            confirmButton = { HardButton("Delete", { vm.deleteTake(t); confirm = null }, color = P.Signal, small = true) },
            dismissButton = { GhostButton("Keep", { confirm = null }, tall = true) },
        )
    }
    Panel("Takes", "${takes.size} · ${"%.0f".format(takes.sumOf { it.bytes } / 1e6)} MB", accent = P.Cv) {
        if (takes.isEmpty()) Text("Takes you record show up here. They stay on the phone until you delete them.", style = T.Small)
        (if (all) takes else takes.take(6)).forEach { t ->
            val peak = "%.1f".format(t.peakDb.maxOrNull() ?: Db.FLOOR)
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Jack(P.Cv, t.id == m.lastTake?.id, 18.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.id, style = T.Title.copy(fontSize = 17.sp), maxLines = 1)
                    Text("${Clock.of(t.seconds)} · ${t.inputName} · $peak dB" +
                        (if (t.clips > 0) " · CLIPPED" else "") + (if (t.stop == StopReason.RECOVERED) " · RECOVERED" else ""),
                        style = T.Small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(6.dp))
                GhostButton("Chop", { vm.useTake(t) }, Modifier.semantics { contentDescription = "Chop take ${t.id}" },
                    color = P.Cv, tall = true, enabled = !vm.busy && !m.capturing)
                Box(
                    Modifier.size(48.dp).clickable(role = Role.Button) { confirm = t }
                        .semantics { contentDescription = "Delete take ${t.id}" },
                    contentAlignment = Alignment.Center,
                ) { Text("×", style = T.Title.copy(color = P.Muted, fontSize = 20.sp)) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(P.Line.copy(alpha = .5f)))
        }
        if (takes.size > 6) GhostButton(if (all) "Show fewer" else "Show all ${takes.size}", { all = !all }, color = P.Cv, tall = true)
        val gb = takes.sumOf { it.bytes } / 1e9
        if (gb > 1.0) { Spacer(Modifier.height(8.dp)); Text("Takes use ${"%.1f".format(gb)} GB. Delete ones you don't need.", style = T.Small) }
    }
}

/* ───────────────────────────── transport ───────────────────────────── */

@Composable
private fun TransportBar(vm: AppState, m: RecModel, r: MeterReading, short: Boolean) {
    val st = m.state
    val fmt = st.format()
    val rate = fmt?.rate?.takeIf { it > 0 } ?: r.rate.takeIf { it > 0 } ?: 48000
    val sec = if (st is RecState.Recording) r.takeFrames / rate.toDouble() else 0.0
    // Armed shows the limit REC will really apply: the 30-minute cap, or less when free storage is short.
    val armedMax = (st as? RecState.Armed)?.let { a ->
        remember(a.input.id, a.open.rate, a.open.channels) {
            TakeBudget.maxSeconds(vm.rec.takeDir.usableSpace, a.open.rate * a.open.channels * 2)
        }
    }
    val maxS = (st as? RecState.Recording)?.maxSeconds ?: armedMax ?: FormatLadder.MAX_TAKE_S
    val timer = TransportText.timer(st, sec)
    val timerColor = when {
        st is RecState.Recording && sec >= maxS - 30 -> P.Cv
        st is RecState.Recording -> P.Signal
        else -> P.Ink
    }
    val spoken = TransportText.spoken(st, sec)
    val (recDesc, recState) = TransportText.recButton(st)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(timer, style = T.Data.copy(fontSize = if (short) 26.sp else 34.sp, color = timerColor), maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { contentDescription = spoken })
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("MAX ${Clock.of(maxS.toDouble())}", style = T.Label)
                if (fmt != null) Text("${Clock.khz(fmt.rate)} ${if (fmt.channels == 2) "STEREO" else "MONO"} 16‑BIT", style = T.Label)
                if (st is RecState.Armed) Text("NOTHING IS KEPT UNTIL YOU TAP REC", style = T.Label, textAlign = TextAlign.End)
            }
        }
        Spacer(Modifier.height(if (short) 6.dp else 10.dp))
        RecButton(
            recording = st is RecState.Recording,
            enabled = st is RecState.Armed || st is RecState.Recording,
            label = when (st) { is RecState.Recording -> "Stop"; is RecState.Opening -> "Opening…"; is RecState.Finalizing -> "Saving…"; else -> "Rec" },
            onClick = vm::toggleRec,
            height = if (short) 56.dp else 72.dp,
            description = recDesc, stateText = recState,
        )
    }
}

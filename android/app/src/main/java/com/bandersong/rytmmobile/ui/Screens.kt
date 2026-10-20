package com.bandersong.rytmmobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bandersong.rytmmobile.*
import com.bandersong.rytmmobile.R
import kotlin.math.abs

private val SECTIONS = listOf("Rytm" to P.Signal, "Chop" to P.Cv, "Split" to P.Audio)

@Composable
fun App(vm: AppState) {
    Column(Modifier.fillMaxSize().background(P.Bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // the signal rail down the left edge, as on the reference site
            Box(Modifier.padding(start = 17.dp).width(2.dp).fillMaxHeight().background(P.Signal.copy(alpha = .75f)))
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
              // Landscape phones and the folded/unfolded Fold: a short window gets a one-line header,
              // and a wide one keeps the content at a readable width.
              val compact = maxHeight < 520.dp
              Column(Modifier.fillMaxHeight().widthIn(max = 760.dp).align(Alignment.TopStart)) {
                Header(vm, compact)
                vm.update?.let { UpdateBanner(vm, it) }
                AnimatedContent(vm.tab, Modifier.weight(1f), label = "tab",
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }) { t ->
                    Box(Modifier.fillMaxSize()) {
                        when (t) { 0 -> RytmScreen(vm); 1 -> ChopScreen(vm); else -> SplitScreen(vm) }
                    }
                }
              }
            }
        }
        PatchBay(SECTIONS, vm.tab, { vm.tab = it },
            lit = listOf(vm.link != null, vm.clip != null, vm.job?.status == "done"))
    }
}

@Composable
private fun Header(vm: AppState, compact: Boolean) {
    val (name, color) = SECTIONS[vm.tab]
    if (compact) {
        Column(Modifier.padding(start = 34.dp, end = 18.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Box(Modifier.offset(x = (-23).dp, y = 6.dp).size(12.dp).clip(RoundedCornerShape(50)).background(color).border(2.dp, P.Shade, RoundedCornerShape(50)))
                    Text(name.uppercase(), style = T.Title)
                }
                Spacer(Modifier.width(12.dp))
                Led(vm.failed || vm.busy || vm.status != "Not connected", when { vm.failed -> P.Signal; vm.busy -> P.Cv; else -> Color(0xFF7FD48A) }, blink = vm.busy)
                Spacer(Modifier.width(8.dp))
                Text(vm.status, style = T.Data.copy(fontSize = 12.sp, color = if (vm.failed) P.Signal else P.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (vm.busy && vm.progress > 0f) { Spacer(Modifier.height(6.dp)); StepProgress(vm.progress, color = color) }
        }
        return
    }
    Column(Modifier.padding(start = 34.dp, end = 18.dp, top = 14.dp, bottom = 10.dp)) {
        Box {
            Kicker("Rytm Mobile · " + when (vm.tab) { 0 -> "+Drive transfer"; 1 -> "trim and send"; else -> "stem separation" }, color)
        }
        Spacer(Modifier.height(6.dp))
        Box {
            // node on the rail, level with the headline
            Box(Modifier.offset(x = (-23).dp, y = 13.dp).size(14.dp).clip(RoundedCornerShape(50)).background(color).border(2.dp, P.Shade, RoundedCornerShape(50)))
            Text(name.uppercase(), style = T.Hero)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Led(vm.failed || vm.busy || vm.status != "Not connected", when { vm.failed -> P.Signal; vm.busy -> P.Cv; else -> Color(0xFF7FD48A) }, blink = vm.busy)
            Spacer(Modifier.width(8.dp))
            Text(vm.status, style = T.Data.copy(fontSize = 12.sp, color = if (vm.failed) P.Signal else P.Muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (vm.busy && vm.progress > 0f) { Spacer(Modifier.height(10.dp)); StepProgress(vm.progress, color = color) }
    }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 34.dp, end = 18.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content)

@Composable
private fun Print(res: Int) = Image(
    painterResource(res), null, contentScale = ContentScale.Crop,
    modifier = Modifier.fillMaxWidth().aspectRatio(16 / 9f).clip(RoundedCornerShape(2.dp, 14.dp, 2.dp, 14.dp))
        .border(1.dp, P.Line, RoundedCornerShape(2.dp, 14.dp, 2.dp, 14.dp)),
)

/** A new version on the download site: what's new, and one tap to install it. */
@Composable
private fun UpdateBanner(vm: AppState, r: Updater.Release) {
    Row(
        Modifier.padding(start = 34.dp, end = 18.dp, bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(P.Panel).border(1.dp, P.Cv.copy(alpha = .7f), RoundedCornerShape(6.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Led(true, P.Cv, blink = vm.updateProgress >= 0f)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("UPDATE ${r.name}", style = T.Title.copy(fontSize = 18.sp))
            if (r.notes.isNotBlank()) Text(r.notes, style = T.Small, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (vm.updateProgress >= 0f) { Spacer(Modifier.height(6.dp)); StepProgress(vm.updateProgress, color = P.Cv) }
        }
        Spacer(Modifier.width(10.dp))
        HardButton(if (vm.updateProgress >= 0f) "…" else "Update", { vm.installUpdate() }, small = true, enabled = vm.updateProgress < 0f)
    }
}

/* ───────────────────────────── RYTM ───────────────────────────── */

@Composable
private fun RytmScreen(vm: AppState) = Page {
    val link = vm.link
    if (link == null) {
        Print(R.drawable.empty_connect)
        Text("Patch the Rytm into the phone with a USB‑C OTG cable. Set its USB mode to USB MIDI.", style = T.Body)
        HardButton("Patch in", { vm.connect() }, color = P.Signal, enabled = !vm.busy)
        Text("Rytm Mobile ${BuildConfig.VERSION_NAME}", style = T.Label)
        if (vm.diag.isNotEmpty()) LogPanel(vm.diag)
        return@Page
    }
    var confirm by remember { mutableStateOf<Elektron.Entry?>(null) }
    var newDir by remember { mutableStateOf("") }
    Panel(link.name, "OS ${link.version}", accent = P.Signal, trailing = { Led(true, Color(0xFF7FD48A)) }) {
        Breadcrumb(vm.dir) { vm.open(it) }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Field("New folder", newDir, { newDir = AppState.clean(it) }, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            HardButton("Add", { vm.mkdir(newDir); newDir = "" }, small = true, enabled = newDir.isNotBlank() && !vm.busy)
        }
    }
    Panel("+Drive", "${vm.entries.count { !it.isDir }} samples · ${vm.entries.count { it.isDir }} folders",
        accent = P.Cv, trailing = { GhostButton("Refresh", { vm.open(vm.dir) }, enabled = !vm.busy) }) {
        if (vm.dir != "/") EntryRow("..", null, true, onOpen = { vm.open(vm.dir.substringBeforeLast('/').ifEmpty { "/" }) })
        if (vm.entries.isEmpty()) Text("Empty folder.", style = T.Small)
        vm.entries.forEach { e ->
            EntryRow(e.name, if (e.isDir) null else (e.size - 64) / 96000.0, e.isDir,
                onOpen = { if (e.isDir) vm.open(AppState.join(vm.dir, e.name)) },
                onDelete = if (e.isDir) null else ({ confirm = e }))
        }
    }
    var showLog by remember { mutableStateOf(false) }
    if (vm.diag.isNotEmpty()) {
        if (showLog) LogPanel(vm.diag) else GhostButton("Show log", { showLog = true })
    }
    confirm?.let { e ->
        AlertDialog(
            onDismissRequest = { confirm = null }, containerColor = P.Panel,
            title = { Text("DELETE ${e.name.uppercase()}?", style = T.Title) },
            text = { Text("It goes from the Rytm's +Drive. Samples already loaded into RAM stay.", style = T.Body) },
            confirmButton = { HardButton("Delete", { vm.delete(e); confirm = null }, color = P.Signal, small = true) },
            dismissButton = { GhostButton("Keep", { confirm = null }) },
        )
    }
}

@Composable
private fun Breadcrumb(dir: String, onOpen: (String) -> Unit) {
    val parts = dir.split('/').filter { it.isNotEmpty() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Text("+DRIVE", style = T.Data.copy(color = P.Cv), modifier = Modifier.clickable { onOpen("/") })
        parts.forEachIndexed { i, p ->
            Text("  /  ", style = T.Data.copy(color = P.Line))
            Text(p, style = T.Data.copy(color = if (i == parts.lastIndex) P.Ink else P.Cv),
                modifier = Modifier.clickable { onOpen("/" + parts.take(i + 1).joinToString("/")) })
        }
    }
}

@Composable
private fun EntryRow(name: String, seconds: Double?, isDir: Boolean, onOpen: () -> Unit, onDelete: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = isDir, onClick = onOpen).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Jack(if (isDir) P.Cv else P.Signal, isDir || seconds != null, 18.dp)
        Spacer(Modifier.width(12.dp))
        Text(name, style = T.Body.copy(fontFamily = if (isDir) Display else Body, fontSize = if (isDir) 18.sp else 15.sp),
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (seconds != null) Text("%.2fs".format(seconds), style = T.Data.copy(color = P.Muted, fontSize = 12.sp))
        if (isDir) Text("›", style = T.Title.copy(color = P.Cv))
        if (onDelete != null) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(32.dp).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
                Text("×", style = T.Title.copy(color = P.Muted, fontSize = 20.sp))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(P.Line.copy(alpha = .5f)))
}

@Composable
private fun LogPanel(log: String) {
    val cb = LocalClipboardManager.current
    Panel("Log", "what the Rytm said", accent = P.Signal, trailing = { GhostButton("Copy", { cb.setText(AnnotatedString(log)) }) }) {
        Text(log.trimEnd(), style = T.Data.copy(fontSize = 11.sp, lineHeight = 15.sp, color = P.Muted))
    }
}

/* ───────────────────────────── CHOP ───────────────────────────── */

@Composable
private fun ChopScreen(vm: AppState) = Page {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::load) }
    val c = vm.clip
    if (c == null) {
        EmptyWave()
        Text("Any audio or video: MP3, WAV, M4A, FLAC, screen recordings, voice memos. It comes out 48 kHz, 16‑bit mono, the Rytm's native format.", style = T.Body)
        HardButton("Open audio or video", { pick.launch(MEDIA) }, enabled = !vm.busy)
        return@Page
    }
    Panel("Sample", "48k mono", accent = P.Cv, trailing = { GhostButton("Open", { pick.launch(MEDIA) }, enabled = !vm.busy) }) {
        Field("Name on Rytm", vm.clipName, { vm.clipName = AppState.clean(it) })
        Spacer(Modifier.height(14.dp))
        Wave(c, vm.start, vm.end) { s, e -> vm.start = s; vm.end = e }
        Spacer(Modifier.height(12.dp))
        val len = (vm.end - vm.start) * c.seconds
        Row(Modifier.fillMaxWidth()) {
            Readout("Start", "%.3f".format(vm.start * c.seconds), Modifier.weight(1f))
            Readout("End", "%.3f".format(vm.end * c.seconds), Modifier.weight(1f))
            Readout("Length", "%.2fs".format(len), Modifier.weight(1f))
            Readout("Size", "%.0fK".format(len * 96), Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch("Normalize", vm.normalize, { vm.normalize = it })
            Switch("Fades", vm.fades, { vm.fades = it })
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HardButton("Play", { vm.prepared()?.let(Audio::play) }, small = true, color = P.Ink)
            Spacer(Modifier.width(8.dp))
            GhostButton("Stop", { Audio.stop() })
        }
    }
    if (vm.link == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Patch the Rytm in to send.", style = T.Small, modifier = Modifier.weight(1f))
            GhostButton("Go to Rytm", { vm.tab = 0 }, color = P.Signal)
        }
    } else {
        HardButton("Send → ${AppState.join(vm.dir, vm.clipName.ifBlank { "sample" })}", { vm.send() }, color = P.Signal, enabled = !vm.busy)
    }
}

@Composable
private fun Readout(label: String, value: String, modifier: Modifier) = Column(modifier) {
    Text(label.uppercase(), style = T.Label.copy(fontSize = 10.sp)); Text(value, style = T.Data.copy(fontSize = 15.sp))
}

@Composable
private fun EmptyWave() {
    Canvas(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(6.dp)).background(P.Panel).border(1.dp, P.Line, RoundedCornerShape(6.dp))) {
        val mid = size.height / 2
        drawLine(P.Line, Offset(0f, mid), Offset(size.width, mid), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        for (i in 0 until 48) {
            val x = size.width * (i + .5f) / 48
            val h = (kotlin.math.sin(i * .7f) * .5f + .55f) * mid * .6f * (1 - abs(i - 24) / 30f)
            drawLine(P.Cv.copy(alpha = .35f), Offset(x, mid - h), Offset(x, mid + h), 4f)
        }
    }
}

/** Waveform with draggable amber start/end handles; tap to move the nearest one. */
@Composable
private fun Wave(c: Clip, start: Float, end: Float, onChange: (Float, Float) -> Unit) {
    val n = 220
    // per column: peak and RMS, both scaled to the clip's loudest peak
    val cols = remember(c) {
        val per = maxOf(1, c.data.size / n)
        val pk = FloatArray(n); val rm = FloatArray(n)
        for (i in 0 until n) {
            var m = 0f; var sq = 0.0; var k = i * per; val end = minOf(c.data.size, (i + 1) * per)
            while (k < end) { val v = c.data[k]; m = maxOf(m, abs(v)); sq += v * v; k++ }
            pk[i] = m; rm[i] = kotlin.math.sqrt(sq / maxOf(1, end - i * per)).toFloat()
        }
        val mx = pk.maxOrNull()?.takeIf { it > 0 } ?: 1f
        Pair(FloatArray(n) { pk[it] / mx }, FloatArray(n) { rm[it] / mx })
    }
    val s by rememberUpdatedState(start); val e by rememberUpdatedState(end)
    var grab by remember { mutableIntStateOf(-1) }
    Canvas(
        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(4.dp)).background(P.Bg)
            .pointerInput(c) {
                fun pick(x: Float): Int = if (abs(x / size.width - s) < abs(x / size.width - e)) 0 else 1
                fun move(h: Int, x: Float) {
                    val f = (x / size.width).coerceIn(0f, 1f)
                    if (h == 0) onChange(minOf(f, e - .002f).coerceAtLeast(0f), e) else onChange(s, maxOf(f, s + .002f).coerceAtMost(1f))
                }
                detectDragGestures(onDragStart = { grab = pick(it.x); move(grab, it.x) }, onDragEnd = { grab = -1 }) { ch, _ -> move(grab, ch.position.x) }
            }
            .pointerInput(c) { detectTapGestures { val h = if (abs(it.x / size.width - s) < abs(it.x / size.width - e)) 0 else 1; val f = (it.x / size.width).coerceIn(0f, 1f); if (h == 0) onChange(minOf(f, e - .002f), e) else onChange(s, maxOf(f, s + .002f)) } }
    ) {
        val w = size.width / n; val mid = size.height / 2
        // 16 grid lines, a nod to the step row
        for (g in 1 until 16) drawLine(P.Line.copy(alpha = if (g % 4 == 0) .7f else .3f), Offset(size.width * g / 16, 0f), Offset(size.width * g / 16, size.height), 1f)
        val (pk, rm) = cols
        for (i in 0 until n) {
            val f = (i + .5f) / n
            val inside = f in s..e
            val x = i * w + w / 2; val bw = maxOf(1.5f, w * .55f)
            val hp = maxOf(1f, pk[i] * mid * .94f); val hr = maxOf(1f, rm[i] * mid * .94f)
            val col = if (inside) P.Signal else P.Muted
            drawLine(col.copy(alpha = if (inside) .38f else .16f), Offset(x, mid - hp), Offset(x, mid + hp), bw)
            drawLine(col.copy(alpha = if (inside) 1f else .4f), Offset(x, mid - hr), Offset(x, mid + hr), bw)
        }
        drawLine(P.Line, Offset(0f, mid), Offset(size.width, mid), 1f)
        val xs = s * size.width; val xe = e * size.width
        drawRect(P.Shade.copy(alpha = .55f), Offset.Zero, Size(xs, size.height))
        drawRect(P.Shade.copy(alpha = .55f), Offset(xe, 0f), Size(size.width - xe, size.height))
        for ((x, k) in listOf(xs to 0, xe to 1)) {
            drawLine(P.Cv, Offset(x, 0f), Offset(x, size.height), 3f)
            val tab = 26f; val ty = if (k == 0) 0f else size.height - tab
            drawRoundRect(P.Cv, Offset(x - (if (k == 0) 0f else tab), ty), Size(tab, tab), CornerRadius(4f))
            if (grab == k) drawCircle(P.Cv.copy(alpha = .25f), 40f, Offset(x, mid))
        }
    }
}

/* ───────────────────────────── SPLIT ───────────────────────────── */

private val MODE_LABEL = mapOf("stems" to "6 stems", "demucs" to "4 stems · fast", "vocals" to "Vocals", "drumkit" to "Drum kit")
private fun stemColor(s: String) = when {
    s.startsWith("drum") || s in setOf("kick", "snare", "toms", "hh", "ride", "crash") -> P.Signal
    s == "bass" -> P.Cv
    s.startsWith("vocal") -> P.Audio
    else -> Color(0xFFB9A6F2)
}

@Composable
private fun SplitScreen(vm: AppState) = Page {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::separate) }
    var editServer by remember { mutableStateOf(vm.models.isEmpty()) }
    val online = vm.models.isNotEmpty()
    // a pairing link (or Connect) that succeeds folds the address fields away
    LaunchedEffect(online) { if (online) editServer = false }
    Panel("Mac", if (online) "online · BS‑RoFormer" else "stem server", accent = P.Audio,
        trailing = { Led(online, Color(0xFF7FD48A)) }) {
        if (editServer || !online) {
            if (vm.server.isBlank()) {
                Text("Splitting runs on your own computer. On a Mac or Linux PC, run this in Terminal, then scan the code it prints with your phone's camera:", style = T.Small)
                Spacer(Modifier.height(8.dp))
                Text("curl -fsSL rytm-mobile.pages.dev/install.sh | bash", style = T.Data.copy(fontSize = 12.sp, color = P.Audio),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(P.Bg).padding(10.dp))
                Spacer(Modifier.height(12.dp))
                Text("Or type its details in:", style = T.Small)
                Spacer(Modifier.height(8.dp))
            }
            Field("Server (Tailscale)", vm.server, { vm.server = it.trim() })
            Spacer(Modifier.height(10.dp))
            Field("Token", vm.token, { vm.token = it.trim() }, secret = true)
            Spacer(Modifier.height(12.dp))
            HardButton("Connect", { vm.fetchModels(); editServer = false }, small = true, color = P.Audio, enabled = !vm.busy)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(vm.server.removePrefix("http://"), style = T.Data.copy(color = P.Muted), modifier = Modifier.weight(1f))
                GhostButton("Edit", { editServer = true })
            }
        }
    }
    if (!online) { if (vm.job == null) Print(R.drawable.empty_stems); return@Page }

    Panel("Mode", null, accent = P.Audio) {
        vm.models.forEach { (k, d) ->
            Row(Modifier.fillMaxWidth().clickable { vm.model = k }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Jack(P.Audio, vm.model == k, 20.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text((MODE_LABEL[k] ?: k).uppercase(), style = T.Title.copy(fontSize = 18.sp, color = if (vm.model == k) P.Ink else P.Muted))
                    Text(d, style = T.Small)
                }
            }
        }
    }
    HardButton("Pick track or video", { pick.launch(MEDIA) }, color = P.Audio, enabled = !vm.busy)
    if (vm.jobId != null && vm.job == null) GhostButton("Resume last job", { vm.resume() }, color = P.Audio, enabled = !vm.busy)

    val j = vm.job
    if (j != null && j.status != "done" && j.status != "error") SignalPath(j.status)
    if (j == null) Print(R.drawable.empty_stems)
    if (j?.status == "done") {
        Panel(vm.jobName, "${j.stems.size} stems · 48k mono", accent = P.Audio) {
            j.stems.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Jack(stemColor(s), true, 20.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(s.replace('_', ' ').uppercase(), style = T.Title.copy(fontSize = 19.sp), modifier = Modifier.weight(1f))
                    GhostButton("Play", { vm.openStem(s) { vm.clip?.let(Audio::play) } }, enabled = !vm.busy)
                    Spacer(Modifier.width(6.dp))
                    GhostButton("Chop", { vm.openStem(s) { vm.tab = 1 } }, color = P.Cv, enabled = !vm.busy)
                }
            }
        }
        if (vm.link != null) HardButton("Send all → ${AppState.join(vm.dir, vm.jobName)}", { vm.sendAllStems() }, color = P.Signal, enabled = !vm.busy)
        else GhostButton("Patch the Rytm in to send all", { vm.tab = 0 }, color = P.Signal)
    }
}

/** PHONE → MAC → STEMS, with signal pulses running down the active cable. */
@Composable
private fun SignalPath(status: String) {
    val t = rememberInfiniteTransition(label = "sig")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "ph")
    val stage = if (status == "running") 1 else 0
    Panel("Signal path", status, accent = P.Audio) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val boxW = size.width * .24f; val boxH = 42f * 1.4f
            val ys = size.height / 2 - boxH / 2
            val xs = listOf(0f, size.width / 2 - boxW / 2, size.width - boxW)
            for (i in 0..1) {
                val a = Offset(xs[i] + boxW, size.height / 2); val b = Offset(xs[i + 1], size.height / 2)
                val col = if (i <= stage) P.Audio else P.Line
                drawLine(P.Shade, a, b, 10f); drawLine(col, a, b, 6f)
                if (i == stage) for (k in 0 until 3) {
                    val f = (phase + k / 3f) % 1f
                    drawCircle(P.Ink, 5f, Offset(a.x + (b.x - a.x) * f, a.y))
                }
            }
            xs.forEachIndexed { i, x ->
                drawRoundRect(P.Bg, Offset(x, ys), Size(boxW, boxH), CornerRadius(6f))
                drawRoundRect(if (i <= stage + 1) P.Audio else P.Line, Offset(x, ys), Size(boxW, boxH), CornerRadius(6f), style = Stroke(3f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("PHONE", "MAC", "STEMS").forEachIndexed { i, l ->
                Text(l, style = T.Label, modifier = Modifier.weight(1f), textAlign = when (i) { 0 -> androidx.compose.ui.text.style.TextAlign.Start; 1 -> androidx.compose.ui.text.style.TextAlign.Center; else -> androidx.compose.ui.text.style.TextAlign.End })
            }
        }
    }
}

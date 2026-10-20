package com.bandersong.rytmmobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bandersong.rytmmobile.ui.App
import com.bandersong.rytmmobile.ui.PatchTheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File

/** Audio files, voice-recorder files (m4a/3gp/amr) and videos such as screen recordings. */
val MEDIA = arrayOf("audio/*", "video/*", "application/ogg", "application/octet-stream")

class MainActivity : ComponentActivity() {
    // One AppState for the whole process: rotating, folding or resizing the window recreates the
    // activity, and the loaded sample and the open MIDI link must survive that.
    private val vm get() = AppState.get(applicationContext)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity gets its launch intent again; acting on USB_DEVICE_ATTACHED twice opened
        // a second MIDI link while the first still held the ports ("busy").
        if (savedInstanceState == null) handleIntent(intent)
        enableEdgeToEdge()
        setContent { PatchTheme { App(vm) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    override fun onResume() { super.onResume(); vm.checkUpdate() }

    private fun handleIntent(i: Intent?) {
        i?.getStringExtra("update_url")?.let { vm.setUpdateUrl(it) }  // debug builds only (ignored in release)
        if (i?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { vm.load(it) }
        } else if (i?.action == Intent.ACTION_VIEW && i.data?.scheme == "rytmmobile") {
            i.data?.let { vm.pair(it) }
        } else if (i?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            vm.connect()
        }
    }
}

class AppState(private val ctx: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = ctx.getSharedPreferences("rytm", Context.MODE_PRIVATE)

    var status by mutableStateOf("Not connected")
    var busy by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var link by mutableStateOf<RytmLink?>(null)
    var dir by mutableStateOf("/")
    var entries by mutableStateOf<List<Elektron.Entry>>(emptyList())
    var diag by mutableStateOf("")

    var clip by mutableStateOf<Clip?>(null)
    var clipName by mutableStateOf("")
    var start by mutableFloatStateOf(0f)
    var end by mutableFloatStateOf(1f)
    var normalize by mutableStateOf(true)
    var fades by mutableStateOf(true)
    var tab by mutableIntStateOf(0)

    // Self-update (see Updater). Checked at launch and when the app comes back, at most every 6 h.
    var update by mutableStateOf<Updater.Release?>(null)
    var updateProgress by mutableFloatStateOf(-1f)
    private var lastUpdateCheck = 0L
    private val updateUrl get() = if (BuildConfig.DEBUG) prefs.getString("update_url", null) ?: BuildConfig.UPDATE_URL else BuildConfig.UPDATE_URL

    fun checkUpdate(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUpdateCheck < 6 * 3600_000L) return
        lastUpdateCheck = now
        scope.launch(Dispatchers.IO) {
            runCatching { Updater.check(updateUrl, BuildConfig.VERSION_CODE) }
                .onSuccess { update = it }
                .onFailure { android.util.Log.i("RytmUpdate", "check failed: ${it.message}") }
        }
    }

    /** Debug builds only: point the update check somewhere else, for testing. */
    fun setUpdateUrl(u: String) { if (BuildConfig.DEBUG) { prefs.edit().putString("update_url", u).apply(); checkUpdate(true) } }

    fun installUpdate() {
        val r = update ?: return
        if (!Updater.canInstall(ctx)) {
            status = "Allow Rytm Mobile to install apps, then tap Update again."
            Updater.openInstallPermission(ctx); return
        }
        if (updateProgress >= 0f) return
        updateProgress = 0f
        scope.launch(Dispatchers.IO) {
            try {
                val f = Updater.download(ctx, r) { updateProgress = it }
                withContext(Dispatchers.Main) { Updater.install(ctx, f) }
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            } finally { updateProgress = -1f }
        }
    }
    val failed get() = status.startsWith("Error")

    var server by mutableStateOf(prefs.getString("server", "")!!)
    var token by mutableStateOf(prefs.getString("token", "")!!)
    var models by mutableStateOf<Map<String, String>>(emptyMap())
    var model by mutableStateOf("stems")
    var jobId by mutableStateOf(prefs.getString("job", null))
    var job by mutableStateOf<StemClient.Job?>(null)
    var jobName by mutableStateOf(prefs.getString("jobName", "track")!!)

    private fun task(label: String, block: suspend CoroutineScope.() -> Unit) {
        if (busy) return
        busy = true; progress = 0f; status = label
        scope.launch {
            try { withContext(Dispatchers.IO, block) } catch (e: Exception) {
                status = "Error: " + when (e) {
                    is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.UnknownHostException ->
                        "can't reach the computer. Awake? Same Wi-Fi or Tailscale?"
                    else -> e.message
                }
            } finally { busy = false }
        }
    }

    /** Appends to the on-screen log, keeping the last 400 lines. */
    private fun logLine(line: String) {
        val all = (diag + line + "\n").lines()
        diag = if (all.size > 401) all.takeLast(401).joinToString("\n") else diag + line + "\n"
    }

    private suspend fun openLink(): RytmLink {
        link?.close(); link = null
        val l = RytmLink.scan(ctx, ::logLine)
        link = l
        return l
    }

    fun connect() = task("Looking for the Rytm…") {
        diag = ""
        val l = openLink()
        status = "Connected: ${l.name} ${l.version}"
        entries = l.list(dir)
    }

    /**
     * Runs a Rytm action. If the Rytm doesn't answer and no longer answers a ping either, the USB
     * link went stale (sleep, a cable wiggle, a fold): reconnect once and run the action again.
     */
    private suspend fun <T> withRytm(what: String, block: (RytmLink) -> T): T {
        val l = link ?: openLink()
        return try {
            block(l)
        } catch (e: java.io.IOException) {
            if (l.alive()) throw e
            logLine("Link went stale during $what; reconnecting")
            status = "Reconnecting to the Rytm…"
            block(openLink())
        }
    }

    init {
        // Unplugged: drop the link at once instead of keeping a dead one. Plugged back in: reconnect.
        val mm = ctx.getSystemService(Context.MIDI_SERVICE) as android.media.midi.MidiManager
        @Suppress("DEPRECATION")
        mm.registerDeviceCallback(object : android.media.midi.MidiManager.DeviceCallback() {
            override fun onDeviceRemoved(info: android.media.midi.MidiDeviceInfo) {
                val l = link ?: return
                if (l.info.id == info.id) {
                    runCatching { l.close() }; link = null
                    logLine("Rytm unplugged")
                    if (!busy) status = "Rytm unplugged"
                }
            }
            override fun onDeviceAdded(info: android.media.midi.MidiDeviceInfo) {
                if (link == null && !busy && RytmLink.elektronDevices(ctx).any { it.id == info.id }) connect()
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    fun open(path: String) = task("Reading $path") {
        entries = withRytm("reading $path") { it.list(path) }; dir = path; status = "${link?.name}: $path"
    }

    fun mkdir(name: String) = task("Creating folder") {
        withRytm("creating a folder") { it.mkdir(join(dir, clean(name))); entries = it.list(dir) }
        status = "Folder created"
    }

    fun delete(e: Elektron.Entry) = task("Deleting ${e.name}") {
        withRytm("deleting ${e.name}") { it.delete(join(dir, e.name)); entries = it.list(dir) }
        status = "Deleted ${e.name}"
    }

    fun load(uri: Uri) = task("Decoding…") {
        val c = Audio.decode(ctx, uri)
        status = "Resampling ${c.rate} Hz → 48 kHz…"
        val r = Audio.resample(c)
        clip = r; clipName = clean(StemClient.displayName(ctx, uri).substringBeforeLast('.'))
        start = 0f; end = 1f; tab = 1
        status = "Loaded ${"%.1f".format(r.seconds)} s"
    }

    private fun loadFile(f: File, name: String) {
        val c = Audio.resample(Audio.decode(f))
        clip = c; clipName = clean(name); start = 0f; end = 1f
    }

    fun prepared(): Clip? = clip?.let { Audio.prepare(it, start * it.seconds, end * it.seconds, normalize, fades) }

    fun send() = task("Sending to Rytm…") {
        if (link == null) throw Exception("Connect the Rytm first")
        val c = prepared() ?: throw Exception("No sample loaded")
        val pcm = Audio.toPcm16(c)
        val path = join(dir, clipName.ifBlank { "sample" })
        val t0 = System.currentTimeMillis()
        logLine("Send $path: ${pcm.size} frames")
        withRytm("sending $path") { it.upload(path, pcm) { progress = it }; entries = it.list(dir) }
        status = "Sent $path (${"%.1f".format(c.seconds)} s in ${(System.currentTimeMillis() - t0) / 1000} s)"
    }

    private fun client(): StemClient = StemClient(serverUrl(server) ?: throw Exception(BAD_SERVER), token)

    /** rytmmobile://pair?url=http%3A%2F%2F100.x.y.z%3A8765&token=… from the installer's QR code. */
    fun pair(uri: Uri) {
        tab = 2
        val u = uri.getQueryParameter("url")?.trim(); val t = uri.getQueryParameter("token")?.trim()
        if (u.isNullOrBlank() || t.isNullOrBlank()) {
            status = "Error: incomplete pairing link. Run ~/.rytm-stems/pair, scan again."; return
        }
        if (serverUrl(u) == null) { status = "Error: bad server address in pairing link: $u"; return }
        if (busy) { status = "Error: busy. Open the pairing link again after this."; return }
        server = u; token = t
        fetchModels()
    }

    /** Checks the server and token; they are saved only once the server has answered with them. */
    fun fetchModels() = task("Contacting stem server…") {
        if (server.isBlank()) throw Exception("Pair with your computer first: run the installer there and scan its code.")
        val url = serverUrl(server) ?: throw Exception(BAD_SERVER)
        val tok = token
        models = emptyMap()
        val m = StemClient(url, tok).models()
        server = url; models = m
        prefs.edit().putString("server", url).putString("token", tok).apply()
        status = "Stem server OK"
    }

    fun separate(uri: Uri) = task("Uploading track…") {
        val c = client()
        jobName = clean(StemClient.displayName(ctx, uri).substringBeforeLast('.')).take(12)
        val id = c.submit(ctx, uri, model) { progress = it }
        jobId = id; prefs.edit().putString("job", id).putString("jobName", jobName).apply()
        poll(c, id)
    }

    fun resume() = task("Checking job…") {
        try { poll(client(), jobId ?: throw Exception("no job")) }
        catch (e: java.io.IOException) {
            if (e.message?.startsWith("That job") == true) { jobId = null; prefs.edit().remove("job").apply() }
            throw e
        }
    }

    private suspend fun poll(c: StemClient, id: String) {
        while (true) {
            val j = c.job(id); job = j
            status = when (j.status) { "queued" -> "Queued (${j.queue} ahead)"; "running" -> "Separating on the Mac…"; else -> j.status }
            if (j.status == "done" || j.status == "error") { if (j.error != null) status = "Error: ${j.error}"; break }
            delay(2000)
        }
        if (job?.status == "done") status = "Stems ready: ${job!!.stems.joinToString()}"
    }

    fun openStem(stem: String, then: () -> Unit = {}) = task("Downloading $stem…") {
        val f = File(ctx.cacheDir, "$stem.wav")
        client().download(jobId!!, stem, f)
        loadFile(f, "${jobName}_$stem")
        status = "Loaded stem $stem"
        withContext(Dispatchers.Main) { then() }
    }

    fun sendAllStems() = task("Sending all stems…") {
        if (link == null) throw Exception("Connect the Rytm first")
        val c = client(); val stems = job?.stems ?: emptyList()
        val folder = join(dir, jobName)
        runCatching { withRytm("creating $folder") { it.mkdir(folder) } }
        stems.forEachIndexed { i, s ->
            status = "Stem ${i + 1}/${stems.size}: $s"
            val f = c.download(jobId!!, s, File(ctx.cacheDir, "$s.wav"))
            val clip = Audio.prepare(Audio.resample(Audio.decode(f)).let { it }, 0.0, 1e9, true, true)
            val pcm = Audio.toPcm16(clip)
            withRytm("sending $s") { it.upload(join(folder, clean(s)), pcm) { p -> progress = (i + p) / stems.size } }
        }
        dir = folder; entries = withRytm("reading $folder") { it.list(folder) }
        status = "All stems in $folder"
    }

    companion object {
        @Volatile private var instance: AppState? = null
        fun get(ctx: Context): AppState = instance ?: synchronized(this) {
            instance ?: AppState(ctx.applicationContext).also { instance = it }
        }

        const val BAD_SERVER = "That server address isn't valid. It looks like http://100.x.y.z:8765"

        /** "10.0.2.2:8765" → "http://10.0.2.2:8765"; null unless it is http(s) with a host. */
        fun serverUrl(s: String): String? {
            val t = s.trim()
            if (t.isEmpty()) return null
            val full = if ("://" in t) t else "http://$t"
            val u = runCatching { java.net.URI(full) }.getOrNull() ?: return null
            if (u.scheme?.lowercase() !in setOf("http", "https") || u.host.isNullOrBlank()) return null
            return full.trimEnd('/')
        }

        fun join(d: String, n: String) = if (d.endsWith("/")) d + n else "$d/$n"
        /** Rytm names: plain ASCII, 24 chars max. */
        fun clean(s: String) = s.replace(Regex("[^A-Za-z0-9 _\\-]"), "_").trim().take(24)
    }
}


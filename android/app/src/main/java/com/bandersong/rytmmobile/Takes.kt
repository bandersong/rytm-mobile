package com.bandersong.rytmmobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Take(
    val id: String, val file: File, val created: Long, val rate: Int, val channels: Int, val frames: Long,
    val preRollFrames: Int, val inputName: String, val source: String, val peakDb: List<Float>, val clips: Int,
    val dropouts: Int, val stop: StopReason, val savedUri: String?, val peaks: List<Float>,
) {
    val seconds: Double get() = frames / rate.toDouble()
    val bytes: Long get() = 44 + frames * channels * 2
}

data class TakeMeta(
    val rate: Int, val channels: Int, val preRollFrames: Int, val inputName: String, val source: String,
    val clips: Int, val dropouts: Int, val stop: StopReason,
)

/** Takes live in one private folder as <id>.wav + <id>.json. Used only on the engine's control/writer threads. */
class TakeStore(val dir: File) {
    private val idFormat = DateTimeFormatter.ofPattern("MMdd-HHmmss")

    /** "R" + MMdd-HHmmss (12 chars); bumps by a second until no <id>.wav / .wav.part / .json exists. */
    fun newId(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        var t = nowMs
        while (true) {
            val id = "R" + idFormat.format(Instant.ofEpochMilli(t).atZone(zone))
            if (!File(dir, "$id.wav").exists() && !partFile(id).exists() && !File(dir, "$id.json").exists()) return id
            t += 1000
        }
    }

    fun partFile(id: String) = File(dir, "$id.wav.part")
    private fun wavFile(id: String) = File(dir, "$id.wav")
    private fun jsonFile(id: String) = File(dir, "$id.json")

    /** Repairs the part file's header, renames it to <id>.wav, scans it and writes the sidecar. */
    fun finalize(id: String, meta: TakeMeta): Take {
        val part = partFile(id)
        val info = WavIo.repair(part)
        val wav = wavFile(id)
        if (wav.exists()) throw IOException("$id.wav already exists")
        moveNoReplace(part, wav)
        val created = wav.lastModified()
        return writeSidecar(build(id, wav, created, info, meta, null))
    }

    /** Newest first; a take is a sidecar whose .wav exists. */
    fun list(): List<Take> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { j -> runCatching { readSidecar(j) }.getOrNull() }
            .filter { it.file.exists() }
            .sortedByDescending { it.created }
    }

    /**
     * Crash recovery: every *.wav.part, and every *.wav without a readable sidecar, is repaired (and renamed),
     * and gets a sidecar with stop = RECOVERED. A file whose header isn't ours is renamed to <name>.bad. Never deletes.
     */
    fun recover(): List<Take> {
        val out = ArrayList<Take>()
        val all = dir.listFiles()?.sortedBy { it.name } ?: return out
        for (f in all.filter { it.isFile && it.name.endsWith(".wav.part") }) {
            val id = f.name.removeSuffix(".wav.part")
            val created = f.lastModified()
            val info = runCatching { WavIo.repair(f) }.getOrNull()
            val wav = wavFile(id)
            if (info == null || wav.exists()) { toBad(f); continue }
            moveNoReplace(f, wav)
            out.add(recovered(id, wav, created, info))
        }
        for (w in all.filter { it.isFile && it.name.endsWith(".wav") }) {
            val id = w.name.removeSuffix(".wav")
            val hasSidecar = jsonFile(id).let { it.exists() && runCatching { readSidecar(it) }.isSuccess }
            if (hasSidecar) continue
            val created = w.lastModified()
            val info = runCatching { WavIo.repair(w) }.getOrNull()
            if (info == null) { toBad(w); continue }
            out.add(recovered(id, w, created, info))
        }
        return out
    }

    fun markSaved(t: Take, uri: String): Take = writeSidecar(t.copy(savedUri = uri))

    /** User-initiated: removes the .wav and the sidecar. */
    fun delete(t: Take) {
        t.file.delete()
        jsonFile(t.id).delete()
    }

    fun totalBytes(): Long = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.sumOf { it.length() } ?: 0L

    // --- internals -------------------------------------------------------------------------------------------

    private fun recovered(id: String, wav: File, created: Long, info: WavIo.Info): Take {
        val meta = TakeMeta(info.rate, info.channels, 0, "Unknown input", "UNKNOWN", 0, 0, StopReason.RECOVERED)
        return writeSidecar(build(id, wav, created, info, meta, null))
    }

    private fun build(id: String, wav: File, created: Long, info: WavIo.Info, meta: TakeMeta, savedUri: String?): Take {
        val scan = WavIo.scan(wav)
        return Take(
            id, wav, created, info.rate, info.channels, info.frames, meta.preRollFrames, meta.inputName, meta.source,
            scan.peakDb.map { r2(it) }, meta.clips, meta.dropouts, meta.stop, savedUri, scan.peaks.map { r3(it) },
        )
    }

    private fun r3(v: Float): Float = (Math.round(v * 1000.0) / 1000.0).toFloat()
    private fun r2(v: Float): Float = (Math.round(v * 100.0) / 100.0).toFloat()

    private fun writeSidecar(t: Take): Take {
        val j = JSONObject()
        j.put("id", t.id); j.put("created", t.created); j.put("rate", t.rate); j.put("channels", t.channels)
        j.put("frames", t.frames); j.put("preRollFrames", t.preRollFrames); j.put("inputName", t.inputName)
        j.put("source", t.source)
        j.put("peakDb", JSONArray().also { a -> t.peakDb.forEach { a.put(Math.round(it * 100.0) / 100.0) } })
        j.put("clips", t.clips); j.put("dropouts", t.dropouts); j.put("stop", t.stop.name)
        if (t.savedUri != null) j.put("savedUri", t.savedUri)
        j.put("peaks", JSONArray().also { a -> t.peaks.forEach { a.put(Math.round(it * 1000.0) / 1000.0) } })
        val tmp = File(dir, "${t.id}.json.tmp")
        tmp.writeText(j.toString(), Charsets.UTF_8)
        Files.move(tmp.toPath(), jsonFile(t.id).toPath(), StandardCopyOption.REPLACE_EXISTING)
        return t
    }

    private fun readSidecar(j: File): Take {
        val o = JSONObject(j.readText(Charsets.UTF_8))
        val id = o.getString("id")
        val dbs = o.getJSONArray("peakDb"); val pk = o.getJSONArray("peaks")
        return Take(
            id, wavFile(id), o.getLong("created"), o.getInt("rate"), o.getInt("channels"), o.getLong("frames"),
            o.getInt("preRollFrames"), o.getString("inputName"), o.getString("source"),
            List(dbs.length()) { dbs.getDouble(it).toFloat() }, o.getInt("clips"), o.getInt("dropouts"),
            StopReason.valueOf(o.getString("stop")), if (o.has("savedUri")) o.getString("savedUri") else null,
            List(pk.length()) { pk.getDouble(it).toFloat() },
        )
    }

    private fun moveNoReplace(from: File, to: File) {
        Files.move(from.toPath(), to.toPath())     // throws FileAlreadyExistsException rather than overwrite
    }

    private fun toBad(f: File) {
        var target = File(dir, f.name + ".bad")
        var n = 1
        while (target.exists()) target = File(dir, f.name + ".bad" + n++)
        Files.move(f.toPath(), target.toPath())
    }
}

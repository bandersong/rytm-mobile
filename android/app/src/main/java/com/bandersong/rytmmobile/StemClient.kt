package com.bandersong.rytmmobile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The two ways a stem can come off the Mac. */
enum class StemFormat(val query: String, val ext: String, val mime: String, val label: String) {
    /** Stereo FLAC at the separator's own rate (44.1 kHz), straight from the model output. */
    FLAC("flac", "flac", "audio/flac", "Full quality"),
    /** 48 kHz 16-bit mono WAV, what the Rytm plays. */
    WAV("wav", "wav", "audio/wav", "Rytm-ready"),
}

/**
 * The server has no full-quality copy. wholeJob: none of this job's stems have one (split before
 * FLAC saving existed, or an older server); otherwise just this stem's FLAC couldn't be made.
 */
class NoFullQualityException(msg: String, val wholeJob: Boolean = true) : IOException(msg)

/** Talks to server/stemserver.py (on the Mac, reached over Tailscale or LAN). */
class StemClient(private val base: String, private val token: String) {
    private fun conn(path: String): HttpURLConnection =
        (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            setRequestProperty("X-Token", token); connectTimeout = 8000; readTimeout = 120_000
        }

    private fun detail(c: HttpURLConnection): String =
        runCatching { JSONObject(c.errorStream.bufferedReader().readText()).optString("detail") }.getOrNull() ?: ""

    private fun json(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        if (code !in 200..299) throw IOException(when (code) {
            401 -> "Wrong token. Run ~/.rytm-stems/pair, scan again."
            404 -> "That job isn't on the Mac any more. Split the track again."
            // 507: the computer's disk is nearly full; its detail says how much is free.
            507 -> detail(c).ifBlank { "The computer is almost out of disk space." }
            else -> "Mac server error $code: " + detail(c)
        })
        return JSONObject(c.inputStream.bufferedReader().readText())
    }

    fun models(): Map<String, String> {
        val j = json(conn("/models"))
        return j.keys().asSequence().associateWith { j.getString(it) }
    }

    fun submit(ctx: Context, uri: Uri, model: String, progress: (Float) -> Unit): String {
        val name = displayName(ctx, uri)
        val total = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val boundary = "----rytm" + System.nanoTime()
        val c = conn("/jobs")
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setChunkedStreamingMode(64 * 1024)
        c.outputStream.use { o ->
            o.write("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n".toByteArray())
            o.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${name.replace("\"", "")}\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            ctx.contentResolver.openInputStream(uri)!!.use { i ->
                val b = ByteArray(64 * 1024); var sent = 0L
                while (true) { val n = i.read(b); if (n < 0) break; o.write(b, 0, n); sent += n; if (total > 0) progress(sent.toFloat() / total) }
            }
            o.write("\r\n--$boundary--\r\n".toByteArray())
        }
        return json(c).getString("id")
    }

    data class Job(val status: String, val stems: List<String>, val error: String?, val queue: Int)

    fun job(id: String): Job {
        val j = json(conn("/jobs/$id"))
        val s = j.getJSONObject("stems")
        return Job(j.getString("status"), s.keys().asSequence().toList(), j.optString("error").ifEmpty { null }, j.optInt("queue"))
    }

    /** One past split, as GET /jobs lists it. */
    data class JobInfo(val id: String, val name: String, val model: String, val status: String, val stems: List<String>, val created: Long)

    /** Every job on the server, newest first. */
    fun jobs(): List<JobInfo> {
        val c = conn("/jobs")
        // An older server only has POST /jobs.
        if (c.responseCode == 404 || c.responseCode == 405)
            throw IOException("Update the stem server on your computer to see past splits.")
        return parseJobs(json(c).toString())
    }

    /**
     * Removes a split from the computer (its stems, FLAC copies and upload). Copies already saved on the
     * phone are untouched. A job the computer no longer has counts as deleted.
     */
    fun delete(id: String) {
        val c = conn("/jobs/" + URLEncoder.encode(id, "UTF-8"))
        c.requestMethod = "DELETE"
        when (val code = c.responseCode) {
            in 200..299, 404 -> Unit
            401 -> throw IOException("Wrong token. Run ~/.rytm-stems/pair, scan again.")
            405 -> throw IOException("Update the stem server on your computer to delete splits.")
            409 -> throw IOException(detail(c).ifBlank { "That split is still running. Delete it when it has finished." })
            else -> throw IOException("Mac server error $code: " + detail(c))
        }
    }

    /** A stem as Rytm-ready WAV (the default) or full-quality FLAC. */
    fun download(id: String, stem: String, dst: File, format: StemFormat = StemFormat.WAV, progress: (Float) -> Unit = {}): File {
        val q = if (format == StemFormat.WAV) "" else "?format=${format.query}"
        return fetch(conn("/jobs/$id/stems/" + URLEncoder.encode(stem, "UTF-8").replace("+", "%20") + q), dst, format, "stem $stem", progress)
    }

    /** The zip, and the stems the computer left out of it (X-Stems-Missing), if any. */
    class ZipResult(val file: File, val missing: List<String>)

    /** All stems of a job in one zip, named "<track>_<stem>.<ext>". */
    fun zip(id: String, format: StemFormat, dst: File, progress: (Float) -> Unit = {}): ZipResult {
        val c = conn("/jobs/$id/zip?format=${format.query}")
        val f = fetch(c, dst, format, "zip", progress, zip = true)
        return ZipResult(f, parseMissing(c.getHeaderField("X-Stems-Missing")))
    }

    private fun fetch(c: HttpURLConnection, dst: File, format: StemFormat, what: String, progress: (Float) -> Unit, zip: Boolean = false): File {
        val code = c.responseCode
        if (code != 200) {
            val d = detail(c)
            if (code == 404 && format == StemFormat.FLAC && d.contains("full-quality"))
                throw NoFullQualityException("This job was split before full-quality saving existed, so only the Rytm-ready WAV is on the computer.")
            // A job made after the change whose FLAC for this one stem couldn't be made.
            if (code == 404 && format == StemFormat.FLAC && !zip && (d.isEmpty() || d == "Not Found"))
                throw NoFullQualityException("The computer has no full-quality copy of $what.", wholeJob = false)
            throw IOException(when (code) {
                401 -> "Wrong token. Run ~/.rytm-stems/pair, scan again."
                // FastAPI's own 404 for a route it doesn't have says just "Not Found".
                404 -> if (zip && (d.isEmpty() || d == "Not Found")) "The stem server on your computer is too old to make zips. Update it."
                       else "That job isn't on the computer any more. Split the track again."
                else -> "Download of $what failed ($code) $d".trim()
            })
        }
        val total = c.contentLengthLong
        dst.parentFile?.mkdirs()
        c.inputStream.use { i -> dst.outputStream().use { o ->
            val b = ByteArray(64 * 1024); var got = 0L
            while (true) { val n = i.read(b); if (n < 0) break; o.write(b, 0, n); got += n; if (total > 0) progress(got.toFloat() / total) }
        } }
        val head = ByteArray(4)
        val n = dst.inputStream().use { it.read(head) }
        val ok = n == 4 && looksLike(head, if (zip) "zip" else format.ext)
        // An older server ignores ?format=flac and answers with the WAV: never save that under a .flac name.
        if (!ok && format == StemFormat.FLAC && !zip)
            throw NoFullQualityException("The stem server on your computer is too old for full-quality saves. Update it, or save Rytm-ready.")
        if (!ok) throw IOException("The computer sent something that isn't a ${if (zip) "zip" else format.ext.uppercase()} file.")
        return dst
    }

    companion object {
        fun displayName(ctx: Context, uri: Uri): String =
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: "audio"

        /** GET /jobs body → jobs, newest first (sorted here too, in case the server didn't). */
        fun parseJobs(text: String): List<JobInfo> {
            val a = JSONObject(text).optJSONArray("jobs") ?: return emptyList()
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val st = o.optJSONArray("stems")
                JobInfo(
                    o.getString("id"), o.optString("name").ifBlank { "track" }, o.optString("model"),
                    o.optString("status"), if (st == null) emptyList() else (0 until st.length()).map { st.getString(it) },
                    o.optLong("created"),
                )
            }.sortedByDescending { it.created }
        }

        /** First bytes of a file of that kind: fLaC, RIFF....WAVE (we check RIFF), PK\x03\x04. */
        fun looksLike(head: ByteArray, kind: String): Boolean {
            if (head.size < 4) return false
            val s = String(head, 0, 4, Charsets.ISO_8859_1)
            return when (kind) {
                "flac" -> s == "fLaC"
                "wav" -> s == "RIFF"
                "zip" -> s == "PK\u0003\u0004"
                else -> false
            }
        }

        /** "bombs", "vocals", FLAC → "bombs_vocals.flac" (the same names the server puts in its zip). */
        fun stemFileName(track: String, stem: String, format: StemFormat) =
            "${fileSafe(track)}_${fileSafe(stem)}.${format.ext}"

        fun zipFileName(track: String, format: StemFormat) =
            "${fileSafe(track)}_stems_${if (format == StemFormat.FLAC) "full" else "rytm"}.zip"

        /** How a split mode is named in a folder: "Full band", "Fast", "Vocals", "Drum kit". */
        fun modeLabel(model: String?): String? = when (model) {
            "stems" -> "Full band"; "demucs" -> "Fast"; "vocals" -> "Vocals"; "drumkit" -> "Drum kit"
            else -> null
        }

        /**
         * The phone folder (and zip name stem) for one split: "<track> (<mode>)". Two splits of the same
         * song in different modes don't overwrite each other; saving the SAME split again still replaces.
         * A split whose mode isn't known (opened before the app recorded it) uses the job id's first 6 characters.
         */
        fun saveFolder(title: String, model: String?, jobId: String?): String {
            val tag = modeLabel(model) ?: jobId?.take(6)?.takeIf { it.isNotBlank() }
            return if (tag == null) fileSafe(title) else "${fileSafe(title)} ($tag)"
        }

        /** X-Stems-Missing: "vocals,drums_full" → names; null or blank → none. */
        fun parseMissing(h: String?): List<String> = h?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        /** "Saved 6 stems to <folder> (full-quality FLAC)", or says which ones came out as WAV. */
        fun saveAllSummary(total: Int, folder: String, fmt: StemFormat, wavStems: List<String>): String = when {
            wavStems.isEmpty() ->
                "Saved $total stems to ${Saver.MUSIC_DIR}/$folder (${if (fmt == StemFormat.FLAC) "full-quality FLAC" else "48k WAV"})"
            wavStems.size == total ->
                "Saved $total stems to ${Saver.MUSIC_DIR}/$folder as 48k WAV (the computer has no full-quality copy)"
            else ->
                "Saved $total stems to ${Saver.MUSIC_DIR}/$folder. ${total - wavStems.size} FLAC; no full-quality copy of " +
                    "${wavStems.joinToString(", ")}, so ${if (wavStems.size == 1) "that one is a" else "those are"} 48k WAV."
        }

        /** "Saved X to Download/Rytm Mobile." plus how many stems are in the zip when some were left out. */
        fun zipNote(total: Int, missing: List<String>): String =
            if (missing.isEmpty() || total <= 0) ""
            else " ${(total - missing.size).coerceAtLeast(0)} of $total stems in the zip; no full-quality copy of ${missing.joinToString(", ")}."

        /** Phone file and folder names: keep spaces and accents, drop what file systems refuse. */
        fun fileSafe(s: String): String =
            s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(80).ifBlank { "track" }

        /** created (unix seconds) → "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago", or "12 Oct". */
        fun ago(created: Long, nowS: Long = System.currentTimeMillis() / 1000): String {
            if (created <= 0) return ""
            val d = nowS - created
            return when {
                d < 60 -> "just now"
                d < 3600 -> "${d / 60} min ago"
                d < 86400 -> "${d / 3600} h ago"
                d < 2 * 86400 -> "yesterday"
                d < 7 * 86400 -> "${d / 86400} days ago"
                else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH).format(java.util.Date(created * 1000))
            }
        }
    }
}

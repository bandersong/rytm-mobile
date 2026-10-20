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

/** Talks to server/stemserver.py (on the Mac, reached over Tailscale or LAN). */
class StemClient(private val base: String, private val token: String) {
    private fun conn(path: String): HttpURLConnection =
        (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            setRequestProperty("X-Token", token); connectTimeout = 8000; readTimeout = 120_000
        }

    private fun json(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        if (code !in 200..299) throw IOException(when (code) {
            401 -> "Wrong token. Run ~/.rytm-stems/pair, scan again."
            404 -> "That job isn't on the Mac any more. Split the track again."
            else -> "Mac server error $code: " + (runCatching { JSONObject(c.errorStream.bufferedReader().readText()).optString("detail") }.getOrNull() ?: "")
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

    fun download(id: String, stem: String, dst: File): File {
        val c = conn("/jobs/$id/stems/" + URLEncoder.encode(stem, "UTF-8"))
        if (c.responseCode != 200) throw IOException("download ${c.responseCode}")
        c.inputStream.use { i -> dst.outputStream().use { i.copyTo(it) } }
        return dst
    }

    companion object {
        fun displayName(ctx: Context, uri: Uri): String =
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: "audio"
    }
}

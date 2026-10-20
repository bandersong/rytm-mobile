package com.bandersong.rytmmobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-update from the download site. version.json there names the newest APK and its SHA-256;
 * the app downloads it, checks the hash, and hands it to Android's installer. Android itself then
 * checks that the new APK is signed with the same key, so only our own builds can update the app.
 */
object Updater {
    data class Release(val code: Int, val name: String, val apk: String, val sha256: String, val notes: String)

    fun parse(json: String, base: String): Release {
        val j = JSONObject(json)
        val apk = j.getString("apk")
        return Release(
            j.getInt("versionCode"), j.getString("versionName"),
            if (apk.startsWith("http")) apk else base.substringBeforeLast('/') + "/" + apk,
            j.getString("sha256").lowercase(), j.optString("notes"),
        )
    }

    /** The newer release, or null if this build is current. */
    fun check(url: String, current: Int): Release? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 15000; useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
        }
        if (c.responseCode != 200) throw IOException("update check: HTTP ${c.responseCode}")
        val r = parse(c.inputStream.bufferedReader().readText(), url)
        return r.takeIf { it.code > current }
    }

    fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "RytmMobile-${r.name}.apk")
        val c = (URL(r.apk).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 30000 }
        if (c.responseCode != 200) throw IOException("update download: HTTP ${c.responseCode}")
        val total = c.contentLengthLong
        val md = MessageDigest.getInstance("SHA-256")
        c.inputStream.use { i ->
            out.outputStream().use { o ->
                val b = ByteArray(64 * 1024); var n: Int; var got = 0L
                while (i.read(b).also { n = it } >= 0) {
                    o.write(b, 0, n); md.update(b, 0, n); got += n
                    if (total > 0) progress(got.toFloat() / total)
                }
            }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (hex != r.sha256) {
            out.delete()
            throw IOException("The update didn't match its checksum, so it wasn't installed. Try again later.")
        }
        return out
    }

    /** Android requires the user to allow "install unknown apps" for this app once. */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

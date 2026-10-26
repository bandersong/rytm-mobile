package com.bandersong.rytmmobile

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Puts files where the phone's music and file apps see them, with no storage permission:
 * MediaStore (API 29+) owns the write, the app only ever touches the items it created.
 * Audio goes to Music/Rytm Mobile/…, zips to Download/Rytm Mobile/.
 */
object Saver {
    const val MUSIC_DIR = "Music/Rytm Mobile"
    const val DOWNLOAD_DIR = "Download/Rytm Mobile"

    private val audio get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val downloads get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /**
     * Saves src as Music/Rytm Mobile/<sub>/<name>; returns the content Uri. replace: write over a file
     * of that name this app saved there before (a stem saved twice is the same file), else the phone
     * picks "name (1).ext".
     */
    fun saveAudio(ctx: Context, src: File, sub: String, name: String, mime: String, replace: Boolean = false): Uri =
        src.inputStream().use { insert(ctx, audio, musicPath(sub), name, mime, it, replace) }

    fun saveAudio(ctx: Context, bytes: ByteArray, sub: String, name: String, mime: String, replace: Boolean = false): Uri =
        bytes.inputStream().use { insert(ctx, audio, musicPath(sub), name, mime, it, replace) }

    /** Saves src as Download/Rytm Mobile/<name>; returns the content Uri. */
    fun saveDownload(ctx: Context, src: File, name: String, mime: String, replace: Boolean = false): Uri =
        src.inputStream().use { insert(ctx, downloads, "$DOWNLOAD_DIR/", name, mime, it, replace) }

    /** A file this app saved earlier at relPath/name (without a storage permission, a query sees only our own). */
    private fun ours(ctx: Context, collection: Uri, relPath: String, name: String): Uri? =
        ctx.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relPath, name), null)?.use {
            if (it.moveToFirst()) android.content.ContentUris.withAppendedId(collection, it.getLong(0)) else null
        }

    fun musicPath(sub: String) = if (sub.isBlank()) "$MUSIC_DIR/" else "$MUSIC_DIR/$sub/"

    private fun insert(ctx: Context, collection: Uri, relPath: String, name: String, mime: String, data: InputStream, replace: Boolean): Uri {
        val r = ctx.contentResolver
        if (replace) ours(ctx, collection, relPath, name)?.let { uri ->
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }, null, null)
            var opened = false
            try {
                val out = r.openOutputStream(uri, "wt") ?: throw IOException("The phone wouldn't open $name for writing")
                opened = true  // "wt" has emptied the file: from here on a failure leaves it half-written
                out.use { data.copyTo(it, 64 * 1024) }
                r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                // Never leave the item pending (hidden from every app). If we had already emptied it, the
                // old copy is gone anyway, so take the broken one away; otherwise just un-hide the old one.
                if (opened) runCatching { r.delete(uri, null, null) }
                else runCatching { r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
                throw e
            }
            return uri
        }
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(collection, v) ?: throw IOException("The phone wouldn't make $name in $relPath")
        try {
            (r.openOutputStream(uri, "w") ?: throw IOException("The phone wouldn't open $name for writing")).use { data.copyTo(it, 64 * 1024) }
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            // Our own half-written item: take it away again so no broken file is left behind.
            runCatching { r.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    /** A cache copy other apps can read through our FileProvider (cache/share/). */
    fun shareCopy(ctx: Context, name: String): File {
        val d = File(ctx.cacheDir, "share").apply { mkdirs() }
        // Our own earlier share copies: an hour is plenty for the other app to have read them.
        val old = System.currentTimeMillis() - 3600_000L
        d.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
        return File(d, name)
    }

    /** Opens the share sheet for a cache file in cache/share/. */
    fun share(ctx: Context, f: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, f.name)
            clipData = android.content.ClipData.newRawUri(f.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Rytm Mobile itself takes audio shares; sending a file to ourselves isn't sharing.
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(ctx, MainActivity::class.java)))
        ctx.startActivity(chooser)
    }
}

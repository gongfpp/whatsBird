package com.whatsbird.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException

sealed interface SaveResult {
    data class Saved(val uri: Uri) : SaveResult
    data object OutOfSpace : SaveResult
    data class Failed(val message: String) : SaveResult
}

/**
 * Writes photos into the system gallery through MediaStore.
 *
 * No storage permission is required: on Android 10+ an app may always insert media it owns, which
 * is why the plan sets minSdk 29.
 *
 * Each step is timed. A gallery write is the slowest stage of a shutter press on the reference
 * device, and "MediaStore is slow" is not actionable — knowing whether the cost is the row insert,
 * the byte write, or the pending-flag hand-off is.
 */
class MediaStoreSaver(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun saveJpeg(bytes: ByteArray, displayName: String): SaveResult {
        val started = SystemClock.elapsedRealtime()
        val uri = insert(displayName) ?: return SaveResult.Failed("MediaStore insert rejected")
        val inserted = SystemClock.elapsedRealtime()

        var written = inserted
        try {
            resolver.openOutputStream(uri)?.use { raw ->
                // MediaStore hands back a stream with no buffering of its own on some devices; a
                // 1 MB frame arriving in 4 KB pieces is how a leisurely write turns into a slow one.
                BufferedOutputStream(raw, WRITE_BUFFER_BYTES).use { it.write(bytes) }
            } ?: return cleanup(uri, "cannot open output stream")
            written = SystemClock.elapsedRealtime()
            finish(uri)
        } catch (e: IOException) {
            return cleanup(uri, e.message ?: "write failed")
        } catch (e: RuntimeException) {
            return cleanup(uri, e.message ?: "write failed")
        }
        val finished = SystemClock.elapsedRealtime()

        Log.i(
            TAG,
            "saved $displayName ${bytes.size / 1024}KB insert=${inserted - started}ms " +
                "write=${written - inserted}ms finish=${finished - written}ms",
        )
        return SaveResult.Saved(uri)
    }

    fun saveBitmap(bitmap: Bitmap, displayName: String, quality: Int = JPEG_QUALITY): SaveResult {
        val uri = insert(displayName) ?: return SaveResult.Failed("MediaStore insert rejected")
        return try {
            resolver.openOutputStream(uri)?.use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) {
                    return cleanup(uri, "JPEG encode failed")
                }
            } ?: return cleanup(uri, "cannot open output stream")
            finish(uri)
            SaveResult.Saved(uri)
        } catch (e: IOException) {
            cleanup(uri, e.message ?: "write failed")
        } catch (e: RuntimeException) {
            cleanup(uri, e.message ?: "write failed")
        }
    }

    private fun insert(displayName: String): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }.onFailure { Log.w(TAG, "MediaStore insert failed", it) }.getOrNull()

    private fun finish(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        // If the pending-flag hand-off fails the row stays invisible to the gallery, so a silent
        // swallow would report "saved" for a photo nobody can open. Surface it as a write failure.
        val updated = runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }.onFailure { Log.w(TAG, "MediaStore publish failed", it) }
            .getOrDefault(0)
        if (updated <= 0) {
            throw IOException("MediaStore failed to publish $uri (updated=$updated rows)")
        }
    }

    private fun cleanup(uri: Uri, message: String): SaveResult {
        runCatching { resolver.delete(uri, null, null) }
        val outOfSpace = message.contains("ENOSPC", ignoreCase = true) ||
            message.contains("No space left", ignoreCase = true)
        return if (outOfSpace) SaveResult.OutOfSpace else SaveResult.Failed(message)
    }

    companion object {
        private const val TAG = "MediaStoreSaver"
        const val ALBUM = "识鸟"

        /**
         * 88 rather than 95 for the annotated copy. The overlay is drawn on top of a photo that is
         * already a JPEG, so re-encoding at 95 spends real time on detail the source never had.
         */
        const val JPEG_QUALITY = 88

        private const val WRITE_BUFFER_BYTES = 128 * 1024
    }
}

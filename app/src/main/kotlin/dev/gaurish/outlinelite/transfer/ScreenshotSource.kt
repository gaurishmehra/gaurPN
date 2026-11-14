package dev.gaurish.outlinelite.transfer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Finds the newest screenshot in shared storage.
 *
 * Used by screenshot requests: the requester gets the latest capture without
 * the user having to pick anything. Every failure (no permission, provider
 * error, empty library) collapses to null so the caller can decline with
 * "unsupported" instead of guessing.
 */
internal object ScreenshotSource {

    fun latestScreenshot(context: Context): Uri? = runCatching {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val query = buildScreenshotQuery(Build.VERSION.SDK_INT)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver
            .query(collection, projection, query.selection, query.selectionArgs, sortOrder)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
    }.getOrNull()
}

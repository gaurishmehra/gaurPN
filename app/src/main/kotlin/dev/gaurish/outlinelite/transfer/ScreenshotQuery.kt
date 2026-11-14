package dev.gaurish.outlinelite.transfer

/**
 * MediaStore query for the newest screenshot. Pure so the exact-path filter is
 * unit-testable: `LIKE 'Pictures/Screenshots%'` also matched
 * `Pictures/ScreenshotsBackup`, so API 29+ uses an exact `relative_path`
 * match instead.
 *
 * The column names are the pinned values behind
 * `MediaStore.Images.Media.RELATIVE_PATH` ("relative_path") and
 * `MediaStore.Images.Media.DATA` ("_data"); spelling them out keeps this file
 * free of Android imports.
 */
internal data class ScreenshotQuery(val selection: String, val selectionArgs: Array<String>)

internal fun buildScreenshotQuery(apiLevel: Int): ScreenshotQuery =
    if (apiLevel >= 29) {
        // MediaStore stores RELATIVE_PATH with a trailing slash.
        ScreenshotQuery("relative_path = ?", arrayOf("Pictures/Screenshots/"))
    } else {
        ScreenshotQuery("_data LIKE ?", arrayOf("%/Pictures/Screenshots/%"))
    }

package dev.gaurish.outlinelite.ui.settings

import android.Manifest
import android.os.Build

/**
 * The runtime permissions the six MediaStore collections need. Android 13+
 * splits storage into media types, so all three are requested; older
 * releases gate the whole volume behind READ_EXTERNAL_STORAGE.
 *
 * These grants are optional for browsing: an ungranted collection lists
 * empty, and all-files access covers the rest of shared storage.
 */
internal fun browseMediaPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

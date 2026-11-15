package dev.gaurish.outlinelite.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * "All files access" (MANAGE_EXTERNAL_STORAGE) is what lifts the target's
 * MediaStore ceiling: media grants expose media rows only, so other apps'
 * PDFs, ZIPs, APKs, and documents stay invisible without it.
 *
 * Before Android 11 the legacy READ_EXTERNAL_STORAGE grant already covers the
 * whole shared volume, so the special access does not exist there and is not
 * needed: [hasAllFilesAccess] reports true and the File API is readable
 * whenever that runtime grant is held.
 */
internal fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

/**
 * The per-app "All files access" settings page. Some ROMs ship no activity
 * for it, so callers must wrap `startActivity` in `runCatching`.
 */
internal fun allFilesAccessIntent(context: Context): Intent = Intent(
    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
    Uri.parse("package:${context.packageName}"),
)

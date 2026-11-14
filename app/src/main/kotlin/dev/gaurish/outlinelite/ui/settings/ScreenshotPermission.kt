package dev.gaurish.outlinelite.ui.settings

/** What the screenshot-request toggle should do for a tap. */
internal enum class ScreenshotToggleAction {
    PERSIST_ON,
    PERSIST_OFF,
    REQUEST_PERMISSION,
}

/**
 * The screenshot feature reads MediaStore, so enabling it requires the photo
 * permission. Turning it on without the grant would leave a toggle that can
 * never answer; request first and persist only after the grant.
 */
internal fun screenshotToggleAction(
    enabled: Boolean,
    permissionGranted: Boolean,
): ScreenshotToggleAction = when {
    !enabled -> ScreenshotToggleAction.PERSIST_OFF
    permissionGranted -> ScreenshotToggleAction.PERSIST_ON
    else -> ScreenshotToggleAction.REQUEST_PERMISSION
}

/** A denied permission request always leaves the feature off. */
internal fun screenshotPermissionResultAction(granted: Boolean): ScreenshotToggleAction =
    if (granted) ScreenshotToggleAction.PERSIST_ON else ScreenshotToggleAction.PERSIST_OFF

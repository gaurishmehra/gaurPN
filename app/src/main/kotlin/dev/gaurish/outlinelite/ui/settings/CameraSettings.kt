package dev.gaurish.outlinelite.ui.settings

/** What the camera-request toggle should do for a tap. */
internal enum class CameraToggleAction {
    PERSIST_ON,
    PERSIST_OFF,
    REQUEST_PERMISSION,
}

/**
 * Streaming the camera needs the CAMERA runtime permission — Android 14+
 * refuses to start a camera foreground service without it, so a granted-less
 * toggle could never answer. Turning it on without the grant requests first
 * and persists only after the grant.
 */
internal fun cameraToggleAction(
    enabled: Boolean,
    permissionGranted: Boolean,
): CameraToggleAction = when {
    !enabled -> CameraToggleAction.PERSIST_OFF
    permissionGranted -> CameraToggleAction.PERSIST_ON
    else -> CameraToggleAction.REQUEST_PERMISSION
}

/** A denied permission request always leaves the feature off. */
internal fun cameraPermissionResultAction(granted: Boolean): CameraToggleAction =
    if (granted) CameraToggleAction.PERSIST_ON else CameraToggleAction.PERSIST_OFF

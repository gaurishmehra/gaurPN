package dev.gaurish.outlinelite.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSettingsTest {

    @Test
    fun togglingOffAlwaysPersistsOff() {
        assertEquals(
            CameraToggleAction.PERSIST_OFF,
            cameraToggleAction(enabled = false, permissionGranted = true),
        )
        assertEquals(
            CameraToggleAction.PERSIST_OFF,
            cameraToggleAction(enabled = false, permissionGranted = false),
        )
    }

    @Test
    fun togglingOnWithPermissionPersistsOn() {
        assertEquals(
            CameraToggleAction.PERSIST_ON,
            cameraToggleAction(enabled = true, permissionGranted = true),
        )
    }

    @Test
    fun togglingOnWithoutPermissionRequestsIt() {
        assertEquals(
            CameraToggleAction.REQUEST_PERMISSION,
            cameraToggleAction(enabled = true, permissionGranted = false),
        )
    }

    @Test
    fun deniedPermissionLeavesTheFeatureOff() {
        assertEquals(
            CameraToggleAction.PERSIST_OFF,
            cameraPermissionResultAction(granted = false),
        )
        assertEquals(
            CameraToggleAction.PERSIST_ON,
            cameraPermissionResultAction(granted = true),
        )
    }
}

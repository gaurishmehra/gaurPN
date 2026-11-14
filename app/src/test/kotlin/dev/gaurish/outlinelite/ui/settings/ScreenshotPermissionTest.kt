package dev.gaurish.outlinelite.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotPermissionTest {

    @Test
    fun togglingOffAlwaysPersistsOff() {
        assertEquals(
            ScreenshotToggleAction.PERSIST_OFF,
            screenshotToggleAction(enabled = false, permissionGranted = true),
        )
        assertEquals(
            ScreenshotToggleAction.PERSIST_OFF,
            screenshotToggleAction(enabled = false, permissionGranted = false),
        )
    }

    @Test
    fun togglingOnWithPermissionPersistsOn() {
        assertEquals(
            ScreenshotToggleAction.PERSIST_ON,
            screenshotToggleAction(enabled = true, permissionGranted = true),
        )
    }

    @Test
    fun togglingOnWithoutPermissionRequestsIt() {
        assertEquals(
            ScreenshotToggleAction.REQUEST_PERMISSION,
            screenshotToggleAction(enabled = true, permissionGranted = false),
        )
    }

    @Test
    fun deniedPermissionLeavesTheFeatureOff() {
        assertEquals(
            ScreenshotToggleAction.PERSIST_OFF,
            screenshotPermissionResultAction(granted = false),
        )
        assertEquals(
            ScreenshotToggleAction.PERSIST_ON,
            screenshotPermissionResultAction(granted = true),
        )
    }
}

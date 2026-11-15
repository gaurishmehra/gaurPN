package dev.gaurish.outlinelite.ui.settings

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Pins which runtime grants the six media collections ask for: all three
 * media types on Android 13+, the legacy volume grant before it.
 */
class BrowsePermissionsTest {

    @Test
    fun android13AsksForEveryMediaType() {
        assertArrayEquals(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            ),
            browseMediaPermissions(33),
        )
    }

    @Test
    fun olderReleasesAskForTheLegacyStorageGrant() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            browseMediaPermissions(32),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            browseMediaPermissions(24),
        )
    }
}

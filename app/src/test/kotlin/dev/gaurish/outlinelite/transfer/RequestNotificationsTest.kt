package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestNotificationsTest {

    @Test
    fun fetchTextCarriesTheLabelAndCountButNoFileNames() {
        assertEquals(
            "Lilac (lilac-76…) fetched 3 file(s)",
            RequestNotifications.fetchNotificationText("Lilac", "lilac-76ee1234", 3),
        )
        assertEquals(
            "Lilac (lilac-76…) fetched 1 file(s)",
            RequestNotifications.fetchNotificationText("Lilac", "lilac-76ee1234", 1),
        )
    }

    @Test
    fun fetchTextSanitizesThePeerName() {
        // Bidi overrides and line breaks never reach the notification.
        assertEquals(
            "Lilac (lilac-76…) fetched 2 file(s)",
            RequestNotifications.fetchNotificationText("Lil\u202Eac\n", "lilac-76ee1234", 2),
        )
        // A blank name falls back to the stable id, which is also the id
        // prefix shown in parentheses.
        assertEquals(
            "lilac-76ee1234 (lilac-76…) fetched 2 file(s)",
            RequestNotifications.fetchNotificationText("   ", "lilac-76ee1234", 2),
        )
    }
}

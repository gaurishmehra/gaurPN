package dev.gaurish.outlinelite.ui

/**
 * Screen ids used by MainActivity's int-based navigation. Centralized so
 * adding a screen can never silently collide with an existing id.
 */
object Screens {
    const val HOME = 0
    const val SETTINGS = 1
    const val SPLIT_TUNNEL = 2
    const val HISTORY = 3
    const val TRUSTED_DEVICES = 4
    const val DNS = 5
    const val CLIPBOARD_SYNC = 6
    const val NOTIFICATIONS = 7
    const val MEDIA = 8
    const val TRANSFERS = 9
    const val BROWSE = 10
    const val SERVERS = 11

    val ALL = listOf(
        HOME, SETTINGS, SPLIT_TUNNEL, HISTORY, TRUSTED_DEVICES,
        DNS, CLIPBOARD_SYNC, NOTIFICATIONS, MEDIA, TRANSFERS, BROWSE, SERVERS,
    )
}

package dev.gaurish.outlinelite.transfer

/**
 * Decides whether a mirrored notification should be dropped because this
 * device already has the source app installed. The installed app posts its own
 * notification here, so showing the mirrored copy would duplicate it.
 *
 * Desktop-originated payloads carry no package name and are always shown.
 * Pure Kotlin so the rule stays unit-testable.
 */
internal object NotificationDedupe {

    fun shouldSuppress(
        packageName: String,
        selfPackage: String,
        isInstalled: (String) -> Boolean,
    ): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == selfPackage) return false
        return isInstalled(packageName)
    }
}

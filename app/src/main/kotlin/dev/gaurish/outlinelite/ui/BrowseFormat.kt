package dev.gaurish.outlinelite.ui

import dev.gaurish.outlinelite.transfer.BrowseEntry
import dev.gaurish.outlinelite.transfer.BrowseError
import dev.gaurish.outlinelite.transfer.DeclineReason
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure formatting helpers for the browse screen: byte sizes, file kinds,
 * stable list keys, and the friendly text for the pinned listing errors and
 * decline reasons.
 */
internal enum class FileKind(val badge: String) {
    IMAGE("IMG"),
    VIDEO("VID"),
    AUDIO("AUD"),
    DOCUMENT("DOC"),
    ARCHIVE("ZIP"),
    OTHER("FILE"),
}

private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "tif", "tiff",
)
private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "flv", "wmv", "mpeg", "mpg",
)
private val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "aiff", "alac",
)
private val DOCUMENT_EXTENSIONS = setOf(
    "pdf", "doc", "docx", "txt", "md", "rtf", "odt", "xls", "xlsx", "csv", "ods",
    "ppt", "pptx", "odp", "epub",
)
private val ARCHIVE_EXTENSIONS = setOf(
    "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst",
)

/** Classifies a file name by its extension; unknown or missing is OTHER. */
internal fun fileKind(name: String): FileKind {
    val dot = name.lastIndexOf('.')
    if (dot < 0 || dot == name.lastIndex) return FileKind.OTHER
    val extension = name.substring(dot + 1).lowercase(Locale.US)
    return when (extension) {
        in IMAGE_EXTENSIONS -> FileKind.IMAGE
        in VIDEO_EXTENSIONS -> FileKind.VIDEO
        in AUDIO_EXTENSIONS -> FileKind.AUDIO
        in DOCUMENT_EXTENSIONS -> FileKind.DOCUMENT
        in ARCHIVE_EXTENSIONS -> FileKind.ARCHIVE
        else -> FileKind.OTHER
    }
}

/**
 * Human-readable byte count: "812 B", "12.4 KB", "3.2 MB", "1.4 GB". One
 * decimal below 100, none above, and a trailing ".0" is dropped.
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "Unknown"
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val text = if (value >= 100.0) {
        String.format(Locale.US, "%.0f", value)
    } else {
        String.format(Locale.US, "%.1f", value)
    }
    return "${text.removeSuffix(".0")} ${units[unit]}"
}

/** Localized medium date, or "" when the peer did not report a timestamp. */
internal fun formatModified(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return runCatching {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }.getOrDefault("")
}

/**
 * Stable LazyColumn keys for one listing. The codec drops duplicate
 * (name, dir) pairs, so the base key is already unique; the index suffix is a
 * last-resort guard so a producer that slips a duplicate through cannot crash
 * the screen (Compose throws on duplicate keys).
 */
internal fun browseEntryKeys(entries: List<BrowseEntry>): List<String> {
    val seen = HashSet<String>(entries.size)
    return entries.mapIndexed { index, entry ->
        val base = "${entry.dir}:${entry.name}"
        if (seen.add(base)) base else "$base:$index"
    }
}

/** Friendly text for the pinned `browse_listing` errors. */
internal fun browseErrorMessage(error: BrowseError): String = when (error) {
    BrowseError.DENIED -> "Device refused the request"
    BrowseError.NOT_FOUND -> "Path not found"
    BrowseError.RATE_LIMITED -> "Too many requests. Slow down."
    BrowseError.UNSUPPORTED -> "This device can't browse files"
}

/** Friendly text for a decline that arrives after a fetch. */
internal fun declineMessage(reason: DeclineReason): String = when (reason) {
    DeclineReason.DECLINED -> "The other device declined the download"
    DeclineReason.BUSY -> "The other device is busy"
    DeclineReason.EXPIRED -> "The request expired"
    DeclineReason.UNSUPPORTED -> "The other device can't send these files"
    DeclineReason.PICKER_UNAVAILABLE -> "The other device couldn't open its files"
    DeclineReason.NOT_ALLOWED -> "The other device doesn't allow downloads"
    DeclineReason.NOT_FOUND -> "Some files no longer exist"
    DeclineReason.TOO_LARGE -> "Too large. The batch is over 4 GB."
}

package dev.gaurish.outlinelite.transfer

/**
 * The exact MediaStore query one browse listing runs.
 *
 * The column name is the pinned value behind
 * `MediaStore.MediaColumns.RELATIVE_PATH` ("relative_path"), spelled out like
 * [ScreenshotQuery] so this file stays free of Android imports and the LIKE
 * escape plus the row LIMIT are JVM-unit-testable.
 *
 * [limit] bounds how many rows the provider materializes. Grouping in
 * [BrowseListingBuilder] still caps the emitted listing at
 * [RequestCodec.MAX_LISTING_ENTRIES]; the headroom above the cap lets a
 * directory with many files still fill it, while a hostile or enormous
 * collection can no longer make one query stream an unbounded cursor.
 */
internal data class BrowseQuery(
    val selection: String,
    val selectionArgs: Array<String>,
    val limit: Int,
)

/** Rows one listing query may return: the entry cap plus grouping headroom. */
internal const val BROWSE_QUERY_LIMIT = RequestCodec.MAX_LISTING_ENTRIES + 50

/** The subtree query for [path]: its rows and everything below it. */
internal fun buildBrowseQuery(path: String): BrowseQuery = BrowseQuery(
    // The ESCAPE clause keeps a peer-supplied "%" or "_" literal instead of
    // letting it widen the prefix match.
    selection = "relative_path LIKE ? ESCAPE '\\'",
    selectionArgs = arrayOf(escapeLike(path) + "/%"),
    limit = BROWSE_QUERY_LIMIT,
)

/** Escapes LIKE metacharacters so a peer-supplied path matches literally. */
internal fun escapeLike(value: String): String = buildString(value.length + 8) {
    value.forEach { char ->
        when (char) {
            '\\', '%', '_' -> {
                append('\\')
                append(char)
            }
            else -> append(char)
        }
    }
}

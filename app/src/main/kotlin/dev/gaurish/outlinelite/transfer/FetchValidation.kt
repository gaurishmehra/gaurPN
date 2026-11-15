package dev.gaurish.outlinelite.transfer

/** Why a fetch was refused before any bytes moved. */
internal enum class FetchRejection(val reason: DeclineReason) {
    /** Zero paths, more than [FetchValidation.MAX_PATHS], or a size mismatch. */
    INVALID_COUNT(DeclineReason.NOT_ALLOWED),

    /** A path segment longer than any listable entry name. */
    INVALID_PATH(DeclineReason.NOT_FOUND),

    NOT_FOUND(DeclineReason.NOT_FOUND),
    TOO_LARGE(DeclineReason.TOO_LARGE),
}

/**
 * Pure pre-send validation for a sealed `fetch`. The codec already bounds the
 * path list; this is the target's second gate after resolving every path, and
 * it is the only place the 4 GiB batch cap lives.
 */
internal object FetchValidation {

    /** Must match [RequestCodec.MAX_FETCH_PATHS]. */
    const val MAX_PATHS = RequestCodec.MAX_FETCH_PATHS

    /** Largest batch a target will send for one fetch. */
    const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024

    /**
     * Longest accepted path segment, in UTF-16 units. Mirrors the desktop's
     * cap and [RequestCodec.MAX_ENTRY_NAME_CHARS]: a longer segment could
     * never have appeared in a listing, so it cannot name a real file.
     */
    const val MAX_SEGMENT_CHARS = 255

    /**
     * Returns null when the batch is sendable. [sizes] holds the resolved
     * size per path in the same order, with null for a path that did not
     * resolve. The running total saturates at the cap, so a batch of huge
     * values can never overflow into a false accept.
     */
    fun validate(paths: List<String>, sizes: List<Long?>): FetchRejection? {
        if (paths.isEmpty() || paths.size > MAX_PATHS || sizes.size != paths.size) {
            return FetchRejection.INVALID_COUNT
        }
        if (paths.any { path -> path.split('/').any { it.length > MAX_SEGMENT_CHARS } }) {
            return FetchRejection.INVALID_PATH
        }
        var total = 0L
        for (size in sizes) {
            val bytes = size ?: return FetchRejection.NOT_FOUND
            if (bytes < 0L) return FetchRejection.NOT_FOUND
            if (bytes > MAX_TOTAL_BYTES - total) return FetchRejection.TOO_LARGE
            total += bytes
        }
        return null
    }
}

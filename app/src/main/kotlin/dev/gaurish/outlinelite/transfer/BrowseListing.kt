package dev.gaurish.outlinelite.transfer

/**
 * The fixed collection roots this phone exposes to a browsing peer. The
 * names are pinned: they are the first path segment on the wire and the
 * prefix of every fetch path.
 */
enum class MediaCollection(val root: String) {
    PICTURES("Pictures"),
    DCIM("DCIM"),
    DOWNLOAD("Download"),
    DOCUMENTS("Documents"),
    MOVIES("Movies"),
    MUSIC("Music");

    companion object {
        fun forRoot(root: String): MediaCollection? = entries.firstOrNull { it.root == root }
    }
}

/**
 * One MediaStore row reduced to what browsing needs. [relativePath] is the
 * provider value including its trailing slash (e.g. "Pictures/Screenshots/");
 * [modified] is epoch-ms.
 */
internal data class MediaRow(
    val collection: MediaCollection,
    val relativePath: String,
    val displayName: String,
    val size: Long,
    val modified: Long,
)

/** A listing plus whether the entry cap cut it short. */
internal data class BrowseListing(
    val entries: List<BrowseEntry>,
    val truncated: Boolean,
)

/**
 * Pure virtual-path scheme shared by the target's MediaStore queries and the
 * requester's UI:
 *
 *  - "" lists the six collection roots.
 *  - "Pictures" lists the Pictures subtree: the next segment becomes a
 *    directory entry, and rows directly in "Pictures/" become file entries.
 *  - A file's fetch path is its directory path plus its display name, e.g.
 *    "Pictures/Screenshots/Screenshot_03.png".
 *
 * No Android imports, so the grouping, ordering, cap, and fetch-path round
 * trip are JVM-testable.
 */
internal object BrowseListingBuilder {

    /** Same cap the wire codec enforces. */
    const val MAX_ENTRIES = RequestCodec.MAX_LISTING_ENTRIES

    private val ENTRY_ORDER: Comparator<BrowseEntry> =
        compareBy<BrowseEntry> { !it.dir }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.name }

    /** The six roots, sorted like every other listing: dirs first, then name. */
    fun rootEntries(): List<BrowseEntry> = MediaCollection.entries
        .map { BrowseEntry(name = it.root, size = 0L, modified = 0L, dir = true) }
        .sortedWith(ENTRY_ORDER)

    /**
     * Groups [rows] into the listing for [path]. Rows from other collections
     * and rows outside the requested subtree are ignored. Directories sort
     * before files, then case-insensitively by name, and the result is cut at
     * [MAX_ENTRIES] with [BrowseListing.truncated] set.
     */
    fun build(rows: List<MediaRow>, path: String): BrowseListing {
        if (path.isEmpty()) return BrowseListing(rootEntries(), truncated = false)
        val root = path.substringBefore('/')
        val collection = MediaCollection.forRoot(root)
            ?: return BrowseListing(emptyList(), truncated = false)

        val dirs = LinkedHashMap<String, Long>()
        val files = ArrayList<BrowseEntry>()
        for (row in rows) {
            if (row.collection != collection) continue
            val dirPath = normalizeDir(row.relativePath)
            when {
                dirPath == path -> {
                    // A name the wire codec would reject would be skipped by
                    // every requester (or, worse, key-collide in the UI), so
                    // it never leaves this phone in the first place.
                    if (!RequestCodec.isEntryName(row.displayName)) continue
                    files.add(
                        BrowseEntry(
                            name = row.displayName,
                            size = row.size,
                            modified = row.modified,
                            dir = false,
                        ),
                    )
                }
                dirPath.startsWith("$path/") -> {
                    val next = dirPath.substring(path.length + 1).substringBefore('/')
                    // Same rule for directory segments: "." and "..", control
                    // characters, and over-long names are not listable.
                    if (next.isNotEmpty() && RequestCodec.isEntryName(next)) {
                        // MediaStore has no directory mtime; the newest child
                        // is the closest useful value for a peer's UI.
                        dirs[next] = maxOf(dirs[next] ?: 0L, row.modified)
                    }
                }
            }
        }

        val all = (dirs.map { (name, modified) ->
            BrowseEntry(name = name, size = 0L, modified = modified, dir = true)
        } + files).sortedWith(ENTRY_ORDER)
        val truncated = all.size > MAX_ENTRIES
        return BrowseListing(
            entries = if (truncated) all.take(MAX_ENTRIES) else all,
            truncated = truncated,
        )
    }

    /** "Pictures/Screenshots/" -> "Pictures/Screenshots". */
    fun normalizeDir(relativePath: String): String = relativePath.trimEnd('/')

    /** The exact path a requester sends back in a `fetch` for [row]. */
    fun fetchPath(row: MediaRow): String {
        val dir = normalizeDir(row.relativePath)
        return if (dir.isEmpty()) row.displayName else "$dir/${row.displayName}"
    }

    /**
     * Resolves a fetch path back to the row that produced it. The match is
     * exact and collection-scoped, so a path can never resolve into a
     * different collection with a colliding relative path.
     */
    fun resolve(rows: List<MediaRow>, path: String): MediaRow? {
        if (!RequestCodec.isRelativePath(path)) return null
        val collection = MediaCollection.forRoot(path.substringBefore('/')) ?: return null
        return rows.firstOrNull { it.collection == collection && fetchPath(it) == path }
    }
}

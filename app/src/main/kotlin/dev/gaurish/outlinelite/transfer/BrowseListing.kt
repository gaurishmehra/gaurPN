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

/**
 * One shared-storage child reduced to what browsing needs. The File adapter
 * in [BrowseSource] fills these; keeping the row a plain value leaves the
 * filtering, ordering, and cap JVM-testable.
 *
 * [symlink] is carried explicitly instead of being resolved here: a link may
 * point outside the storage root, so the builder drops it before it can ever
 * appear in a listing.
 */
internal data class StorageRow(
    val name: String,
    val size: Long,
    val modified: Long,
    val dir: Boolean,
    val symlink: Boolean = false,
)

/** Which source answers a listing or fetch path. */
internal enum class BrowseBackend { MEDIA_STORE, STORAGE }

/** A listing plus whether the entry cap cut it short. */
internal data class BrowseListing(
    val entries: List<BrowseEntry>,
    val truncated: Boolean,
)

/**
 * Pure virtual-path scheme shared by the target's MediaStore queries and the
 * requester's UI:
 *
 *  - "" lists the collection roots: the six MediaStore collections, plus
 *    "Storage" when the target holds all-files access.
 *  - "Pictures" lists the Pictures subtree: the next segment becomes a
 *    directory entry, and rows directly in "Pictures/" become file entries.
 *  - "Storage" lists the real shared-storage tree, rooted at the external
 *    storage directory. With all-files access that is every file the user
 *    can see, not just MediaStore rows.
 *  - A file's fetch path is its directory path plus its display name, e.g.
 *    "Pictures/Screenshots/Screenshot_03.png" or "Storage/Download/a.pdf".
 *
 * No Android imports, so the grouping, ordering, cap, containment policy, and
 * fetch-path round trip are JVM-testable.
 */
internal object BrowseListingBuilder {

    /** Same cap the wire codec enforces. */
    const val MAX_ENTRIES = RequestCodec.MAX_LISTING_ENTRIES

    /** The seventh root: the real shared-storage tree. */
    const val STORAGE_ROOT = "Storage"

    private val ENTRY_ORDER: Comparator<BrowseEntry> =
        compareBy<BrowseEntry> { !it.dir }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.name }

    /**
     * The roots a peer sees, sorted like every other listing. [includeStorage]
     * is true only when the target holds all-files access: without it the
     * Storage tree would list almost nothing, so the root stays hidden
     * instead of advertising a dead end.
     */
    fun rootEntries(includeStorage: Boolean = false): List<BrowseEntry> {
        val names = MediaCollection.entries.map { it.root } +
            if (includeStorage) listOf(STORAGE_ROOT) else emptyList()
        return names
            .map { BrowseEntry(name = it, size = 0L, modified = 0L, dir = true) }
            .sortedWith(ENTRY_ORDER)
    }

    /**
     * Groups [rows] into the listing for [path]. Rows from other collections
     * and rows outside the requested subtree are ignored. Directories sort
     * before files, then case-insensitively by name, and the result is cut at
     * [MAX_ENTRIES] with [BrowseListing.truncated] set.
     */
    fun build(
        rows: List<MediaRow>,
        path: String,
        includeStorage: Boolean = false,
    ): BrowseListing {
        if (path.isEmpty()) {
            return BrowseListing(rootEntries(includeStorage), truncated = false)
        }
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

    // ------------------------------------------------------------- storage

    /**
     * The storage-relative tail of [path]: "" for the Storage root itself,
     * "Download/a.pdf" for "Storage/Download/a.pdf", and null when [path] is
     * not a Storage path or carries a segment the tree never lists.
     *
     * Dot-prefixed segments are hidden from every listing, so a peer must not
     * reach them by guessing the name. [RequestCodec.isRelativePath] already
     * rejects ".", "..", empty segments, and control characters; this adds
     * the dotfile policy on top.
     */
    fun storageSubpath(path: String): String? {
        if (path == STORAGE_ROOT) return ""
        if (!path.startsWith("$STORAGE_ROOT/")) return null
        val tail = path.substring(STORAGE_ROOT.length + 1)
        if (!RequestCodec.isRelativePath(tail)) return null
        if (tail.split('/').any { it.startsWith(".") }) return null
        return tail
    }

    /**
     * The source that answers [path], or null when no root can. A Storage
     * path that fails the dotfile/segment policy is indistinguishable from an
     * unknown root: neither could have come from a listing.
     */
    fun backendFor(path: String): BrowseBackend? = when {
        path.isEmpty() -> null
        storageSubpath(path) != null -> BrowseBackend.STORAGE
        MediaCollection.forRoot(path.substringBefore('/')) != null -> BrowseBackend.MEDIA_STORE
        else -> null
    }

    /**
     * True when [resolved] is [root] itself or below it. Both paths must
     * already be canonical (symlinks resolved); the separator check keeps a
     * sibling such as "/storage/emulated/0-backup" from passing as a prefix
     * match. Android shared storage is always POSIX, so "/" is the separator.
     */
    fun isInsideStorageRoot(root: String, resolved: String): Boolean {
        val base = root.trimEnd('/')
        return resolved == base || resolved.startsWith("$base/")
    }

    /**
     * Builds one shared-storage listing from [rows]. Symlinks and dotfiles
     * are skipped (the tree hides them, so a guessed name must not resolve),
     * names the wire codec would reject are dropped, and the result is
     * ordered and capped exactly like a MediaStore listing.
     */
    fun buildStorage(rows: List<StorageRow>): BrowseListing {
        val entries = rows.asSequence()
            .filter { !it.symlink }
            .filter { isStorageEntryName(it.name) }
            .map {
                BrowseEntry(
                    name = it.name,
                    // A directory's File.length() is meaningless; report 0
                    // like the MediaStore grouping does.
                    size = if (it.dir) 0L else it.size.coerceAtLeast(0L),
                    modified = it.modified.coerceAtLeast(0L),
                    dir = it.dir,
                )
            }
            .sortedWith(ENTRY_ORDER)
            .toList()
        val truncated = entries.size > MAX_ENTRIES
        return BrowseListing(
            entries = if (truncated) entries.take(MAX_ENTRIES) else entries,
            truncated = truncated,
        )
    }

    /** One listable shared-storage name: a safe segment that is not a dotfile. */
    fun isStorageEntryName(name: String): Boolean =
        RequestCodec.isEntryName(name) && !name.startsWith(".")
}

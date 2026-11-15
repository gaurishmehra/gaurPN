package dev.gaurish.outlinelite.transfer

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A fetch path resolved to the URI that will be sent and its size. MediaStore
 * rows resolve to a content URI; Storage paths resolve to a file URI.
 */
internal data class ResolvedFetch(val size: Long, val uri: Uri)

/**
 * The phone-as-browse-source adapter. All grouping, ordering, and path
 * resolution lives in [BrowseListingBuilder]; this object only runs the
 * cursor queries and the File API calls, and keeps both fail-closed:
 *
 *  - a missing permission, a provider error, or an unsupported API level
 *    yields an empty listing (or no resolution), never a crash;
 *  - every MediaStore path is matched exactly
 *    (`RELATIVE_PATH = ? AND DISPLAY_NAME = ?`) and re-checked against the
 *    pure fetch-path rule before a URI is built;
 *  - every Storage path is canonicalized one segment at a time and required
 *    to stay inside the external storage root, so "..", a nested escape, or
 *    a symlink can never resolve outside it;
 *  - the MediaStore subtree query carries the [buildBrowseQuery] LIMIT, so one
 *    huge collection cannot materialize an unbounded cursor.
 *
 * RELATIVE_PATH and the Downloads collection exist from API 29; older
 * releases simply expose empty media collections. Storage is served by the
 * File API, which needs all-files access from API 30 and the legacy storage
 * grant before it.
 */
internal object BrowseSource {

    private const val TAG = "RelayManager"

    private data class QueryRow(val id: Long, val row: MediaRow)

    /** Lists [path]; an unknown root or unreadable source is empty. */
    suspend fun list(context: Context, path: String): BrowseListing = withContext(Dispatchers.IO) {
        if (path.isEmpty()) {
            // The Storage root appears only when the whole tree is readable:
            // without all-files access it would be a dead end for the peer.
            return@withContext BrowseListingBuilder.build(
                emptyList(),
                path,
                includeStorage = hasAllFilesAccess(),
            )
        }
        when (BrowseListingBuilder.backendFor(path)) {
            BrowseBackend.MEDIA_STORE -> {
                val rows = querySubtree(context, path)?.map { it.row } ?: emptyList()
                BrowseListingBuilder.build(rows, path)
            }
            BrowseBackend.STORAGE -> listStorage(path)
            null -> BrowseListing(emptyList(), truncated = false)
        }
    }

    /** Resolves one fetch path to its URI, or null when it is gone or out of bounds. */
    suspend fun resolve(context: Context, path: String): ResolvedFetch? =
        withContext(Dispatchers.IO) {
            when (BrowseListingBuilder.backendFor(path)) {
                BrowseBackend.STORAGE -> resolveStorage(path)
                BrowseBackend.MEDIA_STORE -> resolveMediaStore(context, path)
                null -> null
            }
        }

    // --------------------------------------------------------- MediaStore

    private fun resolveMediaStore(context: Context, path: String): ResolvedFetch? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val slash = path.lastIndexOf('/')
        if (slash <= 0 || slash == path.lastIndex) return null
        val dir = path.substring(0, slash + 1)
        val name = path.substring(slash + 1)
        val collection = MediaCollection.forRoot(path.substringBefore('/'))
            ?: return null
        val uri = collectionUri(collection) ?: return null
        val match = queryExact(context, uri, collection, dir, name)
            ?.firstOrNull { BrowseListingBuilder.fetchPath(it.row) == path }
            ?: return null
        return ResolvedFetch(match.row.size, ContentUris.withAppendedId(uri, match.id))
    }

    private fun querySubtree(context: Context, path: String): List<QueryRow>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaCollection.forRoot(path.substringBefore('/')) ?: return null
        val uri = collectionUri(collection) ?: return null
        val query = buildBrowseQuery(path)
        return readRows(
            context = context,
            uri = uri,
            collection = collection,
            selection = query.selection,
            args = query.selectionArgs,
            limit = query.limit,
        )
    }

    private fun queryExact(
        context: Context,
        uri: Uri,
        collection: MediaCollection,
        dir: String,
        name: String,
    ): List<QueryRow>? = readRows(
        context = context,
        uri = uri,
        collection = collection,
        selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
        args = arrayOf(dir, name),
    )

    private fun readRows(
        context: Context,
        uri: Uri,
        collection: MediaCollection,
        selection: String,
        args: Array<String>,
        limit: Int? = null,
    ): List<QueryRow>? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        return try {
            val cursor = if (limit != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // A subtree listing is the only unbounded query; the provider
                // gets an explicit LIMIT so one huge collection cannot stream
                // an endless cursor into memory.
                val queryArgs = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                }
                context.contentResolver.query(uri, projection, queryArgs, null)
            } else {
                context.contentResolver.query(uri, projection, selection, args, null)
            }
            cursor?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val relativeColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val nameColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

                val rows = ArrayList<QueryRow>(cursor.count.coerceAtLeast(0))
                while (cursor.moveToNext()) {
                    val relativePath = cursor.getString(relativeColumn) ?: continue
                    val displayName = cursor.getString(nameColumn) ?: continue
                    // A hostile or corrupt provider row must not become a
                    // listing entry that cannot be fetched back.
                    if (!RequestCodec.isEntryName(displayName)) continue
                    val size = cursor.getLong(sizeColumn).coerceAtLeast(0L)
                    val modifiedSeconds = cursor.getLong(modifiedColumn)
                        .coerceIn(0L, Long.MAX_VALUE / 1000L)
                    rows.add(
                        QueryRow(
                            id = cursor.getLong(idColumn),
                            row = MediaRow(
                                collection = collection,
                                relativePath = relativePath,
                                displayName = displayName,
                                size = size,
                                modified = modifiedSeconds * 1000L,
                            ),
                        ),
                    )
                }
                rows
            }
        } catch (t: Throwable) {
            // SecurityException (permission not granted) and provider errors
            // both fail closed to an empty listing. Only the exception type is
            // logged: paths and file names stay out of the log.
            Log.w(TAG, "MediaStore browse query failed: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun collectionUri(collection: MediaCollection): Uri? = when (collection) {
        MediaCollection.PICTURES, MediaCollection.DCIM ->
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        MediaCollection.DOWNLOAD ->
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
        MediaCollection.DOCUMENTS ->
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        MediaCollection.MOVIES ->
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        MediaCollection.MUSIC ->
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }

    // ----------------------------------------------------------- storage

    /**
     * The canonical shared-storage root, or null without all-files access.
     * Resolving the root once means every containment check compares against
     * the real directory, so a root that is itself a link cannot be used to
     * escape.
     */
    private fun storageRoot(): File? {
        if (!hasAllFilesAccess()) return null
        return runCatching {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory().canonicalFile
        }.getOrNull()
    }

    /**
     * Lists one directory under the storage root through the File API. With
     * all-files access this sees every file the user can see, which is the
     * point of the Storage root; without it [storageRoot] returns null and
     * the listing stays empty.
     *
     * The directory is read in one pass because the platform offers no
     * bounded listing before API 26; the browse rate limiter and worker cap
     * bound how often that can happen, and the emitted listing is still cut
     * at [BrowseListingBuilder.MAX_ENTRIES]. Only names and stat results are
     * held, never file contents.
     */
    private fun listStorage(path: String): BrowseListing {
        val root = storageRoot() ?: return BrowseListing(emptyList(), truncated = false)
        val sub = BrowseListingBuilder.storageSubpath(path)
            ?: return BrowseListing(emptyList(), truncated = false)
        val dir = resolveStorageFile(root, sub)
            ?: return BrowseListing(emptyList(), truncated = false)
        if (!dir.isDirectory) return BrowseListing(emptyList(), truncated = false)
        val children = runCatching { dir.listFiles() }.getOrNull()
            ?: return BrowseListing(emptyList(), truncated = false)
        val rows = ArrayList<StorageRow>(
            minOf(children.size, BrowseListingBuilder.MAX_ENTRIES + 16),
        )
        for (child in children) {
            // Cheap policy checks first: a dotfile or an unlistable name is
            // skipped without touching the filesystem again.
            if (!BrowseListingBuilder.isStorageEntryName(child.name)) continue
            // A link is skipped even when it points inside the root: the
            // listing then never advertises a path whose fetch would be
            // refused, and a link to a dotfile stays hidden.
            val canonical = runCatching { child.canonicalFile }.getOrNull() ?: continue
            if (canonical.path != child.absoluteFile.path) continue
            val isDir = child.isDirectory
            rows.add(
                StorageRow(
                    name = child.name,
                    size = if (isDir) 0L else child.length().coerceAtLeast(0L),
                    modified = child.lastModified().coerceAtLeast(0L),
                    dir = isDir,
                ),
            )
        }
        return BrowseListingBuilder.buildStorage(rows)
    }

    /** Resolves a storage fetch path to a regular file inside the root. */
    private fun resolveStorage(path: String): ResolvedFetch? {
        val root = storageRoot() ?: return null
        val sub = BrowseListingBuilder.storageSubpath(path) ?: return null
        // The root itself is a directory, never a fetchable file.
        if (sub.isEmpty()) return null
        val file = resolveStorageFile(root, sub) ?: return null
        if (!file.isFile) return null
        val size = file.length()
        if (size < 0L) return null
        // The send flow reads file:// URIs directly (ChunkTransport and
        // OutboundSender both special-case the scheme), so an in-process read
        // needs no FileProvider hop.
        return ResolvedFetch(size, Uri.fromFile(file))
    }

    /**
     * Joins [sub] onto [root] one segment at a time and requires every
     * canonical prefix to stay inside the root. A path that escapes is
     * reported exactly like a missing one: the peer learns nothing about what
     * exists outside the root.
     */
    private fun resolveStorageFile(root: File, sub: String): File? {
        if (sub.isEmpty()) return root
        var current = root
        for (segment in sub.split('/')) {
            val next = File(current, segment)
            val canonical = runCatching { next.canonicalFile }.getOrNull() ?: return null
            // Canonical differs from the literal path only when a component
            // is a symlink (the segments themselves are already free of "."
            // and ".."). Links are not listable, so they must not resolve.
            if (canonical.path != next.absoluteFile.path) return null
            if (!BrowseListingBuilder.isInsideStorageRoot(root.path, canonical.path)) return null
            // A link can resolve to a dotfile even when the requested
            // segment did not start with "."; the tree hides dotfiles, so it
            // must not resolve here either.
            if (canonical.name.startsWith(".")) return null
            current = canonical
        }
        return current
    }
}

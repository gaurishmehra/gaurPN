package dev.gaurish.outlinelite.transfer

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A fetch path resolved to the MediaStore row and content URI behind it. */
internal data class ResolvedMedia(val row: MediaRow, val uri: Uri)

/**
 * The phone-as-browse-source MediaStore adapter. All grouping, ordering, and
 * path resolution lives in [BrowseListingBuilder]; this object only runs the
 * two cursor queries and keeps them fail-closed:
 *
 *  - a missing media permission, a provider error, or an unsupported API
 *    level yields an empty listing (or no resolution), never a crash;
 *  - every path is matched exactly (`RELATIVE_PATH = ? AND DISPLAY_NAME = ?`)
 *    and re-checked against the pure fetch-path rule before a URI is built;
 *  - the subtree query carries the [buildBrowseQuery] LIMIT, so one huge
 *    collection cannot materialize an unbounded cursor.
 *
 * RELATIVE_PATH and the Downloads collection exist from API 29; older
 * releases simply expose empty collections.
 */
internal object BrowseSource {

    private const val TAG = "RelayManager"

    private data class QueryRow(val id: Long, val row: MediaRow)

    /** Lists [path]; an unknown collection or unreadable provider is empty. */
    suspend fun list(context: Context, path: String): BrowseListing = withContext(Dispatchers.IO) {
        if (path.isEmpty()) {
            return@withContext BrowseListingBuilder.build(emptyList(), path)
        }
        val rows = querySubtree(context, path)?.map { it.row } ?: emptyList()
        BrowseListingBuilder.build(rows, path)
    }

    /** Resolves one fetch path to its content URI, or null when it is gone. */
    suspend fun resolve(context: Context, path: String): ResolvedMedia? =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
            val slash = path.lastIndexOf('/')
            if (slash <= 0 || slash == path.lastIndex) return@withContext null
            val dir = path.substring(0, slash + 1)
            val name = path.substring(slash + 1)
            val collection = MediaCollection.forRoot(path.substringBefore('/'))
                ?: return@withContext null
            val uri = collectionUri(collection) ?: return@withContext null
            val match = queryExact(context, uri, collection, dir, name)
                ?.firstOrNull { BrowseListingBuilder.fetchPath(it.row) == path }
                ?: return@withContext null
            ResolvedMedia(match.row, ContentUris.withAppendedId(uri, match.id))
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
}

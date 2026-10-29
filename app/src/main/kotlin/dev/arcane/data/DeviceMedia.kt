package dev.arcane.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** A folder the phone already has, the way a file manager shows one. */
data class DeviceAlbum(
    val bucketId: Long,
    val name: String,
    val cover: DeviceItem?,
    val itemCount: Int,
    val totalBytes: Long,
)

/** One file on the device, addressed by the uri any app — including ours — can open. */
data class DeviceItem(
    val uri: Uri,
    val name: String,
    val bucketId: Long,
    val bucketName: String,
    val mimeType: String?,
    val size: Long,
    val modifiedAtMillis: Long,
    val durationMillis: Long,
) {
    val isVideo: Boolean get() = mimeType?.startsWith("video") == true
    val isImage: Boolean get() = mimeType?.startsWith("image") == true
    val isAudio: Boolean get() = mimeType?.startsWith("audio") == true
}

enum class MediaKind { IMAGES, VIDEO, AUDIO }

/**
 * Simple mode: the phone's own files, unencrypted, read straight out of MediaStore.
 *
 * This is the half of the app that has to look ordinary. It is also the import source for the vault, which
 * is why it goes through MediaStore rather than raw paths — a scoped `content://` uri is the only thing an
 * app can read on a modern phone without asking for the whole disk.
 */
class DeviceMedia(context: Context) {

    private val app = context.applicationContext
    private val io: CoroutineDispatcher = queryExecutor.asCoroutineDispatcher()

    suspend fun albums(kinds: Set<MediaKind> = MediaKind.entries.toSet()): List<DeviceAlbum> =
        withContext(io) {
            kinds.flatMap { query(it) }
                .groupBy { it.bucketId }
                .map { (bucketId, items) ->
                    DeviceAlbum(
                        bucketId = bucketId,
                        name = items.first().bucketName,
                        cover = items.firstOrNull { it.isImage } ?: items.firstOrNull(),
                        itemCount = items.size,
                        totalBytes = items.sumOf { it.size },
                    )
                }
                .sortedByDescending { it.totalBytes }
        }

    /** Files in one album. A row can appear in two kinds at once, so the listing is de-duplicated by uri. */
    suspend fun items(albumId: Long, kinds: Set<MediaKind> = MediaKind.entries.toSet()): List<DeviceItem> =
        withContext(io) {
            val selection = "${MediaStore.MediaColumns.BUCKET_ID} = ?"
            val args = arrayOf(albumId.toString())
            kinds.flatMap { query(it, selection, args) }
                .distinctBy { it.uri }
                .sortedWith(compareBy({ it.name.lowercase() }, { it.uri.toString() }))
        }

    suspend fun recent(limit: Int): List<DeviceItem> = withContext(io) {
        MediaKind.entries.flatMap { query(it) }
            .distinctBy { it.uri }
            .sortedByDescending { it.modifiedAtMillis }
            .take(limit)
    }

    fun hasPermission(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun query(
        kind: MediaKind,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
    ): List<DeviceItem> {
        val base = kind.collection()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
        )
        val out = ArrayList<DeviceItem>()
        app.contentResolver.query(
            base,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val id = cursor.idx(MediaStore.MediaColumns._ID)
            val name = cursor.idx(MediaStore.MediaColumns.DISPLAY_NAME)
            val mime = cursor.idx(MediaStore.MediaColumns.MIME_TYPE)
            val size = cursor.idx(MediaStore.MediaColumns.SIZE)
            val modified = cursor.idx(MediaStore.MediaColumns.DATE_MODIFIED)
            val bucket = cursor.idx(MediaStore.MediaColumns.BUCKET_ID)
            val bucketName = cursor.idx(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val duration = cursor.idx(MediaStore.MediaColumns.DURATION)
            while (cursor.moveToNext()) {
                val rowId = cursor.longAt(id)
                out += DeviceItem(
                    // ContentUris, not Uri.withAppendedPath: the former knows the collection uri ends in a
                    // path segment that needs no slash handling and keeps the numeric id well-formed.
                    uri = ContentUris.withAppendedId(base, rowId),
                    name = cursor.stringAt(name) ?: "untitled",
                    bucketId = if (bucket >= 0) cursor.longAt(bucket) else NO_BUCKET,
                    bucketName = cursor.stringAt(bucketName) ?: "Files",
                    mimeType = cursor.stringAt(mime),
                    size = cursor.longAt(size),
                    // DATE_MODIFIED is in seconds; the column of that name is a lie in some providers.
                    modifiedAtMillis = cursor.longAt(modified) * 1000L,
                    durationMillis = cursor.longAt(duration),
                )
            }
        }
        return out
    }

    private fun MediaKind.collection(): Uri = when (this) {
        MediaKind.IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    private fun Cursor.idx(name: String): Int = getColumnIndex(name)

    private fun Cursor.stringAt(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    private fun Cursor.longAt(index: Int): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else 0L

    private companion object {
        const val NO_BUCKET = -1L

        // MediaStore cursors are cheap but not free, and a large library should not be walked on the main
        // thread; one serial thread keeps the ordering of a listing stable between refreshes.
        val queryExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "arcane-media").apply { isDaemon = true }
        }
    }
}

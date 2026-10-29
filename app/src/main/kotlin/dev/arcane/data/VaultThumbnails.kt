package dev.arcane.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.media3.datasource.DataSource
import dev.arcane.vault.format.Hex
import dev.arcane.vault.index.FileRecord
import java.util.Collections
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Covers for vault files, decrypted on demand and kept only in memory.
 *
 * The security rule this exists for: a thumbnail is plaintext. Nothing here touches the disk and nothing
 * survives the lock — [clear] empties the cache and bumps a generation so a decode that was already running
 * cannot quietly put its bitmap back afterwards.
 *
 * Images decode from their bytes through the chunk cache — whole files up to a cap, a bounded prefix above it
 * — while video frames and embedded album art go through [MediaMetadataRetriever] over
 * [VaultMediaDataSource], which keeps random access working without ever handing the parser a plaintext path.
 */
class VaultThumbnails(
    private val repository: VaultRepository,
    private val playbackSources: DataSource.Factory,
    io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val decodeDispatcher = io

    private val cache = object : LruCache<String, Bitmap>(maxSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Formats the platform refused multiple times are not asked again on every scroll. */
    private val unusable: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())
    private val failureCounts: MutableMap<String, Int> = Collections.synchronizedMap(mutableMapOf<String, Int>())

    /** Bumped by [clear]; a decode that finishes afterwards must not repopulate the cache. */
    @Volatile
    private var generation = 0

    /** The instant answer for a tile that is scrolling back into view. */
    fun peek(record: FileRecord): Bitmap? = cache.get(keyOf(record))

    suspend fun load(record: FileRecord): Bitmap? {
        if (!previewable(record)) return null
        val key = keyOf(record)
        cache.get(key)?.let { return it }
        if (key in unusable) return null
        val startedIn = generation
        val bitmap = runCatching { extract(record) }.getOrNull()
        if (bitmap == null) {
            val count = (failureCounts[key] ?: 0) + 1
            failureCounts[key] = count
            if (count >= 2) {
                unusable.add(key)
            }
            return null
        }
        // The realm may have locked while the frame was being extracted; a bitmap from that generation is
        // plaintext that must not become reachable again.
        if (startedIn != generation) return null
        failureCounts.remove(key)
        cache.put(key, bitmap)
        return bitmap
    }

    /** Called by the lock before the key is wiped. */
    fun clear() {
        generation++
        cache.evictAll()
        failureCounts.clear()
        unusable.clear()
    }

    private suspend fun extract(record: FileRecord): Bitmap? = when {
        isImage(record) -> image(record)
        isVideo(record) -> frame(record)
        isAudio(record) -> albumArt(record)
        else -> null
    }

    private suspend fun image(record: FileRecord): Bitmap? {
        // A truncated JPEG decodes to a half-grey picture rather than failing, so a prefix is only used for
        // files too large to read whole; anything up to that cap is decoded from all of its bytes.
        val cap = if (record.plainSize <= WHOLE_READ_BYTES) record.plainSize else PREFIX_BYTES
        val read = minOf(record.plainSize, cap).toInt()
        if (read <= 0) return null
        return decode(repository.bytesAt(record.fileId, 0, read))
    }

    private suspend fun decode(bytes: ByteArray): Bitmap? = withContext(decodeDispatcher) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, TARGET_PX)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let(::scaled)
    }

    private suspend fun frame(record: FileRecord): Bitmap? = withContext(decodeDispatcher) {
        retriever(record) { retriever ->
            // Try scaled extraction at 1s, then 0s with closest sync or closest
            val scaled = runCatching {
                retriever.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, TARGET_PX, TARGET_PX)
                    ?: retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, TARGET_PX, TARGET_PX)
                    ?: retriever.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST, TARGET_PX, TARGET_PX)
                    ?: retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST, TARGET_PX, TARGET_PX)
                    ?: retriever.getScaledFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, TARGET_PX, TARGET_PX)
            }.getOrNull()

            if (scaled != null) return@retriever scaled

            // Fallback to unscaled frame extraction and scale it down
            val unscaled = runCatching {
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(-1L)
                    ?: retriever.frameAtTime
            }.getOrNull()

            unscaled?.let(::scaled)
                ?: retriever.embeddedPicture?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::scaled)
                }
        }
    }

    private suspend fun albumArt(record: FileRecord): Bitmap? = withContext(decodeDispatcher) {
        retriever(record) { it.embeddedPicture }?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::scaled)
        }
    }

    private fun <T> retriever(record: FileRecord, block: (MediaMetadataRetriever) -> T?): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(VaultMediaDataSource(playbackSources, repository.playbackUri(record.fileId), record.plainSize))
            block(retriever)
        } catch (failure: Throwable) {
            // A container the platform cannot read is not an error worth surfacing in a grid.
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun keyOf(record: FileRecord): String = Hex.lower(record.fileId) + "@" + TARGET_PX

    private fun previewable(record: FileRecord): Boolean =
        isImage(record) || isVideo(record) || isAudio(record)

    private fun isImage(record: FileRecord): Boolean {
        val mime = record.mimeType?.lowercase().orEmpty()
        return mime.startsWith("image/") || extension(record) in IMAGE_EXT
    }

    private fun isVideo(record: FileRecord): Boolean {
        val mime = record.mimeType?.lowercase().orEmpty()
        return mime.startsWith("video/") || extension(record) in VIDEO_EXT
    }

    private fun isAudio(record: FileRecord): Boolean {
        val mime = record.mimeType?.lowercase().orEmpty()
        return mime.startsWith("audio/") || extension(record) in AUDIO_EXT
    }

    private fun extension(record: FileRecord): String = record.name.substringAfterLast('.', "").lowercase()

    private fun scaled(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= TARGET_PX) return bitmap
        val ratio = TARGET_PX.toFloat() / longest
        val small = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
        if (small !== bitmap) bitmap.recycle()
        return small
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        return sample
    }

    private companion object {
        /** Crisp at three times the density of a two-column grid. */
        const val TARGET_PX = 512

        /** Enough of a head for bounds plus the first scan pass of most photos. */
        const val PREFIX_BYTES = 2L shl 20

        /** The whole file is read only below this size, and only when the prefix was not enough. */
        const val WHOLE_READ_BYTES = 12L shl 20

        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "avif", "bmp")
        val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "ts")
        val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")

        fun maxSizeBytes(): Int =
            minOf(32L shl 20, Runtime.getRuntime().maxMemory() / 10).toInt()
    }
}

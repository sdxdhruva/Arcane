package dev.arcane.data

import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * The bridge between Android's media extractors and a vault file.
 *
 * `MediaMetadataRetriever` wants random access and offers no stream overload; the one overload that takes a
 * descriptor or a path is exactly what this app cannot give it, because that would mean a plaintext temp file.
 * Since API 23 it also accepts a [MediaDataSource], which is this: a seekable view built on the same
 * decrypting [DataSource.Factory] playback already uses.
 *
 * Reads are serialised because the native side may call [readAt] from more than one thread, and one open
 * connection is kept per contiguous run so extracting a frame does not re-open (and re-derive the file key)
 * for every 64 KiB the parser asks for.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VaultMediaDataSource(
    private val factory: DataSource.Factory,
    private val uri: Uri,
    private val length: Long,
) : MediaDataSource() {

    private val lock = Any()
    private var source: DataSource? = null
    private var windowStart = -1L
    private var windowEnd = -1L

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = synchronized(lock) {
        if (size <= 0) return 0
        if (position < 0 || position >= length) return -1
        try {
            if (source == null || position < windowStart || position >= windowEnd) openAt(position, size)
            val open = source ?: return -1
            val want = minOf(size.toLong(), windowEnd - position, length - position).toInt()
            var placed = 0
            while (placed < want) {
                val read = open.read(buffer, offset + placed, want - placed)
                if (read <= 0) break
                placed += read
            }
            if (placed == 0) -1 else placed
        } catch (failure: IOException) {
            closeQuietly()
            -1
        }
    }

    override fun getSize(): Long = length

    override fun close() {
        synchronized(lock) { closeQuietly() }
    }

    private fun openAt(position: Long, requested: Int) {
        closeQuietly()
        val span = minOf(length - position, maxOf(requested.toLong(), READAHEAD_BYTES))
        val opened = factory.createDataSource()
        opened.open(
            DataSpec.Builder()
                .setUri(uri)
                .setPosition(position)
                .setLength(span)
                .build()
        )
        source = opened
        windowStart = position
        windowEnd = position + span
    }

    private fun closeQuietly() {
        runCatching { source?.close() }
        source = null
        windowStart = -1L
        windowEnd = -1L
    }

    private companion object {
        /** Four megabytes of readahead: handles 4K and high-bitrate media without stalling or over-decrypting. */
        const val READAHEAD_BYTES = 4L shl 20
    }
}

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
 * Reads are serialised because the native side may call [readAt] from more than one thread. To support
 * random non-sequential seeking required by metadata parsers (e.g. jumping between atom headers and indices),
 * the data source opens at the requested position whenever a seek occurs, and streams sequentially
 * when reads are contiguous.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VaultMediaDataSource(
    private val factory: DataSource.Factory,
    private val uri: Uri,
    private val length: Long,
) : MediaDataSource() {

    private val lock = Any()
    private var source: DataSource? = null
    private var currentPos = -1L

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = synchronized(lock) {
        if (size <= 0) return 0
        if (position < 0 || position >= length) return -1
        try {
            if (source == null || currentPos != position) {
                openAt(position)
            }
            val open = source ?: return -1
            val want = minOf(size.toLong(), length - position).toInt()
            var placed = 0
            while (placed < want) {
                val read = open.read(buffer, offset + placed, want - placed)
                if (read <= 0) break
                placed += read
            }
            if (placed > 0) {
                currentPos = position + placed
                placed
            } else {
                -1
            }
        } catch (failure: IOException) {
            closeQuietly()
            -1
        }
    }

    override fun getSize(): Long = length

    override fun close() {
        synchronized(lock) { closeQuietly() }
    }

    private fun openAt(position: Long) {
        closeQuietly()
        val opened = factory.createDataSource()
        opened.open(
            DataSpec.Builder()
                .setUri(uri)
                .setPosition(position)
                .build()
        )
        source = opened
        currentPos = position
    }

    private fun closeQuietly() {
        runCatching { source?.close() }
        source = null
        currentPos = -1L
    }
}

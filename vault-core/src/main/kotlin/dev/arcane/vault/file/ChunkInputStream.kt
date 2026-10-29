package dev.arcane.vault.file

import java.io.IOException
import java.io.InputStream

/**
 * The vault's content as an [InputStream], for everything that is not Media3: image decoders, text and
 * HTML previews, the PDF writer that needs a real file. Reads go through [ChunkCache], so a stream started
 * right after a video seek costs nothing for the bytes already in RAM.
 *
 * Closing wipes the [ChunkMap] it was built from, which is the whole reason this class exists instead of
 * callers just holding the handle: a viewer that forgets to close leaks a file key into the heap, and the
 * stream is the thing the viewer definitely closes.
 *
 * Byte-at-a-time [read] is not pathological here — the first byte of a chunk decrypts that chunk and every
 * later byte is a memory copy — but it is still one map lookup per byte, so wrap in `BufferedInputStream`
 * for a decoder that insists on reading a file a thousand bytes at a time.
 */
class ChunkInputStream(
    private val cache: ChunkCache,
    private val map: ChunkMap,
) : InputStream() {

    private var position = 0L
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
    }

    override fun read(target: ByteArray, off: Int, len: Int): Int {
        checkOpen()
        require(off >= 0 && len >= 0 && off + len <= target.size) { "bad slice $off+$len into ${target.size}" }
        if (len == 0) return 0
        if (position >= map.plainSize) return -1
        val wanted = minOf(len.toLong(), map.plainSize - position).toInt()
        val placed = cache.readInto(map, target, off, position, wanted)
        position += placed
        return placed
    }

    override fun available(): Int {
        checkOpen()
        return (map.plainSize - position).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    override fun skip(count: Long): Long {
        checkOpen()
        if (count <= 0) return 0
        val moved = minOf(count, map.plainSize - position).coerceAtLeast(0L)
        position += moved
        return moved
    }

    /** Where the stream stands, which is what a viewer needs to map a scroll offset back to a chunk. */
    fun position(): Long = position

    override fun close() {
        if (closed) return
        closed = true
        // The cache keeps its own plaintext — it is shared, and zeroing it here would undo the point of it.
        map.wipe()
    }

    private fun checkOpen() {
        if (closed) throw IOException("stream closed")
    }
}

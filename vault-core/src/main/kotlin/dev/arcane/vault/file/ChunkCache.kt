package dev.arcane.vault.file

import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.format.ChunkGeometry
import dev.arcane.vault.format.VaultFormat

/**
 * A byte-budgeted LRU of decrypted chunks, which is what turns repeated seeking into the thing a premium
 * player is expected to do: scrub back and the bytes are already in RAM, so there is no disk read and no
 * AES pass to wait for.
 *
 * Two rules keep it honest. Cached plaintext never escapes — every read copies out — so an entry cannot
 * be mutated into a wrong picture by a caller. And anything removed is zeroed first, including on
 * [wipe], so the pool of plaintext shrinks back to nothing when the session ends instead of lingering in
 * the heap until the garbage collector feels like it.
 *
 * The budget is bytes of plaintext, not entries: a 512 KiB chunk is the unit, so 16 MiB is 32 chunks of
 * video, which is several seconds of a high-bitrate file and roughly one page of a text document.
 */
class ChunkCache(
    private val blobs: dev.arcane.vault.store.BlobStore,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val reader = ChunkReader(blobs)
    private val entries = LinkedHashMap<Key, ByteArray>(16, 0.75f, true)
    private var totalBytes = 0L

    /** Bumped by [wipe]. A decrypt that straddles a bump must not put its plaintext back in the pool. */
    private var epoch = 0L

    init {
        // Random access is per chunk, so a budget that cannot hold one chunk would evict the chunk being
        // read on every call and answer a seek with a zeroed buffer.
        require(maxBytes >= VaultFormat.CHUNK_PAYLOAD_BYTES.toLong()) {
            "cache budget must hold at least one chunk, got $maxBytes bytes"
        }
    }

    var hits = 0L
        private set
    var misses = 0L
        private set

    val bytesCached: Long get() = synchronized(this) { totalBytes }
    val size: Int get() = synchronized(this) { entries.size }

    /** [targetOffset] onward in [target] receives the plaintext of `[fileOffset, fileOffset + count)`. */
    fun readInto(map: ChunkMap, target: ByteArray, targetOffset: Int, fileOffset: Long, count: Int): Int {
        require(count >= 0 && fileOffset >= 0) { "negative range" }
        require(targetOffset >= 0 && targetOffset + count <= target.size) { "target too small" }
        require(fileOffset + count <= map.plainSize) { "range past end of file" }
        var placed = 0
        var cursor = fileOffset
        while (placed < count) {
            val index = ChunkGeometry.chunkIndexOf(cursor)
            val within = ChunkGeometry.offsetWithinChunk(cursor)
            val available = minOf(
                ChunkGeometry.rawPayloadFor(index, map.plainSize, map.chunkCount) - within,
                count - placed,
            )
            val chunk = obtain(map, index)
            chunk.copyInto(target, targetOffset + placed, within, within + available)
            placed += available
            cursor += available
        }
        return placed
    }

    fun readAt(map: ChunkMap, offset: Long, count: Int): ByteArray =
        ByteArray(count).also { readInto(map, it, 0, offset, count) }

    /** Drop one file's entries, e.g. when it is deleted or its listing is replaced. */
    fun evict(fileId: ByteArray) {
        val prefix = fileId.toList()
        synchronized(this) {
            val doomed = entries.keys.filter { it.fileId == prefix }
            doomed.forEach { key -> entries.remove(key)?.let(::retire) }
        }
    }

    /** Lock-time teardown: every byte of plaintext in the heap goes back to zero. */
    fun wipe() {
        synchronized(this) {
            epoch++
            entries.values.forEach(::retire)
            entries.clear()
            totalBytes = 0
        }
    }

    private fun obtain(map: ChunkMap, index: Long): ByteArray {
        val key = Key(map.fileId.toList(), index)
        val hit = synchronized(this) {
            entries[key]?.also { hits++ }
        }
        if (hit != null) return hit
        // Decrypt outside the lock: one reader's seek should not stop another's.
        val startedAt = synchronized(this) { epoch }
        val plain = reader.readChunk(map, index)
        synchronized(this) {
            // A lock that landed during the decrypt already zeroed the pool this chunk belongs to. Caching it
            // now would serve plaintext to whatever reader outlived the key, so it is zeroed and refused.
            if (epoch != startedAt) {
                retire(plain)
                error("the realm was locked while a chunk was being read")
            }
            misses++
            entries.put(key, plain)
            totalBytes += plain.size
            trimToBudget()
        }
        return plain
    }

    /** Cached arrays are shared, so callers copy out of them and never zero them. */
    private fun retire(chunk: ByteArray) = Secrets.wipe(chunk)

    private fun trimToBudget() {
        val iterator = entries.entries.iterator()
        while (totalBytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            totalBytes -= eldest.value.size
            retire(eldest.value)
            iterator.remove()
        }
    }

    private data class Key(val fileId: List<Byte>, val chunkIndex: Long)

    companion object {
        /** Sized for high-bitrate and 4K media: 32 MiB is 64 chunks, ample for smooth continuous playback. */
        const val DEFAULT_MAX_BYTES = 64L * VaultFormat.CHUNK_PAYLOAD_BYTES
    }
}

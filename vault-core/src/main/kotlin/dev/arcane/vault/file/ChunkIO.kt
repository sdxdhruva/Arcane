package dev.arcane.vault.file

import dev.arcane.vault.crypto.ChunkCipher
import dev.arcane.vault.crypto.KeyDerivation
import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.format.ChunkGeometry
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.store.BlobStore
import java.io.InputStream

/**
 * The unlock-time handle on one stored file: enough to address and read its chunks, and nothing more.
 * The name and folder are not here — they live only in the encrypted index — so a [ChunkMap] taken from
 * a memory dump says what a file is made of, never what it is called or where the user put it.
 *
 * Chunk positions are derived from [nameKey] rather than stored, so a file's blobs can be listed,
 * deleted or resumed from the record alone.
 */
class ChunkMap(
    fileId: ByteArray,
    chunkSalt: ByteArray,
    fileKey: ByteArray,
    nameKey: ByteArray,
    val plainSize: Long,
) {
    // Taken by copy, because [wipe] zeroes these arrays in place. Sharing them with the index record that
    // produced this handle would mean a viewer closing corrupts the listing: the salt is what every nonce
    // in the file is built from.
    val fileId: ByteArray = fileId.copyOf()
    val chunkSalt: ByteArray = chunkSalt.copyOf()
    val fileKey: ByteArray = fileKey.copyOf()
    val nameKey: ByteArray = nameKey.copyOf()

    val chunkCount: Long = ChunkGeometry.chunkCountFor(plainSize)

    init {
        require(this.fileId.size == VaultFormat.FILE_ID_BYTES) { "fileId ${this.fileId.size}" }
        require(this.chunkSalt.size == VaultFormat.CHUNK_SALT_BYTES) { "chunkSalt ${this.chunkSalt.size}" }
        require(this.fileKey.size == 32) { "fileKey must be 32 bytes, got ${this.fileKey.size}" }
        require(this.nameKey.size == 32) { "nameKey must be 32 bytes, got ${this.nameKey.size}" }
        require(plainSize >= 0) { "plainSize must not be negative" }
    }

    fun blobId(chunkIndex: Long): ByteArray = KeyDerivation.deriveBlobId(nameKey, fileId, chunkIndex)

    /** Every position this file's chunks occupy, lowest chunk first. */
    fun blobIds(): List<ByteArray> = (0 until chunkCount).map { blobId(it) }

    fun wipe() {
        Secrets.wipe(fileKey)
        Secrets.wipe(nameKey)
        Secrets.wipe(chunkSalt)
    }
}

/** Streams plaintext in, scatters ciphertext blobs out. */
class ChunkWriter(
    private val blobs: BlobStore,
    private val fileId: ByteArray,
    private val chunkSalt: ByteArray,
    private val fileKey: ByteArray,
    private val nameKey: ByteArray,
    private val plainSize: Long,
    resumeAtChunk: Long = 0,
) {
    private val chunkCount = ChunkGeometry.chunkCountFor(plainSize)
    private var nextIndex = resumeAtChunk
    private var written = resumeAtChunk * VaultFormat.CHUNK_PAYLOAD_BYTES
    private val buffer = ByteArray(VaultFormat.CHUNK_PAYLOAD_BYTES)
    private var buffered = 0

    init {
        require(resumeAtChunk in 0 until maxOf(chunkCount, 1)) {
            "cannot resume at chunk $resumeAtChunk of $chunkCount"
        }
        require(written <= plainSize) { "resume point is past the declared size" }
    }

    /** Chunks already committed, which is what gets written into the journal as progress. */
    val chunksCommitted: Long get() = nextIndex

    fun write(source: ByteArray, off: Int = 0, len: Int = source.size) {
        require(off >= 0 && len >= 0 && off + len <= source.size) { "bad slice" }
        require(written + len <= plainSize) { "wrote past the declared size" }
        var remaining = len
        var cursor = off
        while (remaining > 0) {
            val take = minOf(remaining, VaultFormat.CHUNK_PAYLOAD_BYTES - buffered)
            source.copyInto(buffer, buffered, cursor, cursor + take)
            buffered += take
            cursor += take
            remaining -= take
            written += take
            if (buffered == VaultFormat.CHUNK_PAYLOAD_BYTES) flushFilledChunk()
        }
    }

    /** Copy-keeping import path: never holds more than one chunk of plaintext at a time. */
    fun writeAll(source: InputStream) {
        val slab = ByteArray(VaultFormat.CHUNK_PAYLOAD_BYTES)
        while (true) {
            val read = source.read(slab)
            if (read < 0) break
            write(slab, 0, read)
        }
    }

    /**
     * Flushes the tail and returns the handle to hand to the index. Every byte promised by [plainSize]
     * must have arrived, including an empty file's single all-padding chunk: on disk "empty" and "absent"
     * must not be the same thing.
     */
    fun finish(): ChunkMap {
        check(written == plainSize) { "declared $plainSize bytes but wrote $written" }
        if (nextIndex < chunkCount) flushFilledChunk()
        check(nextIndex == chunkCount) { "produced $nextIndex chunks, format expects $chunkCount" }
        return ChunkMap(fileId, chunkSalt, fileKey, nameKey, plainSize)
    }

    private fun flushFilledChunk() {
        val raw = ChunkGeometry.rawPayloadFor(nextIndex, plainSize, chunkCount)
        check(raw == buffered) { "chunk $nextIndex holds $buffered bytes, geometry expects $raw" }
        val padded = ByteArray(ChunkGeometry.paddedPayloadFor(raw))
        buffer.copyInto(padded, 0, 0, raw)
        blobs.write(
            KeyDerivation.deriveBlobId(nameKey, fileId, nextIndex),
            ChunkCipher.encryptChunk(fileKey, padded, chunkSalt, fileId, nextIndex, chunkCount),
        )
        Secrets.wipe(padded)
        nextIndex++
        buffered = 0
    }
}

/** Random-access reads: a seek decrypts the chunk under the scrubber and nothing else. */
class ChunkReader(private val blobs: BlobStore) {

    /** The exact plaintext of one chunk, authentication-checked. */
    fun readChunk(map: ChunkMap, index: Long): ByteArray {
        require(index in 0 until map.chunkCount) { "chunk $index of ${map.chunkCount}" }
        val raw = ChunkGeometry.rawPayloadFor(index, map.plainSize, map.chunkCount)
        val blobId = map.blobId(index)
        val cipherSize = blobs.size(blobId).toInt()
        val padded = ChunkCipher.decryptChunk(
            map.fileKey,
            blobs.read(blobId, 0, cipherSize),
            map.chunkSalt,
            map.fileId,
            index,
            map.chunkCount,
        )
        return if (raw == padded.size) padded else padded.copyOf(raw)
    }

    fun readAt(map: ChunkMap, offset: Long, count: Int): ByteArray {
        require(offset >= 0 && count >= 0) { "negative range" }
        require(offset + count <= map.plainSize) { "range past end of file" }
        if (count == 0) return ByteArray(0)
        val out = ByteArray(count)
        var placed = 0
        var cursor = offset
        while (placed < count) {
            val index = ChunkGeometry.chunkIndexOf(cursor)
            val within = ChunkGeometry.offsetWithinChunk(cursor)
            val available = minOf(
                ChunkGeometry.rawPayloadFor(index, map.plainSize, map.chunkCount) - within,
                count - placed,
            )
            readChunk(map, index).copyInto(out, placed, within, within + available)
            placed += available
            cursor += available
        }
        return out
    }

    /** Which chunks a plaintext range spans — the input to prefetch and to the seek-cost proof. */
    fun chunksTouched(map: ChunkMap, offset: Long, count: Int): LongRange {
        require(offset >= 0 && count >= 0) { "negative range" }
        require(offset + count <= map.plainSize) { "range past end of file" }
        if (count == 0) return LongRange(1, 0)
        return ChunkGeometry.chunkIndexOf(offset)..ChunkGeometry.chunkIndexOf(offset + count - 1)
    }
}

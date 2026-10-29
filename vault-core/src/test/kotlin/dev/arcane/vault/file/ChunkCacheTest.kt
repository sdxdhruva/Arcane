package dev.arcane.vault.file

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.arcane.vault.MeasuringBlobStore
import dev.arcane.vault.crypto.KeyDerivation
import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.pseudoRandom
import dev.arcane.vault.store.FileBlobStore
import dev.arcane.vault.store.VaultLayout
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

private const val CHUNK = VaultFormat.CHUNK_PAYLOAD_BYTES

class ChunkCacheTest {

    @TempDir
    lateinit var root: File

    private val layout by lazy { VaultLayout(root) }
    private val disk by lazy { FileBlobStore(layout) }
    private val master by lazy { Secrets.randomBytes(32) }
    private val realm by lazy { KeyDerivation.deriveRealmId(master, Secrets.randomBytes(16)) }

    /** The same content a viewer would ask for, written once and read many times below. */
    private fun storeFile(idSeed: Int, chunks: Int): ChunkMap {
        val fileId = pseudoRandom(VaultFormat.FILE_ID_BYTES, idSeed)
        val size = chunks * CHUNK
        return ChunkWriter(
            blobs = disk,
            fileId = fileId,
            chunkSalt = Secrets.randomBytes(VaultFormat.CHUNK_SALT_BYTES),
            fileKey = KeyDerivation.deriveFileKey(master, fileId),
            nameKey = KeyDerivation.deriveBlobNameKey(master, realm),
            plainSize = size.toLong(),
        ).apply { writeAll(ByteArrayInputStream(pseudoRandom(size, seed = idSeed))) }.finish()
    }

    @Test
    fun `a repeated range costs one decrypt instead of two`() {
        val map = storeFile(idSeed = 1, chunks = 4)
        val measured = MeasuringBlobStore(disk)
        val cache = ChunkCache(measured, maxBytes = 8L * CHUNK)

        val first = cache.readAt(map, 0, 1024)
        val readsAfterFirst = measured.reads
        val again = cache.readAt(map, 0, 1024)

        assertThat(again).isEqualTo(first)
        assertWithMessage("the second read touched no blob at all")
            .that(measured.reads)
            .isEqualTo(readsAfterFirst)
        assertWithMessage("hits ${cache.hits}, misses ${cache.misses}")
            .that(cache.hits to cache.misses)
            .isEqualTo(1L to 1L)
    }

    @Test
    fun `scrubbing back over a range the player already passed is free`() {
        val map = storeFile(idSeed = 2, chunks = 8)
        val measured = MeasuringBlobStore(disk)
        val cache = ChunkCache(measured, maxBytes = 8L * CHUNK)
        cache.readAt(map, 0, 6 * CHUNK)
        measured.reset()

        val back = cache.readAt(map, CHUNK.toLong(), 3 * CHUNK)

        assertWithMessage("a rewound range is served from RAM")
            .that(measured.reads)
            .isEqualTo(0)
        assertThat(back).isEqualTo(pseudoRandom(8 * CHUNK, seed = 2).copyOfRange(CHUNK, 4 * CHUNK))
    }

    @Test
    fun `the budget is counted in bytes and the least useful chunk goes first`() {
        val map = storeFile(idSeed = 3, chunks = 5)
        val measured = MeasuringBlobStore(disk)
        val cache = ChunkCache(measured, maxBytes = 2L * CHUNK)

        cache.readAt(map, 0, CHUNK)
        cache.readAt(map, CHUNK.toLong(), CHUNK)
        assertThat(cache.size).isEqualTo(2)
        assertThat(cache.bytesCached).isEqualTo(2L * CHUNK)

        cache.readAt(map, 2L * CHUNK, CHUNK)

        assertWithMessage("the third chunk pushed the oldest one out")
            .that(cache.size)
            .isEqualTo(2)
        val beforeReread = measured.reads
        cache.readAt(map, 0, 64)
        assertWithMessage("an evicted chunk is decrypted again rather than answered with its zeros")
            .that(measured.reads)
            .isEqualTo(beforeReread + 1)
        assertThat(cache.readAt(map, 0, 64))
            .isEqualTo(pseudoRandom(5 * CHUNK, seed = 3).copyOfRange(0, 64))
    }

    @Test
    fun `wiping the cache makes every later read pay again`() {
        val map = storeFile(idSeed = 5, chunks = 2)
        val measured = MeasuringBlobStore(disk)
        val cache = ChunkCache(measured, maxBytes = 4L * CHUNK)
        cache.readAt(map, 0, CHUNK)
        val afterWarm = measured.reads

        cache.wipe()

        assertThat(cache.bytesCached).isEqualTo(0L)
        assertThat(cache.size).isEqualTo(0)
        assertThat(cache.readAt(map, 0, CHUNK)).isEqualTo(pseudoRandom(2 * CHUNK, seed = 5).copyOfRange(0, CHUNK))
        assertWithMessage("the session's plaintext had to be rebuilt from the blob pool")
            .that(measured.reads)
            .isEqualTo(afterWarm + 1)
    }

    @Test
    fun `evicting one file leaves another file's chunks warm`() {
        val fileA = storeFile(idSeed = 6, chunks = 2)
        val fileB = storeFile(idSeed = 7, chunks = 2)
        val measured = MeasuringBlobStore(disk)
        val cache = ChunkCache(measured, maxBytes = 8L * CHUNK)
        cache.readAt(fileA, 0, CHUNK)
        cache.readAt(fileB, 0, CHUNK)
        val before = measured.reads

        cache.evict(fileA.fileId)
        cache.readAt(fileB, 0, CHUNK)
        assertWithMessage("file B was untouched by file A's eviction")
            .that(measured.reads)
            .isEqualTo(before)
        cache.readAt(fileA, 0, CHUNK)
        assertWithMessage("file A had to be decrypted again")
            .that(measured.reads)
            .isEqualTo(before + 1)
    }

    @Test
    fun `scribbling on a result does not poison the cache`() {
        val map = storeFile(idSeed = 8, chunks = 2)
        val cache = ChunkCache(disk, maxBytes = 4L * CHUNK)
        val expected = pseudoRandom(2 * CHUNK, seed = 8).copyOfRange(0, 128)

        val handed = cache.readAt(map, 0, 128)
        handed.fill(0)

        assertWithMessage("what a viewer holds is its own copy")
            .that(cache.readAt(map, 0, 128))
            .isEqualTo(expected)
    }

    @Test
    fun `reads that straddle a chunk boundary come out contiguous`() {
        val map = storeFile(idSeed = 9, chunks = 3)
        val cache = ChunkCache(disk, maxBytes = 8L * CHUNK)
        val offset = CHUNK - 40

        val slice = cache.readAt(map, offset.toLong(), 80)

        assertThat(slice).isEqualTo(pseudoRandom(3 * CHUNK, seed = 9).copyOfRange(offset, offset + 80))
    }

    @Test
    fun `a budget below one chunk is refused instead of answering a seek with zeros`() {
        assertThrows<IllegalArgumentException> { ChunkCache(disk, maxBytes = 1024L) }
    }

    @Test
    fun `asking past the end of the file is refused`() {
        val map = storeFile(idSeed = 10, chunks = 1)
        val cache = ChunkCache(disk, maxBytes = 4L * CHUNK)

        assertThrows<IllegalArgumentException> { cache.readAt(map, 0, CHUNK + 1) }
        assertThrows<IllegalArgumentException> { cache.readAt(map, -1, 16) }
    }
}

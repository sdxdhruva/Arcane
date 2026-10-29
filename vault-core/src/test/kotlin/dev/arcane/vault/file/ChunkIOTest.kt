package dev.arcane.vault.file

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.arcane.vault.MeasuringBlobStore
import dev.arcane.vault.crypto.KeyDerivation
import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.crypto.VaultAuthenticationException
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.pseudoRandom
import dev.arcane.vault.store.BlobNotFoundException
import dev.arcane.vault.store.BlobStore
import dev.arcane.vault.store.FileBlobStore
import dev.arcane.vault.store.VaultLayout
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

private val MASTER = Secrets.randomBytes(32)
private val REALM = Secrets.randomBytes(VaultFormat.BLOB_ID_BYTES)
private val NAME_KEY = KeyDerivation.deriveBlobNameKey(MASTER, REALM)

class ChunkIOTest {

    @TempDir
    lateinit var root: File

    private lateinit var blobs: BlobStore

    private fun store(): BlobStore {
        if (!this::blobs.isInitialized) blobs = FileBlobStore(VaultLayout(root))
        return blobs
    }

    private fun coords(seed: Int = 0): Triple<ByteArray, ByteArray, ByteArray> {
        val fileId = if (seed == 0) Secrets.randomBytes(VaultFormat.FILE_ID_BYTES) else pseudoRandom(16, seed)
        val chunkSalt = Secrets.randomBytes(VaultFormat.CHUNK_SALT_BYTES)
        return Triple(fileId, chunkSalt, KeyDerivation.deriveFileKey(MASTER, fileId))
    }

    private fun writer(
        target: BlobStore = store(),
        fileId: ByteArray,
        chunkSalt: ByteArray,
        fileKey: ByteArray,
        plainSize: Int,
        resumeAtChunk: Long = 0,
    ): ChunkWriter = ChunkWriter(
        blobs = target,
        fileId = fileId,
        chunkSalt = chunkSalt,
        fileKey = fileKey,
        nameKey = NAME_KEY,
        plainSize = plainSize.toLong(),
        resumeAtChunk = resumeAtChunk,
    )

    private fun storeFile(content: ByteArray): ChunkMap {
        val (fileId, chunkSalt, fileKey) = coords()
        val writer = writer(fileId = fileId, chunkSalt = chunkSalt, fileKey = fileKey, plainSize = content.size)
        writer.write(content)
        return writer.finish()
    }

    @Test
    fun `a three megabyte import comes back whole through arbitrary ranges`() {
        val content = pseudoRandom(3 * 1024 * 1024 + 7_777, seed = 3)
        val map = storeFile(content)
        val reader = ChunkReader(store())

        assertWithMessage("chunk count").that(map.chunkCount).isEqualTo(7)
        assertThat(reader.readAt(map, 0, content.size)).isEqualTo(content)
        assertThat(reader.readAt(map, 1_000_000, 999)).isEqualTo(content.copyOfRange(1_000_000, 1_000_999))
        assertThat(reader.readAt(map, 3_141_592, 1)).isEqualTo(content.copyOfRange(3_141_592, 3_141_593))
        assertThat(reader.readAt(map, content.size.toLong(), 0)).isEmpty()
        assertThat(reader.readChunk(map, 6))
            .isEqualTo(content.copyOfRange(6 * VaultFormat.CHUNK_PAYLOAD_BYTES, content.size))
    }

    @Test
    fun `streaming in from a source produces the same chunks as writing one buffer`() {
        val content = pseudoRandom(1_300_000, seed = 5)
        val (fileId, chunkSalt, fileKey) = coords()

        val streamed = writer(fileId = fileId, chunkSalt = chunkSalt, fileKey = fileKey, plainSize = content.size)
            .apply { writeAll(ByteArrayInputStream(content)) }
            .finish()
        val otherId = fileId.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val buffered = writer(
            fileId = otherId,
            chunkSalt = chunkSalt.copyOf(),
            fileKey = KeyDerivation.deriveFileKey(MASTER, otherId),
            plainSize = content.size,
        )
            .apply { write(content) }
            .finish()

        assertWithMessage("same geometry regardless of how the bytes arrive")
            .that(streamed.chunkCount)
            .isEqualTo(buffered.chunkCount)
        assertThat(ChunkReader(store()).readAt(streamed, 0, content.size)).isEqualTo(content)
        assertThat(ChunkReader(store()).readAt(buffered, 0, content.size)).isEqualTo(content)
    }

    @Test
    fun `an empty file is a real chunk, not an absence`() {
        val map = storeFile(ByteArray(0))

        assertWithMessage("empty file still occupies one blob").that(map.blobIds()).hasSize(1)
        assertThat(store().size(map.blobId(0)))
            .isEqualTo((VaultFormat.SIZE_CLASS_BYTES + VaultFormat.GCM_TAG_BYTES).toLong())
        assertThat(ChunkReader(store()).readAt(map, 0, 0)).isEmpty()
    }

    @Test
    fun `seeking near the end of a sixty four mebibyte file touches one chunk and reads one blob`() {
        val size = 64 * 1024 * 1024
        val content = pseudoRandom(size, seed = 64)
        val measured = MeasuringBlobStore(store())
        val (fileId, chunkSalt, fileKey) = coords()
        val map = writer(measured, fileId, chunkSalt, fileKey, size).apply { write(content) }.finish()
        measured.reset()

        val window = ChunkReader(measured).readAt(map, (size - 4096).toLong(), 4096)

        assertWithMessage("a tail seek is one blob read, not a scan")
            .that(measured.reads)
            .isEqualTo(1)
        assertWithMessage("bytes lifted off disk for a 4 KiB seek")
            .that(measured.bytesUploaded)
            .isEqualTo((VaultFormat.CHUNK_PAYLOAD_BYTES + VaultFormat.GCM_TAG_BYTES).toLong())
        assertThat(window).isEqualTo(content.copyOfRange(size - 4096, size))
        assertWithMessage("a 1 MiB window starting mid-file spans three chunks")
            .that(ChunkReader(measured).chunksTouched(map, (size / 2).toLong(), VaultFormat.CHUNK_PAYLOAD_BYTES * 3))
            .isEqualTo(64L..66L)
    }

    @Test
    fun `a blob substituted from another chunk of the same file fails authentication`() {
        val content = pseudoRandom(3 * VaultFormat.CHUNK_PAYLOAD_BYTES, seed = 9)
        val map = storeFile(content)
        // Names are positional, so tampering means moving ciphertext between two of the file's own slots.
        store().write(map.blobId(0), store().read(map.blobId(2)))

        val failure = assertThrows<VaultAuthenticationException> { ChunkReader(store()).readChunk(map, 0) }
        assertWithMessage("the slot says chunk 0 but the ciphertext there carries chunk 2's AAD")
            .that(failure.chunkIndex)
            .isEqualTo(0L)
    }

    @Test
    fun `a chunk from another file does not decrypt here`() {
        val source = storeFile(pseudoRandom(VaultFormat.CHUNK_PAYLOAD_BYTES, seed = 1))
        val (otherId, otherSalt, otherKey) = coords()
        val other = ChunkMap(otherId, otherSalt, otherKey, NAME_KEY, VaultFormat.CHUNK_PAYLOAD_BYTES.toLong())
        store().write(other.blobId(0), store().read(source.blobId(0)))

        assertThrows<VaultAuthenticationException> { ChunkReader(store()).readChunk(other, 0) }
    }

    @Test
    fun `a resumed writer finishes a file whose first chunks are already on disk`() {
        val content = pseudoRandom(4 * VaultFormat.CHUNK_PAYLOAD_BYTES + 1_000, seed = 11)
        val (fileId, chunkSalt, fileKey) = coords(seed = 7)
        val half = content.copyOfRange(0, 2 * VaultFormat.CHUNK_PAYLOAD_BYTES)

        writer(fileId = fileId, chunkSalt = chunkSalt, fileKey = fileKey, plainSize = content.size)
            .apply { write(half) }
            .let { assertWithMessage("progress the journal would record").that(it.chunksCommitted).isEqualTo(2L) }

        val resumed = writer(
            fileId = fileId,
            chunkSalt = chunkSalt,
            fileKey = fileKey,
            plainSize = content.size,
            resumeAtChunk = 2,
        ).apply { writeAll(ByteArrayInputStream(content.copyOfRange(half.size, content.size))) }.finish()

        assertThat(ChunkReader(store()).readAt(resumed, 0, content.size)).isEqualTo(content)
        assertThrows<IllegalArgumentException> {
            writer(fileId = fileId, chunkSalt = chunkSalt, fileKey = fileKey, plainSize = 100, resumeAtChunk = 2)
        }
    }

    @Test
    fun `writing fewer bytes than declared is refused at finish, not silently padded`() {
        val (fileId, chunkSalt, fileKey) = coords()
        val writer = writer(fileId = fileId, chunkSalt = chunkSalt, fileKey = fileKey, plainSize = 1_000_000)
        writer.write(pseudoRandom(999_999, seed = 2))

        assertThrows<IllegalStateException> { writer.finish() }
    }

    @Test
    fun `writing past the declared size is refused`() {
        val (fileId, chunkSalt, fileKey) = coords()
        val writer = writer(fileId = fileId, chunkSalt = chunkSalt, fileKey = fileKey, plainSize = 100)

        assertThrows<IllegalArgumentException> { writer.write(pseudoRandom(101, seed = 4)) }
    }

    @Test
    fun `a chunk listed by the index but absent from the pool is a typed failure`() {
        val map = storeFile(pseudoRandom(1024, seed = 8))
        store().delete(map.blobId(0))

        assertThrows<BlobNotFoundException> { ChunkReader(store()).readChunk(map, 0) }
    }

    @Test
    fun `identical plaintext in two files produces different blobs`() {
        val payload = ByteArray(VaultFormat.CHUNK_PAYLOAD_BYTES) { 0x42 }
        val a = storeFile(payload)
        val b = storeFile(payload)

        assertWithMessage("names are derived per file id, so two files never share a slot")
            .that(a.blobId(0).toList())
            .isNotEqualTo(b.blobId(0).toList())
        assertThat(store().read(a.blobId(0))).isNotEqualTo(store().read(b.blobId(0)))
    }

    @Test
    fun `a file's chunk names are stable across keys and derivable from its id alone`() {
        val (fileId, chunkSalt, fileKey) = coords(seed = 13)
        val first = writer(fileId = fileId, chunkSalt = chunkSalt, fileKey = fileKey, plainSize = 100)
            .apply { write(pseudoRandom(100, seed = 12)) }
            .finish()
        val again = ChunkMap(fileId, chunkSalt, fileKey, NAME_KEY, 100)

        assertWithMessage("resume and GC both depend on names being a pure function of (realm, fileId, index)")
            .that(first.blobIds().map { it.toList() })
            .isEqualTo(again.blobIds().map { it.toList() })
    }
}

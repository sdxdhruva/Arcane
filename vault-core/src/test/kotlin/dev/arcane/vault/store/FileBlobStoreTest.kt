package dev.arcane.vault.store

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.arcane.vault.format.ChunkGeometry
import dev.arcane.vault.format.Hex
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.pseudoRandom
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class FileBlobStoreTest {

    @TempDir
    lateinit var root: File

    private fun store(): FileBlobStore = FileBlobStore(VaultLayout(root))

    @Test
    fun `a blob comes back byte for byte`() {
        val blobs = store()
        val id = ChunkGeometry.newBlobId()
        val payload = pseudoRandom(70_000, seed = 7)

        blobs.write(id, payload)

        assertThat(blobs.read(id)).isEqualTo(payload)
        assertThat(blobs.size(id)).isEqualTo(payload.size.toLong())
        assertThat(blobs.exists(id)).isTrue()
    }

    @Test
    fun `a ranged read returns exactly the slice asked for`() {
        val blobs = store()
        val id = ChunkGeometry.newBlobId()
        val payload = pseudoRandom(200_000, seed = 11)
        blobs.write(id, payload)

        assertThat(blobs.read(id, 65_536, 512)).isEqualTo(payload.copyOfRange(65_536, 66_048))
        assertWithMessage("a range past the end must not silently shorten")
            .that(assertThrows<BlobNotFoundException> { blobs.read(id, 199_000, 5_000) }.message)
            .contains("is not present")
    }

    @Test
    fun `a committed write leaves no temp file behind`() {
        val blobs = store()
        repeat(40) { blobs.write(ChunkGeometry.newBlobId(), pseudoRandom(1_024, seed = it)) }

        val strays = root.walkTopDown().filter { f -> f.isFile && !f.name.endsWith(VaultFormat.BLOB_SUFFIX) }.toList()
        assertWithMessage("every file in the vault must look like a blob, got $strays")
            .that(strays.filterNot { it == root.resolve("vlt/kdf") })
            .isEmpty()
    }

    @Test
    fun `ids spread across all two hundred and fifty six buckets`() {
        val blobs = store()
        val ids = (1..4_000).map { ChunkGeometry.newBlobId() }
        ids.forEach { blobs.write(it, ByteArray(64)) }

        val buckets = root.resolve("vlt").listFiles { f -> f.isDirectory }!!.map { it.name }.toSet()
        assertWithMessage("bucket count").that(buckets).hasSize(VaultFormat.BUCKET_COUNT)
        assertWithMessage("bucket names are two hex chars")
            .that(buckets.all { Regex("^[0-9a-f]{2}$").matches(it) })
            .isTrue()
        assertThat(blobs.blobIds().map { it.toList() }.toSet()).hasSize(ids.size)
    }

    @Test
    fun `delete overwrites then unlinks`() {
        val blobs = store()
        val id = ChunkGeometry.newBlobId()
        val file = VaultLayout(root).blobFile(id)
        blobs.write(id, ByteArray(4096) { 0x41 })
        val originalBytes = file.readBytes()

        assertThat(blobs.delete(id)).isTrue()
        assertThat(file.exists()).isFalse()
        assertWithMessage("deleting a blob that is gone says false")
            .that(blobs.delete(id))
            .isFalse()
        assertWithMessage("the bytes that were there are not readable any more")
            .that(originalBytes.all { it == 0x41.toByte() })
            .isTrue()
    }

    @Test
    fun `reading a missing blob is a typed failure, not an io surprise`() {
        assertThrows<BlobNotFoundException> { store().read(ChunkGeometry.newBlobId()) }
        assertThrows<BlobNotFoundException> { store().read(ChunkGeometry.newBlobId(), 0, 16) }
    }

    @Test
    fun `the directory syncer sees every bucket that was written and is then cleared`() {
        val synced = mutableListOf<String>()
        val blobs = FileBlobStore(VaultLayout(root), DirectorySyncer { synced += it.name })
        (1..300).forEach { blobs.write(ChunkGeometry.newBlobId(), ByteArray(32)) }

        blobs.sync()
        val firstPass = synced.toSet()
        blobs.sync()

        assertWithMessage("sync flushes the buckets touched plus the vault root")
            .that(firstPass.size >= 2)
            .isTrue()
        assertWithMessage("a second sync with nothing new written must not redo work")
            .that(synced.size.toLong())
            .isEqualTo(firstPass.size.toLong())
        assertThat(Hex.lower(ChunkGeometry.newBlobId())).hasLength(32)
    }
}

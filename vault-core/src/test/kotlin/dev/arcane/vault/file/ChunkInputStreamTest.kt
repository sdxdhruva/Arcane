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
import java.io.IOException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

private const val CHUNK = VaultFormat.CHUNK_PAYLOAD_BYTES

class ChunkInputStreamTest {

    @TempDir
    lateinit var root: File

    private val layout by lazy { VaultLayout(root) }
    private val disk by lazy { FileBlobStore(layout) }
    private val master by lazy { Secrets.randomBytes(32) }
    private val realm by lazy { KeyDerivation.deriveRealmId(master, Secrets.randomBytes(16)) }
    private val cache by lazy { ChunkCache(disk, maxBytes = 8L * CHUNK) }

    private fun storeFile(seed: Int, size: Int): Pair<ChunkMap, ByteArray> {
        val plain = pseudoRandom(size, seed = seed)
        val fileId = pseudoRandom(VaultFormat.FILE_ID_BYTES, seed)
        val map = ChunkWriter(
            blobs = disk,
            fileId = fileId,
            chunkSalt = Secrets.randomBytes(VaultFormat.CHUNK_SALT_BYTES),
            fileKey = KeyDerivation.deriveFileKey(master, fileId),
            nameKey = KeyDerivation.deriveBlobNameKey(master, realm),
            plainSize = plain.size.toLong(),
        ).apply { writeAll(ByteArrayInputStream(plain)) }.finish()
        return map to plain
    }

    @Test
    fun `a stream returns the file byte for byte`() {
        val (map, plain) = storeFile(seed = 20, size = 3 * CHUNK)

        val read = ChunkInputStream(cache, map).readBytes()

        assertThat(read).isEqualTo(plain)
    }

    @Test
    fun `one byte at a time reads the same bytes as one buffer at a time`() {
        val (map, plain) = storeFile(seed = 21, size = 2 * CHUNK)
        val stream = ChunkInputStream(cache, map)

        val byteWise = ByteArray(64 * 1024)
        for (i in byteWise.indices) {
            val bit = stream.read()
            assertWithMessage("position $i should still have a byte").that(bit).isNotEqualTo(-1)
            byteWise[i] = bit.toByte()
        }
        assertThat(byteWise).isEqualTo(plain.copyOfRange(0, byteWise.size))
        assertThat(stream.readBytes()).isEqualTo(plain.copyOfRange(byteWise.size, plain.size))
        assertWithMessage("and the stream knows when it has arrived")
            .that(stream.read())
            .isEqualTo(-1)
    }

    @Test
    fun `skipping forward does not pay for the bytes it passed over`() {
        val (map, plain) = storeFile(seed = 22, size = 4 * CHUNK)
        val measured = MeasuringBlobStore(disk)
        val stream = ChunkInputStream(ChunkCache(measured, maxBytes = 4L * CHUNK), map)

        assertThat(stream.skip(3L * CHUNK)).isEqualTo(3L * CHUNK)
        measured.reset()

        val tail = stream.readBytes()

        assertWithMessage("three chunks were walked past, so three decrypts were never needed")
            .that(measured.reads)
            .isEqualTo(1)
        assertThat(tail).isEqualTo(plain.copyOfRange(3 * CHUNK, 4 * CHUNK))
    }

    @Test
    fun `available counts the bytes still to come`() {
        val (map, plain) = storeFile(seed = 23, size = 2 * CHUNK)
        val stream = ChunkInputStream(cache, map)

        assertThat(stream.available()).isEqualTo(plain.size)
        stream.skip(1000)
        assertThat(stream.available()).isEqualTo(plain.size - 1000)
        assertThat(stream.position()).isEqualTo(1000L)
        assertThat(stream.skip(Long.MAX_VALUE)).isEqualTo((plain.size - 1000).toLong())
        assertThat(stream.available()).isEqualTo(0)
    }

    @Test
    fun `closing takes the file key with it`() {
        val (map, plain) = storeFile(seed = 24, size = CHUNK)
        val stream = ChunkInputStream(cache, map)
        assertThat(stream.readBytes()).isEqualTo(plain)

        stream.close()

        assertWithMessage("a viewer that closes leaves no usable key material behind")
            .that(map.fileKey.all { it == 0.toByte() })
            .isTrue()
        assertThrows<IOException> { stream.read(ByteArray(16), 0, 16) }
        assertThrows<IOException> { stream.available() }
    }

    @Test
    fun `an empty file is a stream that reads nothing`() {
        val (map, plain) = storeFile(seed = 25, size = 0)
        val stream = ChunkInputStream(cache, map)

        assertThat(plain).isEmpty()
        assertThat(stream.available()).isEqualTo(0)
        assertThat(stream.read()).isEqualTo(-1)
        assertThat(stream.read(ByteArray(8), 0, 8)).isEqualTo(-1)
        assertWithMessage("an empty file still occupies one chunk on disk, so it is not indistinguishable from absent")
            .that(disk.exists(map.blobId(0)))
            .isTrue()
    }

    @Test
    fun `closing twice is not an error`() {
        val (map, _) = storeFile(seed = 26, size = CHUNK)
        val stream = ChunkInputStream(cache, map)

        stream.close()
        stream.close()
    }
}

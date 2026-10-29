package dev.arcane.vault.format

import com.google.common.truth.Truth.assertThat
import java.security.SecureRandom
import org.junit.jupiter.api.Test

private const val KIB = 1024L
private const val MIB = 1024L * KIB
private const val GIB = 1024L * MIB
private val CHUNK = VaultFormat.CHUNK_PAYLOAD_BYTES.toLong()
private val TAG = VaultFormat.GCM_TAG_BYTES.toLong()
private val CLASS = VaultFormat.SIZE_CLASS_BYTES.toLong()

class ChunkGeometryTest {

    @Test
    fun `one gibibyte is exactly 2048 chunks and the only overhead is the tags`() {
        val size = 1 * GIB
        val chunks = ChunkGeometry.chunkCountFor(size)

        assertThat(chunks).isEqualTo(2048L)
        // 1 GiB divides evenly by the chunk size, so the tail needs no padding at all.
        assertThat(ChunkGeometry.cipherSizeFor(size) - size).isEqualTo(chunks * TAG)
        assertThat(chunks * TAG).isEqualTo(32 * KIB)
        // One blob id per chunk in the index.
        assertThat(chunks * VaultFormat.BLOB_ID_BYTES).isEqualTo(32 * KIB)
    }

    @Test
    fun `gigabyte scale is two thousand and forty eight chunks per gibibyte`() {
        assertThat(ChunkGeometry.chunkCountFor(4 * GIB)).isEqualTo(8192L)
        assertThat(ChunkGeometry.chunkCountFor(100 * MIB)).isEqualTo(200L)
        // Decimal gigabytes, which is what a camera app reports: 4 GB = 7630 chunks.
        assertThat(ChunkGeometry.chunkCountFor(4_000_000_000L)).isEqualTo(7630L)
    }

    @Test
    fun `an empty file is a real chunk so absent and empty stay distinct`() {
        assertThat(ChunkGeometry.chunkCountFor(0)).isEqualTo(1L)
        assertThat(ChunkGeometry.rawPayloadFor(0, 0, 1)).isEqualTo(0)
        assertThat(ChunkGeometry.paddedPayloadFor(0)).isEqualTo(VaultFormat.SIZE_CLASS_BYTES)
    }

    @Test
    fun `sizes below one size class are indistinguishable on disk`() {
        val lengths = listOf(0L, 1L, 500L, 32L * KIB - 1, 32L * KIB)
        val onDisk = lengths.map(ChunkGeometry::cipherSizeFor).distinct()

        // Everything up to one full class collapses to the same ciphertext length.
        assertThat(onDisk).containsExactly(CLASS + TAG)
    }

    @Test
    fun `chunk boundaries do not produce an off-by-one chunk count`() {
        assertThat(ChunkGeometry.chunkCountFor(CHUNK - 1)).isEqualTo(1L)
        assertThat(ChunkGeometry.chunkCountFor(CHUNK)).isEqualTo(1L)
        assertThat(ChunkGeometry.chunkCountFor(CHUNK + 1)).isEqualTo(2L)

        assertThat(ChunkGeometry.rawPayloadFor(0, CHUNK, 1)).isEqualTo(VaultFormat.CHUNK_PAYLOAD_BYTES)
        assertThat(ChunkGeometry.rawPayloadFor(1, CHUNK + 1, 2)).isEqualTo(1)
        assertThat(ChunkGeometry.paddedPayloadFor(1)).isEqualTo(VaultFormat.SIZE_CLASS_BYTES)
        assertThat(ChunkGeometry.cipherSizeFor(CHUNK + 1)).isEqualTo(CHUNK + TAG + CLASS + TAG)
    }

    @Test
    fun `every offset maps back to exactly one chunk and inner position`() {
        val random = SecureRandom()
        val fileSize = 3 * GIB + 777

        repeat(20_000) {
            val offset = (random.nextLong(fileSize))
            val chunk = ChunkGeometry.chunkIndexOf(offset)
            val inner = ChunkGeometry.offsetWithinChunk(offset)

            assertThat(chunk * CHUNK + inner).isEqualTo(offset)
            assertThat(inner).isLessThan(VaultFormat.CHUNK_PAYLOAD_BYTES)
            assertThat(chunk).isLessThan(ChunkGeometry.chunkCountFor(fileSize))
        }
    }

    @Test
    fun `nonces never repeat within a file and salts separate files`() {
        val random = SecureRandom()
        val salt = ByteArray(VaultFormat.CHUNK_SALT_BYTES).also(random::nextBytes)
        val nonces = (0L until 5000L).map { ChunkGeometry.nonceFor(salt, it) }

        assertThat(nonces.distinct().size).isEqualTo(nonces.size)
        assertThat(nonces.first().size).isEqualTo(VaultFormat.NONCE_BYTES)
        // Chunk index is the trailing 8 bytes, big endian, so ordering is visible only to the owner.
        assertThat(Hex.lower(nonces[1])).endsWith("0000000000000001")
        assertThat(Hex.lower(nonces[256])).endsWith("0000000000000100")
    }

    @Test
    fun `blob names carry nothing but the id and spread across all buckets`() {
        val random = SecureRandom()
        val ids = List(20_000) { ChunkGeometry.newBlobId(random) }
        val names = ids.map(ChunkGeometry::blobName)
        val buckets = ids.map(ChunkGeometry::bucketName).distinct()

        assertThat(names.distinct().size).isEqualTo(ids.size)
        assertThat(names.first().length).isEqualTo(VaultFormat.BLOB_ID_BYTES * 2 + VaultFormat.BLOB_SUFFIX.length)
        assertThat(buckets).hasSize(VaultFormat.BUCKET_COUNT)
        assertThat(buckets.all { it.length == 2 && it.all(Char::isDigit) || it.matches(Regex("[0-9a-f]{2}")) }).isTrue()
    }

    @Test
    fun `hex is a faithful round trip and the constant time compare still discriminates`() {
        val random = SecureRandom()
        repeat(500) {
            val bytes = ByteArray(32).also(random::nextBytes)
            assertThat(Hex.decode(Hex.lower(bytes))).isEqualTo(bytes)
        }
        val a = ByteArray(16) { it.toByte() }
        val b = a.copyOf().also { it[15] = 99 }
        assertThat(Hex.equalsConstantTime(a, a.copyOf())).isTrue()
        assertThat(Hex.equalsConstantTime(a, b)).isFalse()
        assertThat(Hex.equalsConstantTime(a, a.copyOfRange(0, 15))).isFalse()
    }
}

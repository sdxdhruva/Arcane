package dev.arcane.vault.scale

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.arcane.vault.crypto.KdfParams
import dev.arcane.vault.crypto.RealmDefaults
import dev.arcane.vault.format.ChunkGeometry
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.pseudoRandom
import dev.arcane.vault.realm.RealmHeaderStore
import dev.arcane.vault.realm.RealmOpener
import dev.arcane.vault.realm.VaultRealm
import dev.arcane.vault.realm.WriteJournal
import dev.arcane.vault.store.FileBlobStore
import dev.arcane.vault.store.VaultLayout
import java.io.File
import java.io.InputStream
import java.util.Random
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir

private const val GIB = 1024L * 1024 * 1024
private const val CHUNK = VaultFormat.CHUNK_PAYLOAD_BYTES
private const val CIPHER_CHUNK = CHUNK + VaultFormat.GCM_TAG_BYTES
private const val BLOCK = 64 * 1024

/**
 * The gigabyte case, measured rather than claimed.
 *
 * PLAN.md §3.1 puts numbers on the page — 2048 chunks to the gibibyte, sixteen tag bytes each, an index that
 * costs the same for a 4 GiB video as for a 4 KiB note — and a plan is only evidence until a file the size of
 * a film has actually been through the format. This test imports exactly one, reads every byte back, and
 * times both.
 *
 * It is switched on by hand because it is minutes of work and a gigabyte of disk:
 * `./gradlew :vault-core:test -Darcane.scale=on`.
 */
@EnabledIfSystemProperty(named = "arcane.scale", matches = "on")
class LargeFileRoundTripTest {

    @TempDir
    lateinit var root: File

    @Test
    fun `a gigabyte lands on disk at the size the geometry predicts and comes back byte for byte`() {
        val layout = VaultLayout(root)
        val blobs = FileBlobStore(layout)
        val opener = RealmOpener(blobs, RealmHeaderStore(layout))
        val journal = WriteJournal(blobs)
        val tag = "passphrase-for-scale"
        val vault = VaultRealm(
            opener.initialise(0, tag.toByteArray(), KdfParams(pseudoRandom(16, tag.hashCode()), RealmDefaults.MEMORY_KIB / 2, 1, 1)),
            blobs,
            journal = journal,
        )

        val importStart = System.nanoTime()
        val record = vault.import(ScaleSource(GIB), "feature.mkv", null, "video/x-matroska", GIB)
        val importMillis = (System.nanoTime() - importStart) / 1_000_000

        assertThat(record.plainSize).isEqualTo(GIB)
        assertThat(vault.index.files.single()).isEqualTo(record)

        val expectedCipher = ChunkGeometry.cipherSizeFor(GIB)
        assertWithMessage("the tag is the only per-chunk cost, so a whole file of full chunks is size + 16 x chunks")
            .that(expectedCipher)
            .isEqualTo(GIB + (GIB / CHUNK) * VaultFormat.GCM_TAG_BYTES)

        val onDisk = root.walkTopDown().filter(File::isFile).map(File::length).toList()
        val wholeChunks = onDisk.count { it == CIPHER_CHUNK.toLong() }
        assertWithMessage("1 GiB is exactly 2048 full chunks, and nothing else on disk is that size")
            .that(wholeChunks)
            .isEqualTo(2048)

        val totalBytes = onDisk.sum()
        val overhead = totalBytes - expectedCipher
        assertThat(overhead).isAtLeast(0L)

        val map = vault.open(record.fileId)!!
        try {
            assertThat(map.chunkCount).isEqualTo(2048L)

            val readStart = System.nanoTime()
            var offset = 0L
            while (offset < GIB) {
                val length = minOf(CHUNK.toLong(), GIB - offset).toInt()
                val got = vault.reader.readAt(map, offset, length)
                assertThat(got).hasLength(length)
                var within = 0
                while (within < length) {
                    val take = minOf(BLOCK, length - within)
                    val expected = blockBytes((offset + within) / BLOCK)
                    assertThat(got.copyOfRange(within, within + take)).isEqualTo(expected.copyOfRange(0, take))
                    within += take
                }
                offset += length
            }
            val readMillis = (System.nanoTime() - readStart) / 1_000_000

            // One chunk per seek is the claim the player rests on: a skip must cost a blob, never a file.
            val seek = Random(20261028L)
            var slowest = 0L
            var total = 0L
            repeat(32) {
                val at = seek.nextLong(GIB - CHUNK)
                val start = System.nanoTime()
                assertThat(vault.reader.readAt(map, at, 4096)).hasLength(4096)
                val millis = (System.nanoTime() - start) / 1_000_000
                total += millis
                slowest = maxOf(slowest, millis)
            }
            val averageSeek = total / 32

            println(
                """
                |--- 1 GiB round trip (host JVM, measured) -------------------------
                |import            ${importMillis} ms  (${rate(importMillis)} MiB/s)
                |full decrypt      ${readMillis} ms  (${rate(readMillis)} MiB/s)
                |seek x32          avg ${averageSeek} ms, worst ${slowest} ms
                |chunks            ${wholeChunks} of ${ChunkGeometry.chunkCountFor(GIB)} expected
                |ciphertext        $expectedCipher bytes (plaintext + ${expectedCipher - GIB} of tags)
                |everything else   $overhead bytes: index, header slots, journal, none of it per chunk
                |-------------------------------------------------------------------
                """.trimMargin()
            )

            assertWithMessage("a seek is one blob and one decrypt, not a scan")
                .that(averageSeek)
                .isAtMost(250L)
        } finally {
            map.wipe()
        }
    }

    /** MiB per second; the multiply leads so a slow run still lands on a real number instead of zero. */
    private fun rate(millis: Long): Long = if (millis <= 0) 0 else GIB / 1024 / 1024 * 1000 / millis
}

/**
 * A gigabyte of content that fits in RAM neither as a source nor as a check.
 *
 * The filler is seekable by construction — every 64 KiB block is a function of its own index — so the same
 * bytes can be regenerated for one range without holding the file, which is the only way to verify a gigabyte
 * inside a test heap.
 */
private class ScaleSource(private val total: Long) : InputStream() {

    private var block = ByteArray(BLOCK)
    private var blockIndex = -1L
    private var written = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(target: ByteArray, off: Int, len: Int): Int {
        if (written >= total) return -1
        val wanted = written / BLOCK
        if (wanted != blockIndex) {
            block = blockBytes(wanted)
            blockIndex = wanted
        }
        val within = (written % BLOCK).toInt()
        val take = minOf(BLOCK - within, len, (total - written).toInt())
        if (take <= 0) return -1
        block.copyInto(target, off, within, within + take)
        written += take
        return take
    }
}

private fun blockBytes(index: Long): ByteArray {
    val bytes = ByteArray(BLOCK)
    var state = (index + 1) * 0x9E3779B97F4A7C15u.toLong()
    for (i in bytes.indices) {
        state = state * 6364136223846793005L + 1442695040888963407L
        bytes[i] = (state ushr 33).toByte()
    }
    return bytes
}

package dev.arcane.vault.crypto

import dev.arcane.vault.format.ChunkGeometry
import dev.arcane.vault.format.VaultFormat
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Argon2id parameters as recorded in the public `kdf` header. Kept as data so a vault can be opened
 * on a different device or a later app version without re-guessing the cost.
 */
data class KdfParams(
    val salt: ByteArray,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val keyLengthBytes: Int = 32,
) {
    init {
        require(salt.size >= MIN_SALT_BYTES) { "salt must be at least $MIN_SALT_BYTES bytes" }
        require(memoryKiB >= 16) { "memory too low: $memoryKiB KiB" }
        require(iterations >= 1) { "iterations must be >= 1" }
        require(parallelism in 1..MAX_PARALLELISM) { "parallelism $parallelism" }
    }

    companion object {
        const val MIN_SALT_BYTES = 16
        const val MAX_PARALLELISM = 4

        /** Floor the calibration step may never go below, however fast the device is. */
        const val MIN_MEMORY_KIB = 32 * 1024
    }
}

object KeyDerivation {

    /**
     * BouncyCastle's lightweight Argon2id: pure Java, so no NDK and no JCE provider registration.
     * It is slower than a native build, which costs unlock milliseconds but buys an architecture
     * neutral APK; the calibration step absorbs the difference by tuning iterations per device.
     */
    fun argon2id(passphrase: ByteArray, params: KdfParams): ByteArray {
        val out = ByteArray(params.keyLengthBytes)
        val generator = Argon2BytesGenerator().apply {
            init(
                Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withSalt(params.salt)
                    .withParallelism(params.parallelism)
                    .withMemoryAsKB(params.memoryKiB)
                    .withIterations(params.iterations)
                    .build()
            )
        }
        generator.generateBytes(passphrase, out)
        return out
    }

    /** RFC 5869 HKDF-SHA256, extract then expand. */
    fun hkdfSha256(inputKeyingMaterial: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmac(ByteArray(32), inputKeyingMaterial)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var produced = 0
        var counter = 1
        while (produced < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, length - produced)
            previous.copyInto(out, produced, 0, take)
            produced += take
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** Per-file key. Deriving from fileId alone keeps the key stable across rename and move. */
    fun deriveFileKey(masterKey: ByteArray, fileId: ByteArray): ByteArray =
        hkdfSha256(masterKey, INFO_FILE + fileId)

    fun deriveIndexKey(masterKey: ByteArray, realmTag: ByteArray): ByteArray =
        hkdfSha256(masterKey, INFO_INDEX + realmTag)

    /**
     * A realm's public handle: 16 bytes that tag every derived name and key inside that realm. It is
     * never written to disk, because it can be recomputed from the master key and the realm's salt,
     * which means an analyst cannot tell which realm a blob belongs to by looking at its name.
     */
    fun deriveRealmId(masterKey: ByteArray, salt: ByteArray): ByteArray =
        hkdfSha256(masterKey, INFO_REALM + salt, VaultFormat.BLOB_ID_BYTES)

    /**
     * Where the current index generation is recorded. Deriving the blob *name* from the master key
     * means the anchor is unlocatable without it: nothing on disk points at it, so an attacker with a
     * raw image has to guess which of tens of thousands of identical blobs is the index.
     */
    fun deriveAnchorBlobId(masterKey: ByteArray, realmId: ByteArray): ByteArray =
        hkdfSha256(masterKey, INFO_ANCHOR_NAME + realmId, VaultFormat.BLOB_ID_BYTES)

    fun deriveAnchorKey(masterKey: ByteArray, realmId: ByteArray): ByteArray =
        hkdfSha256(masterKey, INFO_ANCHOR + realmId)

    /**
     * The write-ahead journal, which records imports and deletions that were in flight when the process
     * died. Like the anchor it is found by derived name, not by pointer, and it is a size-classed
     * envelope, so it looks exactly like any other small blob in the pool.
     */
    fun deriveJournalBlobId(masterKey: ByteArray, realmId: ByteArray): ByteArray =
        hkdfSha256(masterKey, INFO_JOURNAL_NAME + realmId, VaultFormat.BLOB_ID_BYTES)

    fun deriveJournalKey(masterKey: ByteArray, realmId: ByteArray): ByteArray =
        hkdfSha256(masterKey, INFO_JOURNAL + realmId)

    /**
     * The domain key that names a file's chunk blobs. Naming is separate from content so knowing where
     * a chunk lives never helps read one, and the other way round stays true as well.
     */
    fun deriveBlobNameKey(masterKey: ByteArray, realmId: ByteArray): ByteArray =
        hkdfSha256(masterKey, INFO_BLOB_NAME + realmId)

    /**
     * Where a chunk's blob is stored, as a function of (realm, fileId, chunkIndex). Names are derived
     * rather than stored, which is what lets the journal, the index and the garbage collector all speak
     * about a file's chunks without carrying a 16-byte id per chunk: 32 KiB of index saved per GiB of
     * content, and an interrupted import that is always enumerable because its blob names are computable
     * from the record alone.
     */
    fun deriveBlobId(nameKey: ByteArray, fileId: ByteArray, chunkIndex: Long): ByteArray {
        require(nameKey.size == 32) { "blob name key must be 32 bytes, got ${nameKey.size}" }
        require(fileId.size == VaultFormat.FILE_ID_BYTES) { "fileId must be 16 bytes, got ${fileId.size}" }
        val info = INFO_BLOB + fileId + ByteArray(8).also { ChunkGeometry.writeLongBe(chunkIndex, it, 0) }
        return hkdfSha256(nameKey, info, VaultFormat.BLOB_ID_BYTES)
    }

    /**
     * Each index generation gets its own key, mixed with a fresh random salt. Without this, two
     * generations sharing a leading byte-for-byte prefix would be encrypted under the same key and
     * nonce, which is the one AES-GCM failure that leaks the authentication subkey.
     */
    fun deriveGenerationKey(indexKey: ByteArray, generationSalt: ByteArray): ByteArray =
        hkdfSha256(indexKey, INFO_INDEX_GEN + generationSalt)

    private val INFO_FILE = "arcane/file/".toByteArray()
    private val INFO_INDEX = "arcane/index/".toByteArray()
    private val INFO_REALM = "arcane/realm/".toByteArray()
    private val INFO_ANCHOR = "arcane/anchor/".toByteArray()
    private val INFO_ANCHOR_NAME = "arcane/anchor-name/".toByteArray()
    private val INFO_JOURNAL = "arcane/journal/".toByteArray()
    private val INFO_JOURNAL_NAME = "arcane/journal-name/".toByteArray()
    private val INFO_INDEX_GEN = "arcane/index-gen/".toByteArray()
    private val INFO_BLOB_NAME = "arcane/blob-name/".toByteArray()
    private val INFO_BLOB = "arcane/blob/".toByteArray()
}

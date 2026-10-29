package dev.arcane.vault.crypto

import dev.arcane.vault.format.ChunkGeometry
import dev.arcane.vault.format.VaultFormat
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A chunk failed authentication: wrong key, wrong position, spliced, reordered or truncated. */
class VaultAuthenticationException(
    val chunkIndex: Long,
    reason: String,
) : Exception("chunk $chunkIndex failed authentication: $reason")

/**
 * One chunk is one AES-256-GCM record. Encrypting per chunk rather than per file is what makes the
 * layout randomly addressable, which is what makes scrubbing a gigabyte-scale video possible without
 * decrypting anything before the seek point.
 */
object ChunkCipher {

    private const val GCM_TAG_BITS = 128

    /**
     * Binds the blob to its exact position in its exact file:
     * fileId(16) || chunkIndex(8 BE) || chunkCount(8 BE) || formatVersion(2 BE)
     *
     * chunkCount is included so dropping the tail of a file, or appending an extra chunk, fails
     * authentication instead of quietly producing a shorter video.
     */
    fun aadFor(
        fileId: ByteArray,
        chunkIndex: Long,
        chunkCount: Long,
        formatVersion: Int = VaultFormat.FORMAT_VERSION,
    ): ByteArray {
        require(fileId.size == VaultFormat.FILE_ID_BYTES) { "fileId ${fileId.size}" }
        return ByteArray(34).also { aad ->
            fileId.copyInto(aad, 0)
            ChunkGeometry.writeLongBe(chunkIndex, aad, 16)
            ChunkGeometry.writeLongBe(chunkCount, aad, 24)
            aad[32] = ((formatVersion shr 8) and 0xff).toByte()
            aad[33] = (formatVersion and 0xff).toByte()
        }
    }

    fun encryptChunk(
        fileKey: ByteArray,
        paddedPlaintext: ByteArray,
        chunkSalt: ByteArray,
        fileId: ByteArray,
        chunkIndex: Long,
        chunkCount: Long,
        formatVersion: Int = VaultFormat.FORMAT_VERSION,
    ): ByteArray {
        val cipher = newCipher(Cipher.ENCRYPT_MODE, fileKey, chunkSalt, chunkIndex, chunkCount, formatVersion)
        cipher.updateAAD(aadFor(fileId, chunkIndex, chunkCount, formatVersion))
        return cipher.doFinal(paddedPlaintext)
    }

    /** Returns the padded plaintext. Callers trim to the logical size recorded in the index. */
    fun decryptChunk(
        fileKey: ByteArray,
        blob: ByteArray,
        chunkSalt: ByteArray,
        fileId: ByteArray,
        chunkIndex: Long,
        chunkCount: Long,
        formatVersion: Int = VaultFormat.FORMAT_VERSION,
    ): ByteArray {
        if (blob.size < VaultFormat.GCM_TAG_BYTES) {
            throw VaultAuthenticationException(chunkIndex, "blob shorter than a GCM tag (${blob.size} bytes)")
        }
        val cipher = newCipher(Cipher.DECRYPT_MODE, fileKey, chunkSalt, chunkIndex, chunkCount, formatVersion)
        cipher.updateAAD(aadFor(fileId, chunkIndex, chunkCount, formatVersion))
        return try {
            cipher.doFinal(blob)
        } catch (badTag: javax.crypto.AEADBadTagException) {
            throw VaultAuthenticationException(chunkIndex, "tag mismatch")
        }
    }

    private fun newCipher(
        mode: Int,
        fileKey: ByteArray,
        chunkSalt: ByteArray,
        chunkIndex: Long,
        chunkCount: Long,
        formatVersion: Int,
    ): Cipher {
        require(fileKey.size == 32) { "file key must be 32 bytes, got ${fileKey.size}" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                mode,
                SecretKeySpec(fileKey, "AES"),
                GCMParameterSpec(
                    GCM_TAG_BITS,
                    ChunkGeometry.nonceFor(chunkSalt, chunkIndex).also {
                        check(it.size == VaultFormat.NONCE_BYTES)
                    },
                ),
            )
        }
    }

}

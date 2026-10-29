package dev.arcane.vault.crypto

import com.google.common.truth.Truth.assertWithMessage
import com.google.common.truth.Truth.assertThat
import dev.arcane.vault.format.ChunkGeometry
import dev.arcane.vault.format.Hex
import dev.arcane.vault.format.VaultFormat
import java.security.MessageDigest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private fun sha256Hex(bytes: ByteArray): String =
    Hex.lower(MessageDigest.getInstance("SHA-256").digest(bytes))

/** Payload pattern must stay identical to tools/kat_vectors.py. */
private fun patternPayload(length: Int, chunkIndex: Long): ByteArray =
    ByteArray(length) { i -> ((i * 37 + chunkIndex) and 0xff).toByte() }

class ChunkCipherTest {

    private val fileId = Hex.decode(KatVectors.FILE_ID_HEX)
    private val fileKey = Hex.decode(KatVectors.FILE_KEY_HEX)
    private val chunkSalt = Hex.decode(KatVectors.CHUNK_SALT_HEX)

    @Test
    fun `nonce and aad are exactly the layout the reference generator assumed`() {
        assertThat(Hex.lower(ChunkGeometry.nonceFor(chunkSalt, 0))).isEqualTo(KatVectors.NONCE_INDEX0)
        assertThat(Hex.lower(ChunkGeometry.nonceFor(chunkSalt, 1))).isEqualTo(KatVectors.NONCE_INDEX1)
        assertThat(Hex.lower(ChunkCipher.aadFor(fileId, 0, 3))).isEqualTo(KatVectors.AAD_INDEX0_COUNT3)
        assertThat(ChunkCipher.aadFor(fileId, 0, 3).size).isEqualTo(34)
    }

    @Test
    fun `encrypting produces byte identical output to openssl aes-256-gcm`() {
        KatVectors.CHUNKS.forEach { vector ->
            val plaintext = Hex.decode(vector.plaintextHex)
            val blob = ChunkCipher.encryptChunk(fileKey, plaintext, chunkSalt, fileId, vector.chunkIndex, vector.chunkCount)

            assertThat(blob.size).isEqualTo(plaintext.size + VaultFormat.GCM_TAG_BYTES)
            assertWithMessage("chunk ${vector.chunkIndex} of ${vector.chunkCount}")
                .that(sha256Hex(blob))
                .isEqualTo(vector.blobSha256Hex)
        }
    }

    @Test
    fun `a whole size class encrypts to the reference digest`() {
        val plaintext = patternPayload(KatVectors.FULL_CLASS_PAYLOAD_BYTES, 0)
        val blob = ChunkCipher.encryptChunk(fileKey, plaintext, chunkSalt, fileId, 0, 1)

        assertThat(sha256Hex(blob)).isEqualTo(KatVectors.FULL_CLASS_BLOB_SHA256)
        assertThat(blob.size).isEqualTo(KatVectors.FULL_CLASS_PAYLOAD_BYTES + VaultFormat.GCM_TAG_BYTES)
    }

    @Test
    fun `decrypts ciphertext produced by a foreign implementation`() {
        val decrypted = ChunkCipher.decryptChunk(
            fileKey, Hex.decode(KatVectors.TAMPER_BLOB_HEX), chunkSalt, fileId, 0, 3,
        )
        assertThat(Hex.lower(decrypted)).isEqualTo(KatVectors.TAMPER_PLAINTEXT_HEX)
    }

    @Test
    fun `a full payload chunk round trips exactly`() {
        val plaintext = patternPayload(VaultFormat.CHUNK_PAYLOAD_BYTES, 7)
        val blob = ChunkCipher.encryptChunk(fileKey, plaintext, chunkSalt, fileId, 7, 8)
        val decrypted = ChunkCipher.decryptChunk(fileKey, blob, chunkSalt, fileId, 7, 8)

        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `identical plaintext at different positions does not produce identical blobs`() {
        // Without this, repeated chunks would leak structure through ciphertext equality.
        val plaintext = patternPayload(1024, 0)
        val first = ChunkCipher.encryptChunk(fileKey, plaintext, chunkSalt, fileId, 0, 2)
        val second = ChunkCipher.encryptChunk(fileKey, plaintext, chunkSalt, fileId, 1, 2)

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `every way an attacker can move a chunk around fails authentication`() {
        val blob = Hex.decode(KatVectors.TAMPER_BLOB_HEX)
        val otherFileId = fileId.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val otherKey = KeyDerivation.deriveFileKey(Hex.decode(KatVectors.MASTER_KEY_HEX), otherFileId)

        assertThrows<VaultAuthenticationException> {
            // Reordered: same blob claimed to live at a different index.
            ChunkCipher.decryptChunk(fileKey, blob, chunkSalt, fileId, 1, 3)
        }
        assertThrows<VaultAuthenticationException> {
            // Truncated file: same chunks, fewer of them.
            ChunkCipher.decryptChunk(fileKey, blob, chunkSalt, fileId, 0, 2)
        }
        assertThrows<VaultAuthenticationException> {
            // Spliced into another file.
            ChunkCipher.decryptChunk(fileKey, blob, chunkSalt, otherFileId, 0, 3)
        }
        assertThrows<VaultAuthenticationException> {
            // Wrong realm or wrong passphrase: different derived key.
            ChunkCipher.decryptChunk(otherKey, blob, chunkSalt, fileId, 0, 3)
        }
        assertThrows<VaultAuthenticationException> {
            // One bit of ciphertext flipped.
            val corrupted = blob.copyOf().also { it[4] = (it[4].toInt() xor 0x80).toByte() }
            ChunkCipher.decryptChunk(fileKey, corrupted, chunkSalt, fileId, 0, 3)
        }
        assertThrows<VaultAuthenticationException> {
            // Tag cut off the end.
            ChunkCipher.decryptChunk(fileKey, blob.copyOfRange(0, blob.size - 1), chunkSalt, fileId, 0, 3)
        }
        assertThrows<VaultAuthenticationException> {
            // Empty blob is not a chunk.
            ChunkCipher.decryptChunk(fileKey, ByteArray(0), chunkSalt, fileId, 0, 3)
        }
    }

    @Test
    fun `salt is part of the nonce so two files never reuse one`() {
        val otherSalt = Hex.decode("d4c3b2a1")
        val plaintext = patternPayload(256, 0)

        val a = ChunkCipher.encryptChunk(fileKey, plaintext, chunkSalt, fileId, 0, 1)
        val b = ChunkCipher.encryptChunk(fileKey, plaintext, otherSalt, fileId, 0, 1)

        assertThat(a).isNotEqualTo(b)
        // Both still decrypt under their own salt.
        assertThat(ChunkCipher.decryptChunk(fileKey, b, otherSalt, fileId, 0, 1)).isEqualTo(plaintext)
    }
}

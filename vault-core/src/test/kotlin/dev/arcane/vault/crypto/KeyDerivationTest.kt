package dev.arcane.vault.crypto

import com.google.common.truth.Truth.assertWithMessage
import com.google.common.truth.Truth.assertThat
import dev.arcane.vault.format.Hex
import org.junit.jupiter.api.Test

class KeyDerivationTest {

    @Test
    fun `argon2id matches the C reference implementation`() {
        KatVectors.ARGON2ID.forEach { vector ->
            val derived = KeyDerivation.argon2id(
                Hex.decode(vector.secretHex),
                KdfParams(
                    salt = Hex.decode(vector.saltHex),
                    memoryKiB = vector.memoryKiB,
                    iterations = vector.timeCost,
                    parallelism = vector.parallelism,
                    keyLengthBytes = vector.hashLen,
                ),
            )
            assertWithMessage("t=${vector.timeCost} m=${vector.memoryKiB} p=${vector.parallelism}")
                .that(Hex.lower(derived))
                .isEqualTo(vector.tagHex)
        }
    }

    @Test
    fun `per-file keys match openssl hkdf-sha256`() {
        val master = Hex.decode(KatVectors.MASTER_KEY_HEX)
        KatVectors.HKDF_FILE_KEYS.forEach { (fileIdHex, expectedKeyHex) ->
            val fileKey = KeyDerivation.deriveFileKey(master, Hex.decode(fileIdHex))
            assertThat(Hex.lower(fileKey)).isEqualTo(expectedKeyHex)
        }
    }

    @Test
    fun `key domains never collide`() {
        val master = Hex.decode(KatVectors.MASTER_KEY_HEX)
        val fileId = Hex.decode(KatVectors.FILE_ID_HEX)
        val realmId = KeyDerivation.deriveRealmId(master, fileId)

        val keys = listOf(
            KeyDerivation.deriveFileKey(master, fileId),
            KeyDerivation.deriveIndexKey(master, fileId),
            realmId,
            KeyDerivation.deriveAnchorKey(master, realmId),
            KeyDerivation.deriveAnchorBlobId(master, realmId),
            KeyDerivation.deriveGenerationKey(realmId, fileId),
        )

        assertThat(keys.map { it.toList() }.toSet()).hasSize(keys.size)
    }

    @Test
    fun `two files never share a key even with the same master key`() {
        val master = Hex.decode(KatVectors.MASTER_KEY_HEX)
        val keys = (0 until 2000).map { index ->
            val fileId = ByteArray(16).also { bytes ->
                bytes[14] = ((index shr 8) and 0xff).toByte()
                bytes[15] = (index and 0xff).toByte()
            }
            KeyDerivation.deriveFileKey(master, fileId)
        }
        assertThat(keys.map(Hex::lower).distinct()).hasSize(2000)
    }

    @Test
    fun `salt and cost parameters are all load bearing`() {
        val passphrase = "arcane-kat-passphrase".toByteArray()
        val base = KdfParams(Hex.decode(KatVectors.ARGON2ID[0].saltHex), 16384, 3, 2)
        val reference = Hex.lower(KeyDerivation.argon2id(passphrase, base))

        assertThat(Hex.lower(KeyDerivation.argon2id(passphrase, base.copy(memoryKiB = 8192)))).isNotEqualTo(reference)
        assertThat(Hex.lower(KeyDerivation.argon2id(passphrase, base.copy(iterations = 4)))).isNotEqualTo(reference)
        assertThat(Hex.lower(KeyDerivation.argon2id(passphrase, base.copy(parallelism = 1)))).isNotEqualTo(reference)
        assertThat(
            Hex.lower(KeyDerivation.argon2id(passphrase, base.copy(salt = Hex.decode("0f".repeat(16)))))
        ).isNotEqualTo(reference)
        // Same inputs, same output: unlock must be reproducible across devices.
        assertThat(Hex.lower(KeyDerivation.argon2id(passphrase, base))).isEqualTo(reference)
    }

    @Test
    fun `rejects parameters that would weaken the kdf`() {
        val salt = Hex.decode("00".repeat(16))
        assertThat(runCatching { KdfParams(salt.take(8).toByteArray(), 16384, 3, 2) }.isFailure).isTrue()
        assertThat(runCatching { KdfParams(salt, 8, 3, 2) }.isFailure).isTrue()
        assertThat(runCatching { KdfParams(salt, 16384, 0, 2) }.isFailure).isTrue()
        assertThat(runCatching { KdfParams(salt, 16384, 3, 0) }.isFailure).isTrue()
    }
}

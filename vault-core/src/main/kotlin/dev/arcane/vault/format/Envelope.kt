package dev.arcane.vault.format

import dev.arcane.vault.crypto.ChunkCipher
import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.store.BlobStore

/**
 * A single size-classed blob with a public random salt at the front, used for the small fixed-place
 * records of a realm: the index anchor and the import journal.
 *
 * Two properties matter and both are easy to lose. The salt is carved out of the 32 KiB class instead of
 * added to it, so a sealed record is exactly the same size as any other small blob and cannot be picked
 * out of the pool by length. And the key is derived per write from that salt, so a record rewritten a
 * thousand times never repeats a nonce under the same key.
 */
object Envelope {
    const val SALT_BYTES = 16
    const val MAX_BODY_BYTES = VaultFormat.SIZE_CLASS_BYTES - SALT_BYTES

    /** @param deriveKey turns the fresh public salt into the key for this particular write. */
    fun seal(
        blobs: BlobStore,
        fileId: ByteArray,
        deriveKey: (salt: ByteArray) -> ByteArray,
        body: ByteArray,
    ) {
        require(body.size <= MAX_BODY_BYTES) {
            "record is ${body.size} bytes, past one envelope's $MAX_BODY_BYTES"
        }
        val salt = Secrets.randomBytes(SALT_BYTES)
        val padded = ByteArray(MAX_BODY_BYTES).also { body.copyInto(it, 0) }
        val cipher = ChunkCipher.encryptChunk(
            fileKey = deriveKey(salt),
            paddedPlaintext = padded,
            chunkSalt = salt.copyOfRange(0, VaultFormat.CHUNK_SALT_BYTES),
            fileId = fileId,
            chunkIndex = 0,
            chunkCount = 1,
        )
        blobs.write(fileId, salt + cipher)
        Secrets.wipe(padded)
    }

    fun open(
        blobs: BlobStore,
        fileId: ByteArray,
        deriveKey: (salt: ByteArray) -> ByteArray,
    ): ByteArray? {
        if (!blobs.exists(fileId)) return null
        val blob = blobs.read(fileId)
        require(blob.size > SALT_BYTES + VaultFormat.GCM_TAG_BYTES) { "envelope blob is truncated" }
        val salt = blob.copyOfRange(0, SALT_BYTES)
        val padded = ChunkCipher.decryptChunk(
            fileKey = deriveKey(salt),
            blob = blob.copyOfRange(SALT_BYTES, blob.size),
            chunkSalt = salt.copyOfRange(0, VaultFormat.CHUNK_SALT_BYTES),
            fileId = fileId,
            chunkIndex = 0,
            chunkCount = 1,
        )
        val body = padded.copyOfRange(4, 4 + readIntBe(padded, 0))
        Secrets.wipe(padded)
        return body
    }

    /** The length prefix that lets a fixed-size class carry a variable body. */
    fun encodeBody(body: ByteArray): ByteArray =
        ByteArray(4 + body.size).also { out ->
            writeIntBe(body.size, out, 0)
            body.copyInto(out, 4)
        }

    private fun readIntBe(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xff) shl 24) or
            ((bytes[at + 1].toInt() and 0xff) shl 16) or
            ((bytes[at + 2].toInt() and 0xff) shl 8) or
            (bytes[at + 3].toInt() and 0xff)

    private fun writeIntBe(value: Int, into: ByteArray, at: Int) {
        into[at] = ((value shr 24) and 0xff).toByte()
        into[at + 1] = ((value shr 16) and 0xff).toByte()
        into[at + 2] = ((value shr 8) and 0xff).toByte()
        into[at + 3] = (value and 0xff).toByte()
    }
}

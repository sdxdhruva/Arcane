package dev.arcane.vault.format

import java.security.SecureRandom

/** Pure geometry over the chunked layout: sizes, offsets, names, nonces. No crypto, no I/O. */
object ChunkGeometry {

    /** A zero-byte file still gets one chunk, so "empty" and "absent" cannot be conflated on disk. */
    fun chunkCountFor(plainSize: Long): Long {
        require(plainSize >= 0) { "negative size $plainSize" }
        if (plainSize == 0L) return 1
        return (plainSize + VaultFormat.CHUNK_PAYLOAD_BYTES - 1) / VaultFormat.CHUNK_PAYLOAD_BYTES
    }

    /** Plaintext bytes held by chunk [index] of a file of [plainSize], before padding. */
    fun rawPayloadFor(index: Long, plainSize: Long, chunkCount: Long): Int {
        val chunk = VaultFormat.CHUNK_PAYLOAD_BYTES
        val offset = index * chunk
        if (offset >= plainSize) return 0
        val remaining = plainSize - offset
        return if (remaining >= chunk) chunk else {
            if (index == chunkCount - 1) remaining.toInt() else error("short chunk before the tail")
        }
    }

    /**
     * Tail chunk padded up to the next size class. Full chunks are already a whole number of
     * classes, so only the last chunk ever grows here.
     */
    fun paddedPayloadFor(rawPayload: Int): Int {
        require(rawPayload in 0..VaultFormat.CHUNK_PAYLOAD_BYTES) { "raw payload $rawPayload" }
        val cls = VaultFormat.SIZE_CLASS_BYTES
        val padded = ((rawPayload + cls - 1) / cls) * cls
        // A padded-to-zero tail would be indistinguishable from no tail at all.
        return if (padded == 0) cls else padded
    }

    fun blobCipherSizeFor(paddedPayload: Int): Int = paddedPayload + VaultFormat.GCM_TAG_BYTES

    /**
     * On-disk bytes for a file of [plainSize]: every chunk's ciphertext, tail padding included.
     * Excludes directory slack, which this format cannot control. Closed form because this runs
     * while rendering a listing, not once per import.
     */
    fun cipherSizeFor(plainSize: Long): Long {
        val count = chunkCountFor(plainSize)
        val fullChunkCipher = (VaultFormat.CHUNK_PAYLOAD_BYTES + VaultFormat.GCM_TAG_BYTES).toLong()
        val lastRaw = rawPayloadFor(count - 1, plainSize, count)
        return (count - 1) * fullChunkCipher + blobCipherSizeFor(paddedPayloadFor(lastRaw))
    }

    /** Big-endian write shared by the AAD layout and the derived blob names, which must agree. */
    internal fun writeLongBe(value: Long, into: ByteArray, at: Int) {
        var remaining = value
        for (i in 7 downTo 0) {
            into[at + i] = (remaining and 0xff).toByte()
            remaining = remaining shr 8
        }
    }

    fun chunkIndexOf(offset: Long): Long {
        require(offset >= 0) { "negative offset $offset" }
        return offset / VaultFormat.CHUNK_PAYLOAD_BYTES
    }

    fun offsetWithinChunk(offset: Long): Int = (offset % VaultFormat.CHUNK_PAYLOAD_BYTES).toInt()

    /**
     * Nonce is salt(4) || chunkIndex(8, big endian). Safe without a stored per-chunk nonce because
     * the key is per-file and chunk indices never repeat within a file.
     */
    fun nonceFor(chunkSalt: ByteArray, chunkIndex: Long): ByteArray {
        require(chunkSalt.size == VaultFormat.CHUNK_SALT_BYTES) { "salt ${chunkSalt.size}" }
        require(chunkIndex >= 0) { "negative chunk index $chunkIndex" }
        return ByteArray(VaultFormat.NONCE_BYTES).also { nonce ->
            chunkSalt.copyInto(nonce, 0, 0, VaultFormat.CHUNK_SALT_BYTES)
            var v = chunkIndex
            for (i in VaultFormat.NONCE_BYTES - 1 downTo VaultFormat.CHUNK_SALT_BYTES) {
                nonce[i] = (v and 0xff).toByte()
                v = v shr 8
            }
        }
    }

    fun bucketName(blobId: ByteArray): String = Hex.lower(byteArrayOf(blobId.first()))

    fun blobName(blobId: ByteArray): String = Hex.lower(blobId) + VaultFormat.BLOB_SUFFIX

    /**
     * Blob names are the id itself, so they carry no correlation to logical filenames. The suffix is
     * deliberately `.bin` and shared by every blob, including index blobs, so nothing stands out.
     */
    fun newBlobId(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(VaultFormat.BLOB_ID_BYTES).also(random::nextBytes)
}

/** Hand-rolled hex: java.util.HexFormat is JDK 18 and is absent from Android API 28. */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun lower(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = DIGITS[v shr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0f]
        }
        return String(out)
    }

    fun decode(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd hex length ${text.length}" }
        return ByteArray(text.length / 2) { i ->
            val hi = DIGITS.indexOf(text[i * 2])
            val lo = DIGITS.indexOf(text[i * 2 + 1])
            require(hi >= 0 && lo >= 0) { "non-hex character in '$text'" }
            ((hi shl 4) or lo).toByte()
        }
    }

    /** Constant-time compare for anything that gates decryption. */
    fun equalsConstantTime(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or ((a[i].toInt() xor b[i].toInt()) and 0xff)
        return diff == 0
    }
}

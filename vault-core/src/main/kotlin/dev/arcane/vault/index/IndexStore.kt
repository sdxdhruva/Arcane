package dev.arcane.vault.index

import dev.arcane.vault.crypto.KeyDerivation
import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.file.ChunkMap
import dev.arcane.vault.file.ChunkReader
import dev.arcane.vault.file.ChunkWriter
import dev.arcane.vault.format.ChunkGeometry
import dev.arcane.vault.format.Envelope
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.store.BlobStore
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * Which blob holds the current index, and under which key. The anchor record itself is stored inside
 * the anchor blob, so nothing outside the realm's master key tells you where the index is.
 *
 * The record carries no blob list. Chunk positions are derived from (realm, fileId, index), so the
 * blobs this generation occupies are recomputable from [plainSize] alone, which keeps the anchor a
 * fixed small size however many chunks the index has grown to.
 */
@Serializable
data class GenerationRecord(
    val generation: Long,
    val fileId: ByteArray,
    val salt: ByteArray,
    val chunkSalt: ByteArray,
    val plainSize: Long,
    val committedAtMillis: Long,
) {
    val chunkCount: Long get() = ChunkGeometry.chunkCountFor(plainSize)

    init {
        require(salt.size == SALT_BYTES) { "generation salt ${salt.size}" }
        require(chunkSalt.size == VaultFormat.CHUNK_SALT_BYTES) { "chunkSalt ${chunkSalt.size}" }
        require(plainSize >= 0) { "negative index size" }
        require(generation >= 1) { "generation must start at 1" }
    }

    companion object {
        const val SALT_BYTES = 16
    }

    // Content equality, same reason as FileRecord: the ids and salts are arrays.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GenerationRecord) return false
        return generation == other.generation &&
            plainSize == other.plainSize &&
            committedAtMillis == other.committedAtMillis &&
            fileId.contentEquals(other.fileId) &&
            salt.contentEquals(other.salt) &&
            chunkSalt.contentEquals(other.chunkSalt)
    }

    override fun hashCode(): Int {
        var result = generation.hashCode()
        result = 31 * result + fileId.contentHashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + chunkSalt.contentHashCode()
        result = 31 * result + plainSize.hashCode()
        result = 31 * result + committedAtMillis.hashCode()
        return result
    }
}

@OptIn(ExperimentalSerializationApi::class)
class IndexStore(private val blobs: BlobStore) {

    private val cbor = Cbor { ignoreUnknownKeys = true }
    private val reader = ChunkReader(blobs)

    fun anchorBlobId(masterKey: ByteArray, realmId: ByteArray): ByteArray =
        KeyDerivation.deriveAnchorBlobId(masterKey, realmId)

    /** Null when the realm has never been committed. Authentication failure propagates: it means the
     * master key is wrong, which is how the unlock path rejects a candidate passphrase. */
    fun load(masterKey: ByteArray, realmId: ByteArray): Pair<VaultIndex, GenerationRecord>? {
        val record = readAnchor(masterKey, realmId) ?: return null
        val bytes = reader.readAt(generationMap(masterKey, realmId, record), 0, record.plainSize.toInt())
        return cbor.decodeFromByteArray<VaultIndex>(bytes) to record
    }

    fun currentGeneration(masterKey: ByteArray, realmId: ByteArray): GenerationRecord? =
        readAnchor(masterKey, realmId)

    /**
     * Writes a new generation, flips the anchor, then removes the previous generation's blobs. Order
     * matters: a crash before the flip leaves the old index fully readable, and a crash after it leaves
     * the new one. Deleting the old blobs last means no window exists where neither is present.
     */
    fun commit(masterKey: ByteArray, realmId: ByteArray, index: VaultIndex, nowMillis: Long): GenerationRecord {
        val previous = readAnchor(masterKey, realmId)
        val payload = cbor.encodeToByteArray(VaultIndex.serializer(), index)
        val record = writeGeneration(masterKey, realmId, payload, (previous?.generation ?: 0) + 1, nowMillis)
        writeAnchor(masterKey, realmId, record)
        previous?.let { old ->
            if (old.generation != record.generation) deleteGeneration(masterKey, realmId, old)
        }
        return record
    }

    /**
     * Removes one generation's blobs. Public because recovery needs it too: a generation left behind by
     * a crash between write and flip is an orphan, and orphan cleanup has to reach it by name rather than
     * by a pointer that was never written.
     */
    fun deleteGeneration(masterKey: ByteArray, realmId: ByteArray, record: GenerationRecord) {
        val nameKey = KeyDerivation.deriveBlobNameKey(masterKey, realmId)
        (0 until record.chunkCount).forEach { index ->
            blobs.delete(KeyDerivation.deriveBlobId(nameKey, record.fileId, index))
        }
        Secrets.wipe(nameKey)
        blobs.sync()
    }

    private fun writeGeneration(
        masterKey: ByteArray,
        realmId: ByteArray,
        payload: ByteArray,
        generation: Long,
        nowMillis: Long,
    ): GenerationRecord {
        val salt = Secrets.randomBytes(GenerationRecord.SALT_BYTES)
        val chunkSalt = Secrets.randomBytes(VaultFormat.CHUNK_SALT_BYTES)
        val fileId = ChunkGeometry.newBlobId()
        val key = KeyDerivation.deriveGenerationKey(
            KeyDerivation.deriveIndexKey(masterKey, realmId),
            salt,
        )
        val writer = ChunkWriter(
            blobs = blobs,
            fileId = fileId,
            chunkSalt = chunkSalt,
            fileKey = key,
            nameKey = KeyDerivation.deriveBlobNameKey(masterKey, realmId),
            plainSize = payload.size.toLong(),
        )
        writer.write(payload)
        writer.finish()
        Secrets.wipe(key)
        // The commit boundary for the whole generation: one fsync pass, not one per chunk.
        blobs.sync()
        return GenerationRecord(
            generation = generation,
            fileId = fileId,
            salt = salt,
            chunkSalt = chunkSalt,
            plainSize = payload.size.toLong(),
            committedAtMillis = nowMillis,
        )
    }

    private fun writeAnchor(masterKey: ByteArray, realmId: ByteArray, record: GenerationRecord) {
        Envelope.seal(
            blobs = blobs,
            fileId = anchorBlobId(masterKey, realmId),
            deriveKey = { salt -> anchorWriteKey(masterKey, realmId, salt) },
            body = Envelope.encodeBody(cbor.encodeToByteArray(GenerationRecord.serializer(), record)),
        )
        blobs.sync()
    }

    private fun readAnchor(masterKey: ByteArray, realmId: ByteArray): GenerationRecord? {
        val body = Envelope.open(
            blobs = blobs,
            fileId = anchorBlobId(masterKey, realmId),
            deriveKey = { salt -> anchorWriteKey(masterKey, realmId, salt) },
        ) ?: return null
        return cbor.decodeFromByteArray(GenerationRecord.serializer(), body)
    }

    private fun generationMap(masterKey: ByteArray, realmId: ByteArray, record: GenerationRecord): ChunkMap {
        val key = KeyDerivation.deriveGenerationKey(
            KeyDerivation.deriveIndexKey(masterKey, realmId),
            record.salt,
        )
        return ChunkMap(
            fileId = record.fileId,
            chunkSalt = record.chunkSalt,
            fileKey = key,
            nameKey = KeyDerivation.deriveBlobNameKey(masterKey, realmId),
            plainSize = record.plainSize,
        )
    }

    private fun anchorWriteKey(masterKey: ByteArray, realmId: ByteArray, anchorSalt: ByteArray): ByteArray =
        KeyDerivation.deriveGenerationKey(KeyDerivation.deriveAnchorKey(masterKey, realmId), anchorSalt)
}

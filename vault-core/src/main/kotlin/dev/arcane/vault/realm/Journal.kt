package dev.arcane.vault.realm

import dev.arcane.vault.crypto.KeyDerivation
import dev.arcane.vault.format.ChunkGeometry
import dev.arcane.vault.format.Envelope
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.store.BlobStore
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/** What a pending item is waiting for: chunks still to be written, or blobs still to be removed. */
@Serializable
enum class PendingKind {
    Import,
    Delete,
}

/**
 * One operation the vault promised and has not finished. Everything needed to complete or undo it is in
 * here, including the file id and chunk salt, because those are the two inputs the blob names are
 * derived from — which is what makes an interrupted operation enumerable instead of permanent litter.
 *
 * [chunksDone] counts chunks already committed for an [PendingKind.Import] and is unused (zero) for a
 * delete, whose completion condition is "no blob of this file remains".
 */
@Serializable
data class PendingItem(
    val kind: PendingKind,
    val fileId: ByteArray,
    val plainSize: Long,
    val chunkSalt: ByteArray,
    val chunksDone: Long = 0,
    val name: String = "",
    val folderId: String? = null,
    val mimeType: String? = null,
    val startedAtMillis: Long,
) {
    val chunkCount: Long get() = ChunkGeometry.chunkCountFor(plainSize)
    val isComplete: Boolean get() = chunksDone >= chunkCount

    init {
        require(fileId.size == VaultFormat.FILE_ID_BYTES) { "fileId ${fileId.size}" }
        require(chunkSalt.size == VaultFormat.CHUNK_SALT_BYTES) { "chunkSalt ${chunkSalt.size}" }
        require(plainSize >= 0) { "negative size" }
        require(chunksDone >= 0) { "negative progress" }
        if (kind == PendingKind.Import) {
            require(chunksDone <= chunkCount) { "progress $chunksDone past $chunkCount chunks" }
            require(name.isNotEmpty()) { "an import must record the name it will land under" }
            require(!name.contains('/')) { "name must be one path element" }
        }
    }

    // Content equality: the generated one compares the two byte arrays by reference.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingItem) return false
        return kind == other.kind &&
            plainSize == other.plainSize &&
            chunksDone == other.chunksDone &&
            name == other.name &&
            folderId == other.folderId &&
            mimeType == other.mimeType &&
            startedAtMillis == other.startedAtMillis &&
            fileId.contentEquals(other.fileId) &&
            chunkSalt.contentEquals(other.chunkSalt)
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + fileId.contentHashCode()
        result = 31 * result + plainSize.hashCode()
        result = 31 * result + chunkSalt.contentHashCode()
        result = 31 * result + chunksDone.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (folderId?.hashCode() ?: 0)
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        result = 31 * result + startedAtMillis.hashCode()
        return result
    }
}

@Serializable
internal data class JournalBody(val items: List<PendingItem> = emptyList())

/**
 * The realm's write-ahead journal: a single size-classed envelope, found by a derived name, rewritten in
 * place as progress is made. Because every name inside it is derived from (realm, fileId, index), the
 * journal is also the only thing that lets the vault clean up after itself without ever scanning the
 * pool — and a scan is the one operation that would let realm A touch realm B's blobs.
 */
@OptIn(ExperimentalSerializationApi::class)
class WriteJournal(private val blobs: BlobStore) {

    private val cbor = Cbor { ignoreUnknownKeys = true }

    fun blobId(masterKey: ByteArray, realmId: ByteArray): ByteArray =
        KeyDerivation.deriveJournalBlobId(masterKey, realmId)

    /** Empty when nothing is pending. A failed authentication is a programming error, not a wrong key:
     * by the time the journal is read the realm is already unlocked by the anchor. */
    fun load(masterKey: ByteArray, realmId: ByteArray): List<PendingItem> {
        val body = Envelope.open(
            blobs = blobs,
            fileId = blobId(masterKey, realmId),
            deriveKey = { salt -> writeKey(masterKey, realmId, salt) },
        ) ?: return emptyList()
        return cbor.decodeFromByteArray<JournalBody>(body).items
    }

    fun record(masterKey: ByteArray, realmId: ByteArray, item: PendingItem): List<PendingItem> =
        replace(masterKey, realmId) { items -> items.filterNot { it.fileId.contentEquals(item.fileId) } + item }

    /** The progress tick. Called every few dozen chunks, not per chunk: each write is a whole size class. */
    fun progress(masterKey: ByteArray, realmId: ByteArray, fileId: ByteArray, chunksDone: Long): List<PendingItem> =
        replace(masterKey, realmId) { items ->
            items.map { if (it.fileId.contentEquals(fileId)) it.copy(chunksDone = chunksDone) else it }
        }

    fun settle(masterKey: ByteArray, realmId: ByteArray, fileId: ByteArray): List<PendingItem> =
        replace(masterKey, realmId) { items -> items.filterNot { it.fileId.contentEquals(fileId) } }

    fun clear(masterKey: ByteArray, realmId: ByteArray) {
        if (blobs.exists(blobId(masterKey, realmId))) {
            blobs.delete(blobId(masterKey, realmId))
            blobs.sync()
        }
    }

    private fun replace(
        masterKey: ByteArray,
        realmId: ByteArray,
        edit: (List<PendingItem>) -> List<PendingItem>,
    ): List<PendingItem> {
        val updated = edit(load(masterKey, realmId))
        val name = blobId(masterKey, realmId)
        if (updated.isEmpty()) {
            if (blobs.exists(name)) {
                blobs.delete(name)
                blobs.sync()
            }
            return updated
        }
        val body = cbor.encodeToByteArray(JournalBody.serializer(), JournalBody(updated))
        if (body.size > Envelope.MAX_BODY_BYTES - 4) throw JournalFull(updated.size)
        Envelope.seal(
            blobs = blobs,
            fileId = name,
            deriveKey = { salt -> writeKey(masterKey, realmId, salt) },
            body = Envelope.encodeBody(body),
        )
        blobs.sync()
        return updated
    }

    /** Fresh key per write, mixed with the envelope's public salt, so a rewritten journal never reuses a nonce. */
    private fun writeKey(masterKey: ByteArray, realmId: ByteArray, salt: ByteArray): ByteArray =
        KeyDerivation.deriveGenerationKey(KeyDerivation.deriveJournalKey(masterKey, realmId), salt)

    companion object {
        /** Chunks between progress ticks: 32 MiB of content, and the headroom a resume is worth. */
        const val PROGRESS_STRIDE_CHUNKS = 64L
    }
}

/**
 * More operations are in flight than one envelope can describe. Refusing is the safe answer: a journal
 * that silently dropped items would forget how to find their blobs, and unfindable blobs are permanent.
 */
class JournalFull(val items: Int) : Exception("journal cannot hold $items pending operations")

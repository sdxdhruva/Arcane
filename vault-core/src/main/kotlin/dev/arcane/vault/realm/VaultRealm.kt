package dev.arcane.vault.realm

import dev.arcane.vault.crypto.KeyDerivation
import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.file.ChunkMap
import dev.arcane.vault.file.ChunkReader
import dev.arcane.vault.file.ChunkWriter
import dev.arcane.vault.format.ChunkGeometry
import dev.arcane.vault.format.Hex
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.index.FileRecord
import dev.arcane.vault.index.FolderRecord
import dev.arcane.vault.index.IndexStore
import dev.arcane.vault.index.VaultIndex
import dev.arcane.vault.store.BlobStore
import java.io.IOException
import java.io.InputStream

/**
 * What the browser, the viewers and the import queue talk to: an unlocked realm plus the operations that
 * keep it consistent. Everything here is expressed in ids and names that exist only inside the encrypted
 * index, and every mutation that touches the blob pool is promised to the journal before it happens, so
 * a process that dies mid-operation leaves something a later run can either finish or undo.
 *
 * The single-realm-per-instance rule is deliberate. Two realms sharing one instance would mean one of
 * them could reach the other's blobs, and the whole deniability argument depends on that being impossible
 * even by accident.
 */
class VaultRealm(
    private val realm: UnlockedRealm,
    private val blobs: BlobStore,
    private val indexStore: IndexStore = IndexStore(blobs),
    private val journal: WriteJournal = WriteJournal(blobs),
    private val progressStride: Long = WriteJournal.PROGRESS_STRIDE_CHUNKS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val masterKey get() = realm.masterKey
    private val realmId get() = realm.realmId

    init {
        // Each tick is a whole 32 KiB envelope write plus an fsync, so the stride trades re-done bytes
        // against disk churn; one is legal but pointless, and zero would spin the loop.
        require(progressStride >= 1) { "progress stride must be at least one chunk" }
    }

    /**
     * The live view of the tree, refreshed from each commit. Volatile because the media layer reads the
     * listing from Media3's loading thread through [open]; the index itself is immutable, so a publish is
     * all that is needed.
     */
    @Volatile
    var index: VaultIndex = realm.index
        private set

    val reader: ChunkReader = ChunkReader(blobs)

    /** A long-running read asks this between chunks, so a lock mid-materialise stops rather than continues. */
    val isUnlocked: Boolean get() = !realm.isLocked

    val pendingItems: List<PendingItem> get() = journal.load(masterKey, realmId)

    fun file(fileId: ByteArray): FileRecord? = index.files.firstOrNull { it.fileId.contentEquals(fileId) }

    /**
     * A read handle for the media layer. The caller owns it and must call [ChunkMap.wipe] when the player
     * or viewer lets go; the keys inside are derived here so no downstream module re-implements them.
     *
     * A locked realm answers null: the keys it would derive are valid bytes pointing at nothing, and a
     * viewer should see "not open" rather than an authentication failure three chunks later.
     */
    fun open(fileId: ByteArray): ChunkMap? =
        if (realm.isLocked) null else file(fileId)?.chunkMap(masterKey, realmId)

    /**
     * Imports [plainSize] bytes from [source] into [folderId]. The source is read as a stream and never
     * held whole in memory, so the limit on file size is free space, not RAM.
     *
     * @param onProgress receives plaintext bytes committed, which is what an import sheet's bar needs.
     */
    fun import(
        source: InputStream,
        name: String,
        folderId: String?,
        mimeType: String?,
        plainSize: Long,
        onProgress: (bytesDone: Long) -> Unit = {},
    ): FileRecord {
        val item = PendingItem(
            kind = PendingKind.Import,
            fileId = Secrets.randomBytes(VaultFormat.FILE_ID_BYTES),
            plainSize = plainSize,
            chunkSalt = Secrets.randomBytes(VaultFormat.CHUNK_SALT_BYTES),
            name = name,
            folderId = folderId?.also { require(index.folder(it) != null) { "unknown folder $it" } },
            mimeType = mimeType,
            startedAtMillis = nowMillis(),
        )
        return runImport(item, source, resumeAtChunk = 0, onProgress = onProgress)
    }

    /**
     * Continues an import the journal survived. [source] must be positioned at [resumeOffset], which is
     * the caller's job because only the caller knows where the original file now lives.
     */
    fun resume(item: PendingItem, source: InputStream, onProgress: (bytesDone: Long) -> Unit = {}): FileRecord {
        require(item.kind == PendingKind.Import) { "only an import can be resumed" }
        return runImport(item, source, resumeAtChunk = item.chunksDone, onProgress = onProgress)
    }

    /** Where to position the original file before calling [resume]. */
    fun resumeOffset(item: PendingItem): Long = item.chunksDone * VaultFormat.CHUNK_PAYLOAD_BYTES

    /**
     * Abandons a pending operation and removes whatever it had already written. Both kinds are handled the
     * same way, because a blob is a blob: the names come from the item, so there is nothing to look up.
     */
    fun cancel(item: PendingItem) {
        checkUnlocked()
        removeChunks(item.fileId, item.chunkSalt, item.plainSize)
        journal.settle(masterKey, realmId, item.fileId)
    }

    /** Removes a file's listing first, then its bytes, so a crash never leaves a listing pointing at holes. */
    fun delete(fileId: ByteArray): Boolean {
        checkUnlocked()
        val record = file(fileId) ?: return false
        val item = PendingItem(
            kind = PendingKind.Delete,
            fileId = fileId,
            plainSize = record.plainSize,
            chunkSalt = record.chunkSalt,
            startedAtMillis = nowMillis(),
        )
        journal.record(masterKey, realmId, item)
        mutate(index.withoutFile(fileId))
        removeChunks(fileId, record.chunkSalt, record.plainSize)
        journal.settle(masterKey, realmId, fileId)
        return true
    }

    fun createFolder(name: String, parentId: String?): FolderRecord {
        val record = FolderRecord(id = newFolderId(), parentId = parentId, name = name)
        mutate(index.withFolder(record))
        return record
    }

    fun renameFolder(id: String, newName: String): FolderRecord {
        val record = requireNotNull(index.folder(id)) { "unknown folder $id" }.copy(name = newName)
        mutate(index.withFolder(record))
        return record
    }

    /** Refuses a move that would put a folder inside itself; [VaultIndex.withFolder] is the one that knows. */
    fun moveFolder(id: String, newParentId: String?): FolderRecord {
        if (newParentId != null) require(index.folder(newParentId) != null) { "unknown folder $newParentId" }
        val record = requireNotNull(index.folder(id)) { "unknown folder $id" }.copy(parentId = newParentId)
        mutate(index.withFolder(record))
        return record
    }

    /** Drops the folder and its subtree; [VaultIndex.withoutFolder] refuses while any file remains inside. */
    fun deleteFolder(id: String): Boolean {
        require(index.folder(id) != null) { "unknown folder $id" }
        mutate(index.withoutFolder(id))
        return true
    }

    fun renameFile(fileId: ByteArray, newName: String): FileRecord {
        val record = requireNotNull(file(fileId)) { "unknown file" }.copy(name = newName, modifiedAtMillis = nowMillis())
        mutate(index.withFile(record))
        return record
    }

    /** Index-only, like rename: moving a 4 GiB file re-encrypts nothing, because keys never mention names. */
    fun moveFile(fileId: ByteArray, folderId: String?): FileRecord {
        if (folderId != null) require(index.folder(folderId) != null) { "unknown folder $folderId" }
        val record = requireNotNull(file(fileId)) { "unknown file" }
            .copy(folderId = folderId, modifiedAtMillis = nowMillis())
        mutate(index.withFile(record))
        return record
    }

    /**
     * Repairs whatever the last process left half-done, then reports what it did. Only names derivable
     * from this realm's key are ever touched, which is what lets this run against a pool that also holds
     * another realm's files.
     */
    fun reconcile(): ReconcileReport {
        checkUnlocked()
        var recovered = 0
        var finished = 0
        val resumable = mutableListOf<PendingItem>()
        for (item in journal.load(masterKey, realmId)) {
            val listed = index.files.any { it.fileId.contentEquals(item.fileId) }
            when {
                // The record landed but the settle did not: the file is whole, the promise is stale.
                listed && item.kind == PendingKind.Import -> journal.settle(masterKey, realmId, item.fileId)

                item.kind == PendingKind.Delete -> {
                    if (listed) mutate(index.withoutFile(item.fileId))
                    removeChunks(item.fileId, item.chunkSalt, item.plainSize)
                    journal.settle(masterKey, realmId, item.fileId)
                    finished++
                }

                // Every chunk exists but the index never listed them. The item carries the name, folder,
                // size and salt, so the file can be shown again instead of thrown away.
                item.isComplete && chunksPresent(item) -> {
                    val folderId = item.folderId?.takeIf { index.folder(it) != null }.orEmpty()
                    mutate(index.withFile(item.toRecord(folderId, item.startedAtMillis)))
                    journal.settle(masterKey, realmId, item.fileId)
                    recovered++
                }

                // Chunks the item promises are no longer on disk, so there is nothing to continue from.
                item.chunksDone > 0 && !chunksPresent(item) -> cancel(item)

                else -> resumable += item
            }
        }
        return ReconcileReport(resumable = resumable, recovered = recovered, deletionsFinished = finished)
    }

    private fun runImport(
        item: PendingItem,
        source: InputStream,
        resumeAtChunk: Long,
        onProgress: (bytesDone: Long) -> Unit,
    ): FileRecord {
        checkUnlocked()
        journal.record(masterKey, realmId, item)
        val fileKey = KeyDerivation.deriveFileKey(masterKey, item.fileId)
        val nameKey = KeyDerivation.deriveBlobNameKey(masterKey, realmId)
        val writer = ChunkWriter(
            blobs = blobs,
            fileId = item.fileId,
            chunkSalt = item.chunkSalt,
            fileKey = fileKey,
            nameKey = nameKey,
            plainSize = item.plainSize,
            resumeAtChunk = resumeAtChunk,
        )
        val slab = ByteArray(VaultFormat.CHUNK_PAYLOAD_BYTES)
        val finalCount = try {
            var placed = resumeAtChunk * VaultFormat.CHUNK_PAYLOAD_BYTES
            var journaled = resumeAtChunk
            while (placed < item.plainSize) {
                // Checked per chunk rather than once at the top: the lock can arrive at any moment, and the
                // cost of asking is a 32-byte scan against a 512 KiB decrypt.
                checkUnlocked()
                val want = minOf(VaultFormat.CHUNK_PAYLOAD_BYTES.toLong(), item.plainSize - placed).toInt()
                val got = fill(source, slab, want)
                if (got != want) throw SourceShrank(item.plainSize, placed + got)
                writer.write(slab, 0, got)
                placed += got
                onProgress(placed)
                val done = writer.chunksCommitted
                if (done - journaled >= progressStride) {
                    // Again before the tick, not only before the chunk: a journal write under a locked key
                    // lands at a name derived from nothing, which no later run could ever find or reclaim.
                    checkUnlocked()
                    journal.progress(masterKey, realmId, item.fileId, done)
                    journaled = done
                }
            }
            writer.finish().chunkCount
        } finally {
            Secrets.wipe(fileKey)
            Secrets.wipe(nameKey)
        }
        journal.progress(masterKey, realmId, item.fileId, finalCount)
        val record = item.toRecord(item.folderId ?: "", addedAtMillis = item.startedAtMillis)
        mutate(index.withFile(record))
        journal.settle(masterKey, realmId, item.fileId)
        return record
    }

    private fun PendingItem.toRecord(folderId: String, addedAtMillis: Long): FileRecord = FileRecord(
        fileId = fileId,
        name = name,
        folderId = folderId.ifEmpty { null },
        mimeType = mimeType,
        plainSize = plainSize,
        chunkSalt = chunkSalt,
        addedAtMillis = addedAtMillis,
        modifiedAtMillis = nowMillis(),
    )

    /** Writes nothing and looks up nothing: it asks whether the names the item promises are all there. */
    private fun chunksPresent(item: PendingItem): Boolean =
        derivedNames(item.fileId, item.chunkSalt, item.plainSize).all(blobs::exists)

    private fun removeChunks(fileId: ByteArray, chunkSalt: ByteArray, plainSize: Long) {
        derivedNames(fileId, chunkSalt, plainSize).forEach(blobs::delete)
        blobs.sync()
    }

    private fun derivedNames(fileId: ByteArray, chunkSalt: ByteArray, plainSize: Long): List<ByteArray> {
        val nameKey = KeyDerivation.deriveBlobNameKey(masterKey, realmId)
        return try {
            (0 until ChunkGeometry.chunkCountFor(plainSize)).map { KeyDerivation.deriveBlobId(nameKey, fileId, it) }
        } finally {
            Secrets.wipe(nameKey)
        }
    }

    private fun mutate(next: VaultIndex) {
        checkUnlocked()
        indexStore.commit(masterKey, realmId, next, nowMillis())
        index = next
    }

    /**
     * A locked realm cannot write anything, because a zeroed master key still derives a perfectly valid
     * wrong key: the work would land under names no later unlock can find. Refusing is the only safe move.
     */
    private fun checkUnlocked() {
        if (realm.isLocked) throw RealmLockedException()
    }

    private fun newFolderId(): String = Hex.lower(Secrets.randomBytes(8))

    private fun fill(source: InputStream, into: ByteArray, want: Int): Int {
        var got = 0
        while (got < want) {
            val read = source.read(into, got, want - got)
            if (read < 0) break
            got += read
        }
        return got
    }
}

/** What the browser shows after a crash: imports that can be continued, plus a tally of what was repaired. */
data class ReconcileReport(
    val resumable: List<PendingItem>,
    val recovered: Int,
    val deletionsFinished: Int,
)

/** The source got shorter while it was being read, so the declared size can no longer be met. */
class SourceShrank(expected: Long, got: Long) :
    IOException("source ended after $got bytes, expected $expected")

/**
 * The realm was locked while work was still in flight, so the operation stopped. Anything already written
 * is covered by the journal, which is exactly what [VaultRealm.reconcile] reads on the next unlock.
 */
class RealmLockedException : IllegalStateException("realm is locked")

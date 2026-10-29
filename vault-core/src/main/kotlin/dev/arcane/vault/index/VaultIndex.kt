package dev.arcane.vault.index

import dev.arcane.vault.crypto.KeyDerivation
import dev.arcane.vault.file.ChunkMap
import dev.arcane.vault.format.VaultFormat
import kotlinx.serialization.Serializable

/**
 * The virtual hierarchy. Names, folders and extensions exist only inside the encrypted index, so the
 * filesystem never sees a directory, a filename or an extension belonging to a stored file.
 *
 * Content keys derive from [fileId], never from a name, which is what makes rename and move index-only
 * writes: renaming a 4 GiB video re-encrypts nothing.
 */
@Serializable
data class FileRecord(
    val fileId: ByteArray,
    val name: String,
    /** Null means the file sits at the top of the realm, the way loose files sit in a home directory. */
    val folderId: String?,
    val mimeType: String? = null,
    val plainSize: Long,
    val chunkSalt: ByteArray,
    val addedAtMillis: Long,
    val modifiedAtMillis: Long,
) {
    init {
        require(fileId.size == VaultFormat.FILE_ID_BYTES) { "fileId ${fileId.size}" }
        require(chunkSalt.size == VaultFormat.CHUNK_SALT_BYTES) { "chunkSalt ${chunkSalt.size}" }
        require(plainSize >= 0) { "plainSize must not be negative" }
        require(!name.contains('/') && !name.contains('\u0000')) { "name must be a single path element" }
        require(name != "." && name != "..") { "name must not be a traversal element" }
    }

    /**
     * The unlock-time handle for playback and viewing. The caller owns the result and must wipe it; the
     * per-file key and the blob name key are derived here so nothing downstream re-implements the domains.
     */
    fun chunkMap(masterKey: ByteArray, realmId: ByteArray): ChunkMap = ChunkMap(
        fileId = fileId,
        chunkSalt = chunkSalt,
        fileKey = KeyDerivation.deriveFileKey(masterKey, fileId),
        nameKey = KeyDerivation.deriveBlobNameKey(masterKey, realmId),
        plainSize = plainSize,
    )

    // Explicit because the generated data-class equality compares ByteArray fields by reference, which
    // would make a record read back from the index never equal the record that was written into it.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileRecord) return false
        return name == other.name &&
            folderId == other.folderId &&
            mimeType == other.mimeType &&
            plainSize == other.plainSize &&
            addedAtMillis == other.addedAtMillis &&
            modifiedAtMillis == other.modifiedAtMillis &&
            fileId.contentEquals(other.fileId) &&
            chunkSalt.contentEquals(other.chunkSalt)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (folderId?.hashCode() ?: 0)
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        result = 31 * result + plainSize.hashCode()
        result = 31 * result + fileId.contentHashCode()
        result = 31 * result + chunkSalt.contentHashCode()
        result = 31 * result + addedAtMillis.hashCode()
        result = 31 * result + modifiedAtMillis.hashCode()
        return result
    }
}

@Serializable
data class FolderRecord(
    val id: String,
    val parentId: String?,
    val name: String,
) {
    init {
        require(name.isNotBlank()) { "folder name must not be blank" }
        require(!name.contains('/') && !name.contains('\u0000')) { "name must be a single path element" }
        require(id != parentId) { "a folder cannot be its own parent" }
    }
}

/** The whole realm's directory, decrypted into memory at unlock. A null parent or folder is top level. */
@Serializable
data class VaultIndex(
    val formatVersion: Int = VaultFormat.FORMAT_VERSION,
    val folders: List<FolderRecord> = emptyList(),
    val files: List<FileRecord> = emptyList(),
) {
    init {
        require(formatVersion == VaultFormat.FORMAT_VERSION) { "unsupported index version $formatVersion" }
        require(folders.map { it.id }.toSet().size == folders.size) { "duplicate folder id" }
        require(files.map { it.fileId.toList() }.toSet().size == files.size) { "duplicate file id" }
        val ids = folders.map { it.id }.toSet()
        require(folders.all { it.parentId == null || it.parentId in ids }) { "folder parent missing" }
        require(files.all { it.folderId == null || it.folderId in ids }) { "file folder missing" }
    }

    val fileCount: Int get() = files.size

    fun folder(id: String): FolderRecord? = folders.firstOrNull { it.id == id }

    /** Every stored folder above [id], nearest first. A top-level folder yields an empty list. */
    fun ancestorsOf(id: String): List<FolderRecord> {
        val out = mutableListOf<FolderRecord>()
        var cursor = folder(id)?.parentId
        while (cursor != null) {
            val node = folder(cursor) ?: break
            out += node
            cursor = node.parentId
        }
        return out
    }

    fun childFolders(parentId: String?): List<FolderRecord> = folders.filter { it.parentId == parentId }

    fun childFiles(parentId: String?): List<FileRecord> = files.filter { it.folderId == parentId }

    /** `/Photos/2026/private.jpg`, as the unlocked browser shows it. */
    fun pathOf(fileId: ByteArray): String? {
        val record = files.firstOrNull { it.fileId.contentEquals(fileId) } ?: return null
        return pathOfFolder(record.folderId) + "/" + record.name
    }

    /** The empty string is the top of the realm; every folder below it has a leading-slash path. */
    fun pathOfFolder(id: String?): String {
        val node = id?.let(::folder) ?: return ""
        val parts = (ancestorsOf(node.id).reversed() + node).map { it.name }
        return "/" + parts.joinToString("/")
    }

    fun withFile(record: FileRecord): VaultIndex {
        val replaced = files.any { it.fileId.contentEquals(record.fileId) }
        return if (replaced) {
            copy(files = files.map { if (it.fileId.contentEquals(record.fileId)) record else it })
        } else {
            copy(files = files + record)
        }
    }

    fun withoutFile(fileId: ByteArray): VaultIndex = copy(files = files.filterNot { it.fileId.contentEquals(fileId) })

    fun withFolder(record: FolderRecord): VaultIndex {
        val existing = folder(record.id) ?: return copy(folders = folders + record)
        // The guard is on the record being written, not the one on file: re-parenting a folder under its
        // own descendant is the move that turns the tree into a loop, and a loop makes pathOfFolder spin.
        if (record.parentId != null) {
            require(record.parentId !in collectDescendantFolders(record.id)) { "move would create a cycle" }
        }
        return copy(folders = folders.map { if (it.id == record.id) record else it })
    }

    fun withoutFolder(id: String): VaultIndex {
        val doomed = collectDescendantFolders(id)
        require(files.none { it.folderId in doomed }) { "refusing to drop a folder that still holds files" }
        return copy(folders = folders.filterNot { it.id in doomed })
    }

    fun descendantsOf(id: String): List<FolderRecord> = collectDescendantFolders(id).drop(1).mapNotNull { folder(it) }

    private fun collectDescendantFolders(id: String): Set<String> {
        val doomed = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        queue += id
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!doomed.add(current)) continue
            folders.filter { it.parentId == current }.forEach { queue += it.id }
        }
        return doomed
    }
}

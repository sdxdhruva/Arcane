package dev.arcane.vault.store

import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.format.ChunkGeometry
import dev.arcane.vault.format.Hex
import dev.arcane.vault.format.VaultFormat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Opaque blob storage across the 256 buckets. This layer knows nothing about files, chunks, names or
 * formats: it moves byte arrays in and out of randomly named `.bin` files. That ignorance is the
 * point — nothing here can leak structure because nothing here knows there is any.
 */
interface BlobStore {
    fun exists(blobId: ByteArray): Boolean

    /** Cipher bytes on disk, i.e. padded payload plus the GCM tag. */
    fun size(blobId: ByteArray): Long

    fun read(blobId: ByteArray): ByteArray

    /** `[offset, offset + length)` of the blob, without reading the rest of it. */
    fun read(blobId: ByteArray, offset: Long, length: Int): ByteArray

    fun write(blobId: ByteArray, payload: ByteArray)

    /** Best-effort overwrite, then unlink. Returns false if the blob was not there. */
    fun delete(blobId: ByteArray): Boolean

    /** Every blob id currently on disk, in unspecified order. */
    fun blobIds(): List<ByteArray>

    /**
     * Settle durability for everything written since the last call. This is the commit boundary:
     * import does not fsync per chunk, it fsyncs once for the whole batch.
     */
    fun sync()
}

/**
 * @param directorySyncer flushes a directory's entries so renames and unlinks survive power loss.
 * Java has no portable way to do it — `FileChannel.open(dir, WRITE)` fails with EISDIR on Linux — so
 * Android supplies an implementation on `Os.open(O_RDONLY)` plus `Os.fsync`, and plain-JVM tests use
 * [DirectorySyncer.None].
 */
class FileBlobStore(
    private val layout: VaultLayout,
    private val directorySyncer: DirectorySyncer = DirectorySyncer.None,
) : BlobStore {

    private val touchedDirs = mutableSetOf<File>()

    override fun exists(blobId: ByteArray): Boolean = target(blobId).isFile

    override fun size(blobId: ByteArray): Long = target(blobId).length()

    override fun read(blobId: ByteArray): ByteArray {
        val file = target(blobId)
        if (!file.isFile) throw BlobNotFoundException(blobId)
        RandomAccessFile(file, "r").use { raf ->
            val out = ByteArray(raf.length().toInt())
            raf.readFully(out)
            return out
        }
    }

    override fun read(blobId: ByteArray, offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0) { "negative range" }
        val file = target(blobId)
        if (!file.isFile) throw BlobNotFoundException(blobId)
        RandomAccessFile(file, "r").use { raf ->
            if (offset + length > raf.length()) throw BlobNotFoundException(blobId)
            raf.seek(offset)
            val out = ByteArray(length)
            raf.readFully(out, 0, length)
            return out
        }
    }

    override fun write(blobId: ByteArray, payload: ByteArray) {
        val bucketDir = layout.bucketDir(ChunkGeometry.bucketName(blobId))
        if (!bucketDir.isDirectory) {
            if (!bucketDir.mkdirs() && !bucketDir.isDirectory) {
                throw IOException("could not create bucket ${bucketDir.name}")
            }
            // Creating a bucket changes the vault root's own entry list, so the root needs its own flush.
            synchronized(this) { touchedDirs += layout.vaultRoot }
        }
        // A temp file with a fresh random blob name in the same bucket: renames across mount points
        // are not atomic, and a distinctive `.tmp` name would mark interrupted writes as exactly that.
        val temp = File(bucketDir, ChunkGeometry.blobName(ChunkGeometry.newBlobId()))
        try {
            FileOutputStream(temp).use { out ->
                out.write(payload)
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(target(blobId))) throw IOException("could not commit blob write")
            synchronized(this) { touchedDirs += bucketDir }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    /**
     * Overwriting a blob's bytes is the vault-private analogue of secure delete. The limits are real:
     * f2fs and journalled ext4 may relocate blocks, so chip-level read-back can still find the old
     * bytes. What this does defeat is the cheap case — a deleted file whose content is still there.
     */
    override fun delete(blobId: ByteArray): Boolean {
        val file = target(blobId)
        if (!file.isFile) return false
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val length = raf.length()
                if (length > 0) {
                    raf.seek(0)
                    raf.write(Secrets.randomBytes(length.toInt()))
                    raf.fd.sync()
                    raf.setLength(0)
                }
            }
        } catch (_: IOException) {
            // Fall through to unlink: a blob that is gone is better than one that is merely stubborn.
        }
        val bucketDir = file.parentFile
        val removed = file.delete()
        if (removed && bucketDir != null) {
            synchronized(this) { touchedDirs += bucketDir }
            if (bucketDir.list()?.isEmpty() == true) {
                bucketDir.delete()
                synchronized(this) { touchedDirs += layout.vaultRoot }
            }
        }
        return removed
    }

    override fun blobIds(): List<ByteArray> =
        layout.allBlobFiles().mapNotNull { file ->
            runCatching { Hex.decode(file.name.removeSuffix(VaultFormat.BLOB_SUFFIX)) }.getOrNull()
        }

    override fun sync() {
        val dirs: List<File>
        synchronized(this) {
            dirs = touchedDirs.toList()
            touchedDirs.clear()
        }
        dirs.forEach(directorySyncer::sync)
    }

    private fun target(blobId: ByteArray): File = layout.blobFile(blobId)
}

class BlobNotFoundException(blobId: ByteArray) :
    IOException("blob ${Hex.lower(blobId)} is not present")

/** Flushes a directory so pending renames and unlinks are durable. */
fun interface DirectorySyncer {
    fun sync(dir: File)

    companion object {
        /** Plain JVM has no portable directory fsync, and host tests do not need power-loss semantics. */
        val None = DirectorySyncer { }
    }
}

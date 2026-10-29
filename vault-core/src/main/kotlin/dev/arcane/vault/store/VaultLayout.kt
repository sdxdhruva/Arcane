package dev.arcane.vault.store

import dev.arcane.vault.format.ChunkGeometry
import dev.arcane.vault.format.VaultFormat
import java.io.File

/**
 * Concrete on-disk placement for the scattered chunks. Every name here is meaningless on purpose:
 * no path in the vault says "secret", "vault" or "encrypted", and the suffix shared by chunk blobs,
 * index blobs and half-written temp files means a completed blob and an interrupted one look alike.
 *
 * @param privateRoot the app's internal files directory. Nothing in the vault is ever written to
 * shared or external storage.
 */
class VaultLayout(private val privateRoot: File) {

    val vaultRoot: File get() = File(privateRoot, DIR_VAULT)

    val kdfHeader: File get() = File(vaultRoot, FILE_KDF)

    /** Plaintext only ever lands here, and only for viewers that genuinely need a seekable fd. */
    val tempPlainRoot: File get() = File(privateRoot, DIR_TEMP)

    fun bucketDir(bucket: String): File = File(vaultRoot, bucket)

    fun blobFile(bucket: String, blobName: String): File = File(bucketDir(bucket), blobName)

    fun blobFile(blobId: ByteArray): File =
        blobFile(ChunkGeometry.bucketName(blobId), ChunkGeometry.blobName(blobId))

    /** Existing buckets only — created on first use so an unused vault leaves no 256 empty dirs. */
    fun existingBuckets(): List<File> =
        vaultRoot.listFiles { f -> f.isDirectory && f.name.length == 2 }?.toList() ?: emptyList()

    /** Every blob anywhere in the vault, used by reconcile and by the plaintext-bleed scan. */
    fun allBlobFiles(): List<File> =
        existingBuckets().flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(VaultFormat.BLOB_SUFFIX) }?.toList()
                ?: emptyList()
        }

    private companion object {
        const val DIR_VAULT = "vlt"
        const val DIR_TEMP = "cache/vtmp"
        const val FILE_KDF = "kdf"
    }
}

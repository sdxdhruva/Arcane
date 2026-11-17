package dev.arcane.data.backup

import android.content.Context
import android.net.Uri
import dev.arcane.platform.VaultDevice
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.store.VaultLayout
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Handles exporting and importing encrypted `.arcane` backup archives.
 *
 * An `.arcane` archive packages the vault's KDF envelope and all encrypted chunk blobs
 * directly from disk. Since every blob is already encrypted with ChaCha20-Poly1305 and the
 * KDF header is protected by Argon2id, the resulting backup file is completely safe to store
 * on external storage, USB drives, or cloud providers without any plaintext leaks.
 */
class VaultBackupManager(
    private val context: Context,
    private val device: VaultDevice,
) {
    private val layout: VaultLayout get() = device.layout

    data class BackupProgress(
        val itemsDone: Int,
        val totalItems: Int,
        val bytesDone: Long,
        val totalBytes: Long,
    )

    sealed interface BackupResult {
        data class Success(val fileCount: Int, val totalBytes: Long) : BackupResult
        data class Error(val message: String) : BackupResult
    }

    sealed interface RestoreResult {
        data class Success(val fileCount: Int, val totalBytes: Long) : RestoreResult
        data class Error(val message: String) : RestoreResult
    }

    /**
     * Exports the entire encrypted vault into the provided [targetUri] as a `.arcane` archive.
     */
    suspend fun exportBackup(
        targetUri: Uri,
        onProgress: (BackupProgress) -> Unit = {},
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val kdfFile = layout.kdfHeader
            if (!kdfFile.exists() || kdfFile.length() == 0L) {
                return@withContext BackupResult.Error("No vault found to back up.")
            }

            val blobFiles = layout.allBlobFiles()
            val totalItems = blobFiles.size + 1 // +1 for kdf
            val totalBytes = kdfFile.length() + blobFiles.sumOf { it.length() }

            val outStream = context.contentResolver.openOutputStream(targetUri)
                ?: return@withContext BackupResult.Error("Failed to open backup destination.")

            ZipOutputStream(BufferedOutputStream(outStream)).use { zipOut ->
                // Write backup metadata header
                val metaJson = JSONObject().apply {
                    put("magic", MAGIC_HEADER)
                    put("version", BACKUP_VERSION)
                    put("appVersion", "1.0.3")
                    put("timestamp", System.currentTimeMillis())
                    put("totalItems", totalItems)
                    put("totalBytes", totalBytes)
                }.toString()

                zipOut.putNextEntry(ZipEntry(ENTRY_META))
                zipOut.write(metaJson.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()

                var itemsDone = 0
                var bytesDone = 0L

                // 1. Pack KDF Header
                zipOut.putNextEntry(ZipEntry(ENTRY_KDF))
                FileInputStream(kdfFile).use { input ->
                    bytesDone += input.copyTo(zipOut)
                }
                zipOut.closeEntry()
                itemsDone++
                onProgress(BackupProgress(itemsDone, totalItems, bytesDone, totalBytes))

                // 2. Pack all encrypted blobs into their respective bucket directories
                val buffer = ByteArray(64 * 1024)
                for (blob in blobFiles) {
                    val bucketName = blob.parentFile?.name ?: "00"
                    val entryName = "blobs/$bucketName/${blob.name}"
                    zipOut.putNextEntry(ZipEntry(entryName))

                    FileInputStream(blob).use { input ->
                        var read: Int
                        while (input.read(buffer).also { read = it } >= 0) {
                            if (read > 0) {
                                zipOut.write(buffer, 0, read)
                                bytesDone += read
                            }
                        }
                    }
                    zipOut.closeEntry()
                    itemsDone++
                    onProgress(BackupProgress(itemsDone, totalItems, bytesDone, totalBytes))
                }
            }

            BackupResult.Success(fileCount = totalItems, totalBytes = totalBytes)
        } catch (e: Exception) {
            BackupResult.Error("Backup export failed: ${e.message ?: "Unknown error"}")
        }
    }

    /**
     * Restores an encrypted `.arcane` archive from [sourceUri] into the vault storage.
     */
    suspend fun restoreBackup(
        sourceUri: Uri,
        onProgress: (BackupProgress) -> Unit = {},
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val inStream = context.contentResolver.openInputStream(sourceUri)
                ?: return@withContext RestoreResult.Error("Failed to open backup source.")

            var isArcaneBackup = false
            var restoredItems = 0
            var restoredBytes = 0L

            // Ensure root directory exists
            layout.vaultRoot.mkdirs()

            ZipInputStream(BufferedInputStream(inStream)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == ENTRY_META -> {
                            val metaContent = zipIn.readBytes().toString(Charsets.UTF_8)
                            val meta = JSONObject(metaContent)
                            if (meta.optString("magic") == MAGIC_HEADER) {
                                isArcaneBackup = true
                            }
                        }
                        name == ENTRY_KDF -> {
                            isArcaneBackup = true
                            val kdfTarget = layout.kdfHeader
                            kdfTarget.parentFile?.mkdirs()
                            FileOutputStream(kdfTarget).use { out ->
                                val copied = zipIn.copyTo(out)
                                restoredBytes += copied
                            }
                            restoredItems++
                            onProgress(BackupProgress(restoredItems, -1, restoredBytes, -1))
                        }
                        name.startsWith("blobs/") -> {
                            // Format: blobs/{bucket}/{blobName}
                            val parts = name.split("/")
                            if (parts.size >= 3) {
                                val bucket = parts[1]
                                val blobName = parts[2]
                                val bucketDir = layout.bucketDir(bucket)
                                bucketDir.mkdirs()
                                val blobFile = layout.blobFile(bucket, blobName)
                                FileOutputStream(blobFile).use { out ->
                                    val copied = zipIn.copyTo(out)
                                    restoredBytes += copied
                                }
                                restoredItems++
                                onProgress(BackupProgress(restoredItems, -1, restoredBytes, -1))
                            }
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            if (!isArcaneBackup) {
                return@withContext RestoreResult.Error("The selected file is not a valid Arcane backup archive.")
            }

            RestoreResult.Success(fileCount = restoredItems, totalBytes = restoredBytes)
        } catch (e: Exception) {
            RestoreResult.Error("Backup restore failed: ${e.message ?: "Corrupted archive"}")
        }
    }

    companion object {
        const val MAGIC_HEADER = "ARCANE_BACKUP"
        const val BACKUP_VERSION = 1
        const val ENTRY_META = "arcane.meta"
        const val ENTRY_KDF = "kdf"
    }
}

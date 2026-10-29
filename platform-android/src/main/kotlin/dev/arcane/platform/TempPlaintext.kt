package dev.arcane.platform

import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.file.ChunkMap
import dev.arcane.vault.file.ChunkReader
import dev.arcane.vault.format.Hex
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.store.VaultLayout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The one place plaintext is allowed to exist on a device, and only for a viewer that genuinely needs a
 * seekable file descriptor — `PdfRenderer` and any ContentResolver-backed decoder are the honest list.
 * Everything else reads through [ChunkReader] straight from the blob pool and never touches this.
 *
 * Files here are randomly named with the vault's own `.bin` suffix, so a temp plaintext looks like every
 * other blob in a directory listing, and they are overwritten before they are unlinked. The limit is
 * stated plainly: while a viewer is open, the plaintext exists on flash, and no app-level trick changes
 * what a block-level read of that region can find.
 */
class TempPlaintext(private val layout: VaultLayout) {

    /** Streams the whole file out as plaintext and hands back a readable, seekable [File]. */
    fun materialize(
        reader: ChunkReader,
        map: ChunkMap,
        onProgress: (bytesDone: Long) -> Unit = {},
        isOpen: () -> Boolean = { true },
    ): File {
        val dir = layout.tempPlainRoot.also { it.mkdirs() }
        val file = File(dir, Hex.lower(Secrets.randomBytes(VaultFormat.BLOB_ID_BYTES)) + VaultFormat.BLOB_SUFFIX)
        FileOutputStream(file).use { out ->
            var offset = 0L
            while (offset < map.plainSize) {
                if (!isOpen()) throw IOException("viewer went away mid-materialise")
                val count = minOf(CHUNK.toLong(), map.plainSize - offset).toInt()
                val plain = reader.readAt(map, offset, count)
                out.write(plain)
                Secrets.wipe(plain)
                offset += count
                onProgress(offset)
            }
            out.flush()
            out.fd.sync()
        }
        return file
    }

    fun destroy(file: File) {
        overwrite(file)
        file.delete()
    }

    /** Leftovers from a killed process: same treatment, because a stale temp file is still plaintext. */
    fun sweepStale(): Int {
        val dir = layout.tempPlainRoot
        val stale = dir.listFiles { f -> f.isFile }?.toList() ?: emptyList()
        stale.forEach { overwrite(it); it.delete() }
        return stale.size
    }

    private fun overwrite(file: File) {
        runCatching {
            val length = file.length()
            if (length > 0L) {
                java.io.RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(0)
                    var remaining = length
                    while (remaining > 0L) {
                        val toWrite = minOf(CHUNK.toLong(), remaining).toInt()
                        val garbage = Secrets.randomBytes(toWrite)
                        raf.write(garbage, 0, toWrite)
                        Secrets.wipe(garbage)
                        remaining -= toWrite
                    }
                    raf.fd.sync()
                    raf.setLength(0)
                }
            }
        }
    }

    private companion object {
        const val CHUNK = VaultFormat.CHUNK_PAYLOAD_BYTES
    }
}

package dev.arcane.platform

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.arcane.vault.store.DirectorySyncer
import java.io.File

/**
 * `fsync` on a directory fd — the only way a rename or an unlink survives power loss, and the one call
 * Java has no portable way to make (`FileChannel.open(dir, WRITE)` fails with EISDIR on Linux).
 *
 * A directory that cannot be synced is skipped rather than raised: the blob bytes themselves are already
 * durable, because every write fsyncs the file before renaming it. Losing the sync costs a possible
 * missing rename after an abrupt power cut, not a corrupted file, and refusing the user's import over it
 * would be the worse trade.
 */
class AndroidDirectorySyncer : DirectorySyncer {

    override fun sync(dir: File) {
        val fd = try {
            Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
        } catch (_: ErrnoException) {
            return
        }
        try {
            Os.fsync(fd)
        } catch (_: ErrnoException) {
            // Best effort, as documented above.
        } finally {
            try {
                Os.close(fd)
            } catch (_: ErrnoException) {
                // Nothing actionable; the fd is going away with the process anyway.
            }
        }
    }
}

package dev.arcane.platform

import android.content.Context
import dev.arcane.vault.file.ChunkCache
import dev.arcane.vault.index.IndexStore
import dev.arcane.vault.realm.RealmHeaderStore
import dev.arcane.vault.realm.RealmOpener
import dev.arcane.vault.realm.UnlockedRealm
import dev.arcane.vault.realm.VaultRealm
import dev.arcane.vault.realm.WriteJournal
import dev.arcane.vault.store.BlobStore
import dev.arcane.vault.store.FileBlobStore
import dev.arcane.vault.store.VaultLayout
import java.io.File

/**
 * The one place that knows where a vault lives on an Android device, assembled once per process.
 *
 * The root is `noBackupFilesDir`, and that choice carries weight: a vault restored onto a second phone
 * by a cloud backup would be a pile of blobs the user has no way to name, and Auto Restore would present
 * it as if it were a working vault. Excluded from backup, the blobs stay exactly where the passphrase
 * that unlocks them is.
 */
class VaultDevice private constructor(private val root: File) {

    val layout: VaultLayout = VaultLayout(root)
    val blobs: BlobStore = FileBlobStore(layout, AndroidDirectorySyncer())
    val headers: RealmHeaderStore = RealmHeaderStore(layout)
    val indexStore: IndexStore = IndexStore(blobs)
    val journal: WriteJournal = WriteJournal(blobs)
    val opener: RealmOpener = RealmOpener(blobs, headers, indexStore)

    /**
     * Shared across every viewer on purpose: the same chunk a browser just decrypted is the chunk the
     * player wants next, and lock-time teardown has exactly one place to wipe.
     */
    val chunkCache: ChunkCache = ChunkCache(blobs)

    /** The only place plaintext is allowed to reach flash, and only for viewers that need an fd. */
    val tempPlaintext: TempPlaintext = TempPlaintext(layout)

    /** An operation handle on a realm the caller has already unlocked and authenticated. */
    fun facade(realm: UnlockedRealm): VaultRealm = VaultRealm(realm, blobs, indexStore, journal)

    companion object {
        @Volatile
        private var instance: VaultDevice? = null

        fun of(context: Context): VaultDevice {
            val root = context.applicationContext.noBackupFilesDir
            return instance ?: synchronized(this) {
                instance ?: VaultDevice(root).also { instance = it }
            }
        }
    }
}

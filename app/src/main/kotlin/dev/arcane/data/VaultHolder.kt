package dev.arcane.data

import dev.arcane.platform.VaultDevice
import dev.arcane.vault.realm.UnlockedRealm
import dev.arcane.vault.realm.VaultRealm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The open realm. There is exactly one thing here: if this is non-null, the encrypted folder is on screen and
 * the window has to behave like it — no screenshots, no recents thumbnail, no key left behind.
 */
class OpenedVault internal constructor(
    val realm: VaultRealm,
    private val unlocked: UnlockedRealm,
) {
    val slotIndex: Int get() = unlocked.slotIndex

    internal fun wipe() = unlocked.wipe()
}

/** Which realm is open, if any. Everything the vault screens do is answered from here. */
class VaultHolder(private val device: VaultDevice) {

    private val _opened = MutableStateFlow<OpenedVault?>(null)
    val opened: StateFlow<OpenedVault?> = _opened.asStateFlow()

    val current: OpenedVault? get() = _opened.value

    /** Adopts an authenticated realm, wiping any previous one first: there is only ever one open realm. */
    fun adopt(unlocked: UnlockedRealm): OpenedVault {
        takeCurrent()?.wipe()
        val opened = OpenedVault(device.facade(unlocked), unlocked)
        _opened.value = opened
        return opened
    }

    /**
     * The lock. Idempotent, and the order matters: stop the work, drop the keys, then the plaintext the
     * work left in RAM and in the temp directory.
     */
    fun lock(): Int {
        val opened = takeCurrent() ?: return 0
        opened.wipe()
        device.chunkCache.wipe()
        return device.tempPlaintext.sweepStale()
    }

    /**
     * Detaches the open realm and hands it back. The value assignment is atomic on its own; what is not
     * atomic is read-then-clear, and that gap only ever costs a vault thread one more chunk before it sees
     * a locked realm — which [dev.arcane.vault.realm.VaultRealm] already refuses to write.
     */
    private fun takeCurrent(): OpenedVault? {
        val previous = _opened.value
        _opened.value = null
        return previous
    }
}

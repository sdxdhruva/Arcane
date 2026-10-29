package dev.arcane.vault.realm

import dev.arcane.vault.crypto.KdfParams
import dev.arcane.vault.crypto.KeyDerivation
import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.index.GenerationRecord
import dev.arcane.vault.index.IndexStore
import dev.arcane.vault.index.VaultIndex
import dev.arcane.vault.store.BlobStore
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Everything a caller needs after a successful unlock. The master key lives in a zeroizable array, never
 * a String, and [wipe] is what the lifecycle observer calls when the app goes to the background, the
 * screen turns off, or the idle timeout fires.
 */
class UnlockedRealm(
    val slotIndex: Int,
    val masterKey: ByteArray,
    val realmId: ByteArray,
    val index: VaultIndex,
    val generation: GenerationRecord,
) {
    init {
        require(masterKey.size == 32) { "master key must be 32 bytes" }
    }

    fun fileKey(fileId: ByteArray): ByteArray = KeyDerivation.deriveFileKey(masterKey, fileId)

    /**
     * True once [wipe] has run. A zeroed master key is still a perfectly usable HKDF input — it derives a
     * different, equally valid key — so "locked" cannot be detected by a failure downstream. It has to be
     * asked, which is what lets a commit refuse instead of writing an anchor that no passphrase can reach.
     */
    val isLocked: Boolean get() = masterKey.all { it == 0.toByte() }

    fun wipe() {
        Secrets.wipe(masterKey)
        Secrets.wipe(realmId)
    }
}

/**
 * Turns a passphrase into a realm. There is no realm label on disk and no hint of which slot is the
 * decoy: a candidate key is tested by whether it can authenticate that realm's index anchor, which is
 * exactly the property an attacker without the passphrase cannot fabricate.
 */
class RealmOpener(
    private val blobs: BlobStore,
    private val headers: RealmHeaderStore,
    private val indexStore: IndexStore = IndexStore(blobs),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** Creates a realm in [slotIndex], committing an empty index so the anchor exists from the start. */
    fun initialise(
        slotIndex: Int,
        passphrase: ByteArray,
        params: KdfParams,
        emptyIndex: VaultIndex = VaultIndex(),
    ): UnlockedRealm {
        headers.initialiseIfAbsent()
        val masterKey = KeyDerivation.argon2id(passphrase, params)
        val realmId = KeyDerivation.deriveRealmId(masterKey, params.salt)
        if (blobs.exists(indexStore.anchorBlobId(masterKey, realmId))) {
            Secrets.wipe(masterKey)
            Secrets.wipe(realmId)
            throw RealmAlreadyExists(slotIndex)
        }
        headers.writeSlot(slotIndex, RealmSlot(VaultFormat.FORMAT_VERSION, params.salt, params.memoryKiB, params.iterations, params.parallelism))
        val generation = indexStore.commit(masterKey, realmId, emptyIndex, nowMillis())
        return UnlockedRealm(slotIndex, masterKey, realmId, emptyIndex, generation)
    }

    /** Null means the passphrase opened no realm, including when there is no realm to open. */
    fun unlock(passphrase: ByteArray): UnlockedRealm? {
        val slots = runCatching { headers.usableSlots() }.getOrElse { return null }
        for ((slotIndex, slot) in slots) {
            val masterKey = KeyDerivation.argon2id(passphrase, slot.kdfParams)
            val realmId = KeyDerivation.deriveRealmId(masterKey, slot.salt)
            val loaded = runCatching { indexStore.load(masterKey, realmId) }
                .getOrNull() // blob missing, garbage slot or failed authentication: all read as "not this one"
            if (loaded != null) {
                return UnlockedRealm(slotIndex, masterKey, realmId, loaded.first, loaded.second)
            }
            Secrets.wipe(masterKey)
            Secrets.wipe(realmId)
        }
        return null
    }

    /**
     * Reopens a realm from a master key the caller already holds. The app has no cached-key path — every
     * open goes through [unlock] — so this exists for tests and for any future in-process handoff where the
     * key is already derived, not for a shortcut around the passphrase.
     */
    fun unlockWithMasterKey(slotIndex: Int, masterKey: ByteArray, salt: ByteArray): UnlockedRealm {
        val realmId = KeyDerivation.deriveRealmId(masterKey, salt)
        val loaded = indexStore.load(masterKey, realmId) ?: throw RealmNotInitialised(slotIndex)
        return UnlockedRealm(slotIndex, masterKey, realmId, loaded.first, loaded.second)
    }

    /** Re-reads the index after a commit, keeping the caller's handle current without re-deriving keys. */
    fun reload(realm: UnlockedRealm): Pair<VaultIndex, GenerationRecord> {
        val loaded = indexStore.load(realm.masterKey, realm.realmId) ?: throw RealmNotInitialised(realm.slotIndex)
        return loaded
    }

}

class RealmAlreadyExists(slotIndex: Int) : Exception("slot $slotIndex already holds a realm")
class RealmNotInitialised(slotIndex: Int) : Exception("slot $slotIndex has no committed index")

/**
 * The coercion-tolerance rule: after [maxWrongAttempts] consecutive failures the app stops reporting errors
 * and opens plain browsing, so an observer sees a phone that simply unlocks. The failure count itself is
 * persisted by the app layer; this class only decides, which keeps the policy testable.
 *
 * The count is not a constant, and that is the point. A fixed three is a fact about the app that whoever is
 * holding the phone learns by counting their own failures — "two left, the third will show me something" —
 * while a per-install count somewhere in 2..5 is not learnable that way. It is derived from the header salt,
 * which is public bytes on disk, so the derivation costs nothing to hide: no threshold is persisted anywhere,
 * and reading the preferences file shows a failure counter and nothing else.
 *
 * After a failover the counter is parked one below the threshold rather than at zero. A reset to zero would
 * make the next wrong passphrase answer "Not recognised." to a phone that had just appeared to unlock itself,
 * which is the one moment the design cannot afford a visible state change; parked, the next wrong attempt
 * fails over again and the illusion holds for as long as the observer keeps trying.
 */
class FailoverPolicy(val maxWrongAttempts: Int = DEFAULT) {

    init {
        require(maxWrongAttempts in MIN_ATTEMPTS..MAX_ATTEMPTS) {
            "failover threshold must be between $MIN_ATTEMPTS and $MAX_ATTEMPTS"
        }
    }

    fun onWrongAttempt(consecutiveFailures: Int): FailoverOutcome =
        if (consecutiveFailures + 1 >= maxWrongAttempts) {
            FailoverOutcome.FailoverToDecoy(resetTo = maxWrongAttempts - 1)
        } else {
            FailoverOutcome.RejectAttempt(consecutiveFailures = consecutiveFailures + 1)
        }

    fun onRealmOpened(): FailoverOutcome = FailoverOutcome.Opened(resetTo = 0)

    companion object {
        const val MIN_ATTEMPTS = 2
        const val MAX_ATTEMPTS = 5
        const val DEFAULT = 3

        /**
         * The count this install will use, from the decoy realm's salt. A missing salt — a phone that has not
         * been set up yet, or a header that cannot be read — falls back to the middle of the range rather than
         * to the minimum, so a broken lookup never quietly makes the tell easier to count.
         */
        fun forSalt(salt: ByteArray?): FailoverPolicy =
            FailoverPolicy(if (salt == null || salt.isEmpty()) DEFAULT else attemptsFrom(salt))

        private fun attemptsFrom(salt: ByteArray): Int {
            val digest = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(THRESHOLD_KEY, "HmacSHA256"))
                doFinal(salt)
            }
            // 256 divides evenly by the span, so the remainder carries no bias into the count.
            val spread = MAX_ATTEMPTS - MIN_ATTEMPTS + 1
            return MIN_ATTEMPTS + ((digest[0].toInt() and 0xff) % spread)
        }

        /** Lives in the code, not on disk: knowing it requires the APK, not the phone. */
        private val THRESHOLD_KEY = byteArrayOf(
            0x51, 0x7a.toByte(), 0x2d, 0x68, 0x13, 0x7c, 0x40, 0x5e,
            0x27, 0x6b, 0x19, 0x34, 0x0c, 0x52, 0x61, 0x4f,
        )
    }
}

sealed interface FailoverOutcome {
    /** Show the generic "could not unlock" message and remember the strike. */
    data class RejectAttempt(val consecutiveFailures: Int) : FailoverOutcome

    /** Open the decoy realm with no error and no hint that anything else exists. */
    data class FailoverToDecoy(val resetTo: Int) : FailoverOutcome

    /** Open the realm the passphrase actually matched. */
    data class Opened(val resetTo: Int) : FailoverOutcome
}

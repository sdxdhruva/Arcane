package dev.arcane.data

import dev.arcane.platform.VaultDevice
import dev.arcane.platform.VaultSession
import dev.arcane.vault.crypto.KdfCalibrator
import dev.arcane.vault.crypto.KdfParams
import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.realm.FailoverOutcome
import dev.arcane.vault.realm.FailoverPolicy
import dev.arcane.vault.realm.RealmAlreadyExists
import dev.arcane.vault.realm.RealmSlot
import dev.arcane.vault.realm.UnlockedRealm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Where the passphrase screen stands after an attempt. */
sealed interface UnlockOutcome {
    /** The encrypted realm opened. Nothing about it is said on screen beyond "a folder opened". */
    data object Opened : UnlockOutcome

    /** Generic rejection. The count is kept for the policy, not shown: "2 tries left" would advertise a secret. */
    data class Rejected(val consecutiveFailures: Int) : UnlockOutcome

    /**
     * The strike count reached the number drawn for this phone, so the app shows the phone's own files instead
     * and shows no error. This is not a realm and holds no key: there is nothing here to confess to.
     */
    data object FailedOver : UnlockOutcome

    /** No realm exists yet, so the setup flow takes over. */
    data object NeedsSetup : UnlockOutcome

    /** Something that is not the user's fault: unreadable header, no writable storage, disk full. */
    data class Broken(val reason: String) : UnlockOutcome
}

sealed interface SetupOutcome {
    data class Opened(val realSlot: Int) : SetupOutcome
    data class Rejected(val reason: SetupReason) : SetupOutcome
}

enum class SetupReason { PASSPHRASE_TOO_SHORT, VAULT_ALREADY_EXISTS, COULD_NOT_CREATE }

/** The stages of the one slow flow in the app, so the screen can say what it is waiting for. */
enum class SetupStage { TUNING, CREATING }

/**
 * Opens the realm, and decides what a wrong passphrase means.
 *
 * The strike rule lives here rather than on the screen because it has to outlive the screen: the count is
 * persisted, so closing the app and reopening it does not hand a fourth try to whoever is holding the phone.
 * The strike that lands the failover is derived per install, somewhere in two to five, so nobody learns it by
 * counting.
 *
 * What it lands *on* is the phone's own media, unencrypted, with no error message — not a second encrypted
 * folder. A second realm would have to look like the first one to do any good, and once it looks like the
 * vault it stops being a safe answer; the plain browser needs no key, no cache and no seeding, and it cannot
 * be confessed to because it holds nothing. [onFailover] is how the graph is told to put that browser up.
 */
class UnlockEngine(
    private val device: VaultDevice,
    private val settings: SettingsStore,
    private val holder: VaultHolder,
    private val session: VaultSession,
    private val repository: VaultRepository,
    private val onFailover: () -> Unit,
    private val argonDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * The realm's salt, read fresh: it is public bytes on disk and it is what the strike count is derived
     * from, so a header that cannot be read degrades to the middle of the range rather than throwing at the
     * worst moment — a wrong passphrase in front of someone.
     */
    private suspend fun realmSalt(slot: Int?): ByteArray? {
        if (slot == null) return null
        return runCatching {
            withContext(argonDispatcher) {
                device.headers.usableSlots().firstOrNull { it.first == slot }?.second?.salt
            }
        }.getOrNull()
    }

    /** The typed-passphrase path, and the one that decides whether this attempt was the last. */
    suspend fun unlock(passphrase: String): UnlockOutcome {
        val prefs = settings.current()
        if (!prefs.isSetUp) return UnlockOutcome.NeedsSetup
        val bytes = passphrase.toByteArray(Charsets.UTF_8)
        val attempt = try {
            withContext(argonDispatcher) { Result.success(device.opener.unlock(bytes)) }
        } catch (failure: Throwable) {
            Result.failure(failure)
        } finally {
            Secrets.wipe(bytes)
        }
        val anyRealm = attempt.exceptionOrNull()?.let { return UnlockOutcome.Broken(it.readable()) }
            ?: attempt.getOrNull()
        // Only the realm this install recorded is a door this app has. An earlier build put a second,
        // decoy-named realm in another slot, and a passphrase that opens *that* must not be allowed to
        // arrive at the vault browser — from the outside it would be indistinguishable from the failover
        // opening the real one, which is the exact behaviour this flow exists to make unreadable.
        val realm = anyRealm?.takeIf { it.slotIndex == prefs.realSlot }
        if (anyRealm != null && anyRealm !== realm) {
            withContext(argonDispatcher) { anyRealm.wipe() }
        }
        if (realm != null) return succeed(realm)

        return when (val outcome = FailoverPolicy.forSalt(realmSalt(prefs.realSlot)).onWrongAttempt(prefs.wrongAttempts)) {
            is FailoverOutcome.FailoverToDecoy -> {
                settings.setWrongAttempts(outcome.resetTo)
                onFailover()
                UnlockOutcome.FailedOver
            }
            is FailoverOutcome.RejectAttempt -> {
                settings.setWrongAttempts(outcome.consecutiveFailures)
                UnlockOutcome.Rejected(outcome.consecutiveFailures)
            }
            is FailoverOutcome.Opened -> UnlockOutcome.Rejected(prefs.wrongAttempts)
        }
    }

    /** The realm the app is currently in, if any. */
    fun currentSlot(): Int? = holder.current?.slotIndex

    /** Create the realm, and hand the user standing inside it. */
    suspend fun setUp(
        passphrase: String,
        onStage: (SetupStage) -> Unit = {},
    ): SetupOutcome {
        if (passphrase.length < MIN_LENGTH) return SetupOutcome.Rejected(SetupReason.PASSPHRASE_TOO_SHORT)

        onStage(SetupStage.TUNING)
        val cost = withContext(argonDispatcher) { KdfCalibrator().calibrate() }

        onStage(SetupStage.CREATING)
        val bytes = passphrase.toByteArray(Charsets.UTF_8)
        val realm = try {
            withContext(argonDispatcher) { device.opener.initialise(SLOT_VAULT, bytes, cost.withFreshSalt()) }
        } catch (failure: RealmAlreadyExists) {
            // The prefs were cleared but the blobs were not, which is a restore, not a first run.
            return SetupOutcome.Rejected(SetupReason.VAULT_ALREADY_EXISTS)
        } catch (failure: Throwable) {
            return SetupOutcome.Rejected(SetupReason.COULD_NOT_CREATE)
        } finally {
            Secrets.wipe(bytes)
        }

        settings.recordSetup(realSlot = SLOT_VAULT)
        succeed(realm)
        return SetupOutcome.Opened(SLOT_VAULT)
    }

    /** The on-screen lock button, and everything that decides to hand the phone back. */
    fun lockNow() = session.lockNow()

    /** Trigger failover immediately (e.g. from safety timeout). */
    fun failover() = onFailover()

    private suspend fun succeed(realm: UnlockedRealm): UnlockOutcome = withContext(NonCancellable) {
        settings.setWrongAttempts(0)
        holder.adopt(realm)
        session.adopt()
        // The journal is read before the listing is published, so the first frame a user sees already knows
        // whether an import from the last session is waiting to be finished.
        repository.reconcile()
        repository.show(null)
        UnlockOutcome.Opened
    }

    private fun KdfParams.withFreshSalt(): KdfParams =
        KdfParams(Secrets.randomBytes(RealmSlot.SALT_BYTES), memoryKiB, iterations, parallelism)

    private fun Throwable.readable(): String = message ?: javaClass.simpleName

    private companion object {
        const val MIN_LENGTH = 8

        /** Slot 1 rather than 0 because the first build of this app put a decoy realm in slot 0; that slot
         * may still hold it, and the app must never mistake it for the vault. */
        const val SLOT_VAULT = 1
    }
}

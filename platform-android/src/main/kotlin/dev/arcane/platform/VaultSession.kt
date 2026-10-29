package dev.arcane.platform

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dev.arcane.vault.session.Cancellable
import dev.arcane.vault.session.SessionGuard

/**
 * [SessionGuard] attached to a real clock and the real app lifecycle, which is the only thing that knows
 * the difference between "the user rotated the phone" and "the user left the app".
 *
 * [adopt] is the unlock moment: the timers only exist once a realm is open, so a locked app can never
 * "expire". Call [touch] from the UI on any interaction, and let the [onLock] callback be the single place
 * that wipes keys, clears the chunk cache and releases whatever is playing.
 *
 * `ON_STOP` rather than `ON_PAUSE`: a permission dialog, an incoming call header or the notification shade
 * pauses the activity without putting anyone else's eyes on the screen, and locking through those would train
 * the user to think the lock is noise. The background grace itself is zero, so the real exposure window is
 * only the ~700 ms that `ProcessLifecycleOwner` debounces `ON_STOP` by to survive a configuration change.
 *
 * Anything the app launches on purpose — a file pick, an export destination — is exempted by [beginHandoff]
 * for as long as [SessionGuard] allows, because an instant lock that fires mid-pick is a lock the user pays
 * for twice: once when the picker comes back to a gate, and once when they pick the same file again.
 */
class VaultSession(
    private val onLock: () -> Unit,
    idleTimeoutMillis: Long = SessionGuard.DEFAULT_IDLE_MILLIS,
    backgroundGraceMillis: Long = SessionGuard.DEFAULT_BACKGROUND_GRACE_MILLIS,
    handoffCeilingMillis: Long = SessionGuard.DEFAULT_HANDOFF_CEILING_MILLIS,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val guard = SessionGuard(
        lock = onLock,
        schedule = ::scheduleAfter,
        idleTimeoutMillis = idleTimeoutMillis,
        backgroundGraceMillis = backgroundGraceMillis,
        handoffCeilingMillis = handoffCeilingMillis,
    )
    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> guard.returnedToForeground()
            Lifecycle.Event.ON_STOP -> guard.leftForeground()
            else -> Unit
        }
    }

    val isOpen: Boolean get() = guard.isOpen

    /** From the Application, on the main thread: start watching the foreground. */
    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    }

    fun detach() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
        guard.lockNow()
    }

    fun adopt() = guard.adopt()

    fun touch() = guard.touch()

    /**
     * Wrap a for-result launch: [beginHandoff] immediately before `launch(...)`, [endHandoff] as the first act
     * of the result callback. This is the only way the app survives its own file picker now that leaving the
     * foreground locks instantly — see [dev.arcane.vault.session.SessionGuard] for why the exemption is
     * bounded rather than open-ended.
     */
    fun beginHandoff() = guard.beginHandoff()

    fun endHandoff() = guard.endHandoff()

    fun lockNow() = guard.lockNow()

    private fun scheduleAfter(delayMillis: Long, action: () -> Unit): Cancellable {
        val task = Runnable { action() }
        handler.postDelayed(task, delayMillis)
        return Cancellable { handler.removeCallbacks(task) }
    }
}

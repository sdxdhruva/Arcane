package dev.arcane.vault.session

/** A pending lock that has not fired yet. */
fun interface Cancellable {
    fun cancel()
}

/**
 * When an open realm stops being open. The unlock screen is only the first door; the one that matters is
 * this, because an unlocked vault is a window into everything the user is hiding, and the moment it is
 * worth protecting is the moment nobody is looking at it.
 *
 * Two timers, and the shorter one always wins:
 *
 * - [backgroundGraceMillis] starts when the app leaves the foreground — a recents swipe, a screen-off — and
 *   locks when it expires. It defaults to zero: a vault that is not on screen is a vault in someone else's
 *   hands. A configuration change cancels it, so a rotation does not log the user out.
 * - [idleTimeoutMillis] restarts on every interaction, so a long reading session stays open and a pocket
 *   does not.
 *
 * The one exception to the instant background lock is a [beginHandoff]: the app putting its own screen down
 * to ask the phone for something — a file pick, an export destination — is not the user leaving. During a
 * handoff the background lock waits for [handoffCeilingMillis] instead, which is a ceiling and not a
 * permission: a pick that is abandoned, or a picker the user walks away from, still locks the realm.
 *
 * [schedule] is injected rather than a Handler or a coroutine: the policy is the part worth testing, and a
 * test should be able to move the clock without waiting for it.
 */
class SessionGuard(
    private val lock: () -> Unit,
    private val schedule: (delayMillis: Long, action: () -> Unit) -> Cancellable,
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_MILLIS,
    private val backgroundGraceMillis: Long = DEFAULT_BACKGROUND_GRACE_MILLIS,
    private val handoffCeilingMillis: Long = DEFAULT_HANDOFF_CEILING_MILLIS,
) {
    init {
        require(idleTimeoutMillis > 0) { "idle timeout must be positive" }
        require(backgroundGraceMillis >= 0) { "background grace cannot be negative" }
        require(handoffCeilingMillis >= 0) { "handoff ceiling cannot be negative" }
    }

    var isOpen: Boolean = false
        private set

    private var pending: Cancellable? = null

    /**
     * Nested on purpose: two launchers can be outstanding at once — a pick whose result starts an export — and
     * the lock must not come back early when the first of them is answered.
     */
    private var handoffs = 0

    /**
     * Tracked because a handoff can end while the app is still off-screen — the picker dismisses itself, the
     * user lands somewhere else — and at that moment the exemption has already been spent. Without this the
     * ended handoff would hand the realm back the long idle window while it is not even being looked at.
     */
    private var inForeground = true

    /** The realm just opened: the clock starts running from here, not from process start. */
    fun adopt() {
        isOpen = true
        handoffs = 0
        inForeground = true
        arm(idleTimeoutMillis)
    }

    /** Any user interaction that means "still looking at this". */
    fun touch() {
        if (isOpen) arm(idleTimeoutMillis)
    }

    /**
     * The app is about to hand the screen to something it asked for. Call this immediately before the launch,
     * not after the result: the backgrounding is what it is trying to survive.
     */
    fun beginHandoff() {
        if (!isOpen) return
        handoffs++
    }

    /** The answer arrived, or the launch was called off. Normal rules resume at once. */
    fun endHandoff() {
        if (handoffs <= 0) return
        handoffs--
        if (isOpen && handoffs == 0) arm(if (inForeground) idleTimeoutMillis else backgroundGraceMillis)
    }

    fun leftForeground() {
        if (!isOpen) return
        inForeground = false
        arm(if (handoffs > 0) handoffCeilingMillis else backgroundGraceMillis)
    }

    /** Cancel a grace lock that has not fired yet and restart the idle clock. */
    fun returnedToForeground() {
        if (!isOpen) return
        inForeground = true
        arm(idleTimeoutMillis)
    }

    /** The lock button, and the decoy handover. Always safe to call, even when already locked. */
    fun lockNow() {
        disarm()
        handoffs = 0
        if (!isOpen) return
        isOpen = false
        lock()
    }

    private fun arm(delayMillis: Long) {
        disarm()
        pending = schedule(delayMillis) { lockNow() }
    }

    private fun disarm() {
        pending?.cancel()
        pending = null
    }

    companion object {
        /** Comfortable for a file browser, short enough that a forgotten phone is not an open vault. */
        const val DEFAULT_IDLE_MILLIS = 90_000L

        /**
         * Zero. Leaving the app is the moment the vault is most exposed — it is on someone else's screen, or
         * about to be — and the rotation that used to need this window is already handled by the lifecycle
         * debounce and by the fact that the activity survives its own configuration changes.
         */
        const val DEFAULT_BACKGROUND_GRACE_MILLIS = 0L

        /**
         * How long an open realm may wait on a pick the app itself asked for. Two minutes is long enough to
         * browse a file tree for one document and short enough that a picker left open on someone else's phone
         * is not a vault left open on someone else's phone.
         */
        const val DEFAULT_HANDOFF_CEILING_MILLIS = 120_000L
    }
}

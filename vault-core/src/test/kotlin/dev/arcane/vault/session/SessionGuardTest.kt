package dev.arcane.vault.session

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * Drives the guard with a clock the test owns, so the assertions are about policy — what fires, when and
 * how many times — and never about how fast the machine happens to be.
 */
private class FakeScheduler {
    class Pending(val delayMillis: Long, val action: () -> Unit)

    var lockCount = 0

    private val pending = mutableListOf<Pending>()

    fun schedule(delayMillis: Long, action: () -> Unit): Cancellable {
        val task = Pending(delayMillis, action)
        pending += task
        return Cancellable { pending.remove(task) }
    }

    /** Runs every armed task whose delay has elapsed, oldest first, like a real timer queue. */
    fun advance(millis: Long) {
        val due = pending.filter { it.delayMillis <= millis }.sortedBy { it.delayMillis }
        due.forEach {
            pending.remove(it)
            it.action()
        }
    }

    fun armed(): List<Long> = pending.map { it.delayMillis }
}

class SessionGuardTest {

    private val scheduler = FakeScheduler()

    private fun guard(
        idle: Long = 90_000,
        grace: Long = SessionGuard.DEFAULT_BACKGROUND_GRACE_MILLIS,
        handoff: Long = 120_000,
    ) = SessionGuard(
        lock = { scheduler.lockCount++ },
        schedule = scheduler::schedule,
        idleTimeoutMillis = idle,
        backgroundGraceMillis = grace,
        handoffCeilingMillis = handoff,
    )

    @Test
    fun `an open realm locks itself when nobody touches it`() {
        val session = guard(idle = 30_000)
        session.adopt()

        scheduler.advance(29_999)
        assertThat(scheduler.lockCount).isEqualTo(0)
        scheduler.advance(30_000)

        assertWithMessage("the idle window is a timeout, not a suggestion").that(scheduler.lockCount).isEqualTo(1)
        assertThat(session.isOpen).isFalse()
    }

    @Test
    fun `each interaction restarts the clock rather than stacking another timer`() {
        val session = guard(idle = 10_000)
        session.adopt()

        repeat(10) {
            scheduler.advance(9_000)
            session.touch()
        }
        scheduler.advance(9_000)

        assertThat(scheduler.lockCount).isEqualTo(0)
        assertWithMessage("and only one lock is ever pending")
            .that(scheduler.armed())
            .containsExactly(10_000L)

        scheduler.advance(10_000)
        assertThat(scheduler.lockCount).isEqualTo(1)
    }

    @Test
    fun `leaving the app locks on the spot`() {
        val session = guard(idle = 90_000)

        session.adopt()
        session.leftForeground()

        assertWithMessage("the default background grace is zero: a vault off-screen is a vault unattended")
            .that(scheduler.armed())
            .containsExactly(0L)
        scheduler.advance(0)
        assertThat(scheduler.lockCount).isEqualTo(1)
    }

    @Test
    fun `a pick the app asked for survives the backgrounding, up to a ceiling`() {
        val session = guard(handoff = 120_000)
        session.adopt()

        session.beginHandoff()
        session.leftForeground()

        assertWithMessage("the handoff window replaces the instant lock, it does not stack on top of it")
            .that(scheduler.armed())
            .containsExactly(120_000L)

        scheduler.advance(119_999)
        assertThat(scheduler.lockCount).isEqualTo(0)

        session.returnedToForeground()
        session.endHandoff()
        scheduler.advance(120_000)

        assertWithMessage("answering the pick goes back to the idle clock, not to nothing")
            .that(scheduler.lockCount)
            .isEqualTo(1)
    }

    @Test
    fun `a picker the user walks away from still locks`() {
        val session = guard(handoff = 120_000)
        session.adopt()

        session.beginHandoff()
        session.leftForeground()
        scheduler.advance(120_000)

        assertWithMessage("the ceiling is the whole point: an exemption nobody returns for is a hole")
            .that(scheduler.lockCount)
            .isEqualTo(1)
        assertThat(session.isOpen).isFalse()
    }

    @Test
    fun `a handoff that ends off-screen does not buy back the idle window`() {
        val session = guard(idle = 90_000, grace = 0, handoff = 120_000)
        session.adopt()

        session.beginHandoff()
        session.leftForeground()
        session.endHandoff()

        assertWithMessage("still backgrounded, so the instant lock applies again the moment the exemption ends")
            .that(scheduler.armed())
            .containsExactly(0L)
        scheduler.advance(0)
        assertThat(scheduler.lockCount).isEqualTo(1)
    }

    @Test
    fun `nested handoffs are released one at a time`() {
        val session = guard(handoff = 120_000)
        session.adopt()

        session.beginHandoff()
        session.beginHandoff()
        session.endHandoff()
        session.leftForeground()

        assertWithMessage("one pick outstanding still means the ceiling, not the instant lock")
            .that(scheduler.armed())
            .containsExactly(120_000L)

        scheduler.advance(120_000)
        assertThat(scheduler.lockCount).isEqualTo(1)
    }

    @Test
    fun `a handoff on a locked realm is refused`() {
        val session = guard()

        session.beginHandoff()
        session.adopt()

        assertWithMessage("beginHandoff before adopt is a no-op, so the realm still leaves on its idle clock")
            .that(scheduler.armed())
            .containsExactly(90_000L)

        session.leftForeground()
        scheduler.advance(0)
        assertThat(scheduler.lockCount).isEqualTo(1)
    }

    @Test
    fun `a rotation does not log the user out`() {
        val session = guard(grace = 1_500)
        session.adopt()

        session.leftForeground()
        scheduler.advance(1_200)
        session.returnedToForeground()
        scheduler.advance(89_999)

        assertThat(scheduler.lockCount).isEqualTo(0)
        assertWithMessage("back in the foreground means the idle clock runs again")
            .that(scheduler.armed())
            .containsExactly(90_000L)
    }

    @Test
    fun `the lock button fires once and a second press does nothing`() {
        val session = guard()
        session.adopt()

        session.lockNow()
        session.lockNow()
        scheduler.advance(90_000)

        assertWithMessage("an explicit lock also cancels the timer it replaced")
            .that(scheduler.lockCount)
            .isEqualTo(1)
        assertThat(session.isOpen).isFalse()
    }

    @Test
    fun `touching a locked session cannot reopen it`() {
        val session = guard()

        session.touch()
        session.leftForeground()
        session.returnedToForeground()
        scheduler.advance(1_500)

        assertWithMessage("nothing was ever adopted, so nothing was ever armed")
            .that(scheduler.lockCount)
            .isEqualTo(0)
        assertThat(scheduler.armed()).isEmpty()
    }

    @Test
    fun `a zero grace period locks on the spot`() {
        val session = guard(grace = 0)
        session.adopt()

        session.leftForeground()
        scheduler.advance(0)

        assertThat(scheduler.lockCount).isEqualTo(1)
    }

    @Test
    fun `nonsense timings are refused at construction`() {
        assertWithMessage("an idle timeout of zero would lock the user out of their own app")
            .that(runCatching { SessionGuard({}, scheduler::schedule, idleTimeoutMillis = 0) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { SessionGuard({}, scheduler::schedule, backgroundGraceMillis = -1) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}

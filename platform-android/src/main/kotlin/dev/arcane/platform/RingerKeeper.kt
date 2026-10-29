package dev.arcane.platform

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.app.NotificationManager
import android.content.SharedPreferences
import android.provider.Settings

/**
 * The phone's ringer, borrowed while a realm is open and handed back when it closes.
 *
 * The point is the interruption that comes from outside: a call arriving on speaker while someone is
 * standing in the vault is a louder tell than anything on the screen, and a ring the owner cannot silence
 * fast enough is the one notification that cannot be ignored afterwards.
 *
 * Android only hands the ringer to an app the owner has granted Do-Not-Disturb access to, so this degrades
 * honestly: [engage] reports whether the phone is actually on vibrate now, and a false result is the reason
 * the UI shows the settings page rather than promising silence it cannot deliver.
 *
 * The previous mode is written down before it is changed, because a process killed mid-viewing would
 * otherwise leave the phone on vibrate with nothing left that remembers why. It is only handed back on the
 * next start if the ringer is still where this app left it, so a person who set silent themselves in the
 * meantime is not quietly overruled.
 */
class RingerKeeper(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val state: SharedPreferences = context.getSharedPreferences("ringer", Context.MODE_PRIVATE)

    /** Whether the owner has granted this app the right to move the ringer at all. */
    val hasAccess: Boolean get() = notifications?.isNotificationPolicyAccessGranted == true

    private var borrowed: Int? = null

    init {
        // Left behind by a kill: finish the restore the lock never got to run.
        val owed = state.getInt(KEY_OWED, -1)
        if (owed >= 0) {
            if (audio?.ringerMode == AudioManager.RINGER_MODE_VIBRATE) audio.ringerMode = owed
            state.edit().remove(KEY_OWED).apply()
        }
    }

    /** @return true when the phone is on vibrate now — not merely when this method was called. */
    fun engage(): Boolean {
        val manager = audio ?: return false
        if (!hasAccess) return false
        val previous = manager.ringerMode
        if (previous != AudioManager.RINGER_MODE_VIBRATE) {
            borrowed = previous
            state.edit().putInt(KEY_OWED, previous).apply()
            runCatching { manager.ringerMode = AudioManager.RINGER_MODE_VIBRATE }.onFailure { return false }
        } else {
            borrowed = null
        }
        return manager.ringerMode == AudioManager.RINGER_MODE_VIBRATE
    }

    /** The lock's share of the work: the phone goes back to whatever it was doing before this app asked. */
    fun restore() {
        val previous = borrowed ?: state.getInt(KEY_OWED, -1).takeIf { it >= 0 } ?: return
        borrowed = null
        state.edit().remove(KEY_OWED).apply()
        runCatching { audio?.ringerMode = previous }
    }

    /** The system page that grants the right. No result comes back from it; the caller re-checks [hasAccess]. */
    fun accessSettings(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        const val KEY_OWED = "owed"
    }
}

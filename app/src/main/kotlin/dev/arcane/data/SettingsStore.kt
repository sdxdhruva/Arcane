package dev.arcane.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The file is called `cfg`, not `arcane_settings`, for the same reason the blobs are called `.bin`. */
private val Context.preferences by preferencesDataStore(name = "cfg")

/**
 * What the app remembers: a slot index, a strike counter and one switch. None of it opens anything — the
 * key exists only for as long as the passphrase has been typed into this process, and is derived again on
 * every unlock. There is deliberately no wrapped key here and no quick-unlock shortcut: this app never asks
 * the phone's lock screen to vouch for anyone, because the phone's PIN and its fingerprint are not this
 * vault's secret.
 *
 * The safe answer under pressure is not a second encrypted folder — it is the wrong number of wrong
 * passphrases, which lands in plain browsing of the phone's own files. That needs no key, so nothing here
 * has to record it, and nothing here gives the plain surface anything to say.
 */
data class AppPrefs(
    val realSlot: Int? = null,
    val wrongAttempts: Int = 0,
    /** The phone goes to vibrate for the length of an open realm. Off means off, whatever the ringer does. */
    val silentMode: Boolean = true,
) {
    val isSetUp: Boolean get() = realSlot != null

    companion object {
        val EMPTY = AppPrefs()
    }
}

class SettingsStore(context: Context) {

    private val store = context.applicationContext.preferences

    val prefs: Flow<AppPrefs> = store.data
        .catch { emit(emptyPreferences()) } // a corrupt preferences file must not brick the unlock screen
        .map(::read)

    suspend fun current(): AppPrefs = prefs.first()

    suspend fun recordSetup(realSlot: Int) = edit {
        it[KEY_REAL_SLOT] = realSlot
    }

    suspend fun setWrongAttempts(count: Int) = edit { it[KEY_ATTEMPTS] = count.coerceAtLeast(0) }

    suspend fun setSilentMode(enabled: Boolean) = edit { it[KEY_SILENT] = enabled }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        store.edit(block)
    }

    private fun read(p: Preferences): AppPrefs = AppPrefs(
        realSlot = p[KEY_REAL_SLOT]?.takeIf { it >= 0 },
        wrongAttempts = p[KEY_ATTEMPTS] ?: 0,
        silentMode = p[KEY_SILENT] ?: true,
    )

    private companion object {
        val KEY_REAL_SLOT = intPreferencesKey("slot_r")
        val KEY_ATTEMPTS = intPreferencesKey("att")
        val KEY_SILENT = booleanPreferencesKey("mute")
    }
}

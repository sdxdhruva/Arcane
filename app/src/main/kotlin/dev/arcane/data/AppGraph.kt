package dev.arcane.data

import android.content.Context
import androidx.media3.datasource.DataSource
import dev.arcane.media.ArcaneDataSourceFactory
import dev.arcane.media.ArcanePlaybackSources
import dev.arcane.platform.RingerKeeper
import dev.arcane.platform.VaultDevice
import dev.arcane.platform.VaultSession
import dev.arcane.vault.index.FileRecord
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

/**
 * The process-wide wiring: one disk layout, one settings store, one realm, one thread that owns it. Nothing
 * else in the app is allowed to assemble vault pieces itself, because the failure
 * mode of getting this wrong is a second copy of a master key that nobody wipes.
 *
 * [session] is the only thing that decides when a realm stops being open, and it decides by calling
 * [lockNow], which is the only thing that stops work and drops keys. Any screen that thinks it should lock
 * the vault should ask the session instead of doing it itself.
 */
class AppGraph private constructor(context: Context) {

    private val app = context.applicationContext
    val device: VaultDevice = VaultDevice.of(app)
    val settings: SettingsStore = SettingsStore(app)
    val holder: VaultHolder = VaultHolder(device)

    /** Simple mode's source of truth: the phone's own library, read out of MediaStore. */
    val media: DeviceMedia = DeviceMedia(app)

    private val vaultExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "arcane-vault").apply { isDaemon = true }
    }

    val repository: VaultRepository = VaultRepository(device, holder, vaultExecutor.asCoroutineDispatcher(), app)

    val session: VaultSession = VaultSession(onLock = ::lockNow)

    /** The phone's ringer, borrowed for the length of an open realm. See [RingerKeeper]. */
    val ringer: RingerKeeper = RingerKeeper(app)

    /**
     * The ringer follows the realm rather than a screen, because the moment worth silencing is the moment there
     * is something to hear: a call on speaker over an open folder is a louder tell than anything drawn on it.
     * Plain browsing and the gate leave the phone alone.
     *
     * Main-immediate, and re-checked after the settings read, because [lockNow] restores the ringer on this
     * same thread: a borrow that resumed after the lock would leave the phone on vibrate with the vault shut.
     */
    private val ringerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        ringerScope.launch {
            holder.opened.collect { opened ->
                if (opened == null) return@collect
                val prefs = settings.current()
                // The suspension above is the whole race: anything that happened in it is visible here.
                if (prefs.silentMode && holder.opened.value === opened) ringer.engage()
            }
        }
    }

    /**
     * Plain browsing — the phone's own files, no key, nothing secret. It is what the wrong-passphrase failover
     * lands on, so it is a session state rather than a screen state: it has to survive the gate being put back
     * up, and it has to come down the moment anything real locks.
     */
    private val _plain = MutableStateFlow(false)
    val plain: StateFlow<Boolean> = _plain.asStateFlow()

    val engine: UnlockEngine = UnlockEngine(device, settings, holder, session, repository, ::enterPlain)

    fun enterPlain() {
        _plain.value = true
    }

    /** Back out of plain browsing. The gate is behind it, which is the only way back in. */
    fun leavePlain() {
        _plain.value = false
    }

    /**
     * Surround a for-result launch — [beginHandoff] before `launch`, [endHandoff] as the first act of the
     * result callback. Without it the instant background lock fires the moment the phone's own file picker
     * comes up, and every import ends as a gate the user has to pass again with a pick they have to redo.
     */
    fun beginHandoff() = session.beginHandoff()

    fun endHandoff() = session.endHandoff()

    /**
     * Turn the borrowed ringer on or off, and say whether the phone is actually quiet now.
     *
     * A false result is not a failure of this app: Android only hands the ringer to an app the owner has
     * switched on under Do Not Disturb access, so the caller's job is to offer that page rather than promise
     * silence.
     */
    suspend fun setQuietMode(enabled: Boolean): Boolean {
        settings.setSilentMode(enabled)
        if (!enabled) {
            ringer.restore()
            return false
        }
        return holder.opened.value != null && ringer.engage()
    }

    /**
     * Which file is being viewed full-screen, if any. It lives here rather than in the browser because the
     * viewer has to cover everything beneath it, and only the shell can decide that; the shell also clears it
     * the moment no realm is open, so a locked phone cannot be left showing a plaintext page.
     */
    private val _viewing = MutableStateFlow<FileRecord?>(null)
    val viewing: StateFlow<FileRecord?> = _viewing.asStateFlow()

    /**
     * The queue the viewer pages through. A folder filtered by search passes only what is on screen, so a
     * swipe never lands on a file the person had just filtered out. It is held here rather than in the viewer
     * because paging calls [view] again with no queue, and a queue remembered by composable state would be
     * rebuilt from the unfiltered listing on the next frame.
     */
    private val _viewQueue = MutableStateFlow<List<FileRecord>?>(null)
    val viewQueue: StateFlow<List<FileRecord>?> = _viewQueue.asStateFlow()

    fun view(record: FileRecord?, queue: List<FileRecord>? = null) {
        _viewing.value = record
        when {
            record == null -> _viewQueue.value = null
            queue != null -> _viewQueue.value = queue
        }
    }

    /** The single factory the one player uses: vault content decrypts, everything else behaves normally. */
    val playbackSources: DataSource.Factory = ArcanePlaybackSources(
        app,
        ArcaneDataSourceFactory(repository.chunkMaps(), device.chunkCache),
    )

    /** Covers for the grid: decrypted on demand, held in RAM only, emptied by [lockNow]. */
    val thumbnails: VaultThumbnails = VaultThumbnails(repository, playbackSources)

    /** Stops the work, then the keys, then the plaintext. Order matters: a wipe mid-write corrupts nothing
     * but a write after a wipe leaves blobs no later unlock can name. */
    fun lockNow() {
        // First, because a viewer that unmounts after the key is gone would spend its last frame reading a
        // locked realm; unmounting is the polite half of wiping.
        _viewing.value = null
        _viewQueue.value = null
        _plain.value = false
        // Plaintext covers go before the key does; a decode still running will see the bumped generation and
        // refuse to re-enter the cache.
        thumbnails.clear()
        repository.cancelInFlight()
        holder.lock()
        ringer.restore()
    }

    fun startLifecycle() = session.attach()

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun of(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }
    }
}

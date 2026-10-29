package dev.arcane.data

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import dev.arcane.media.ArcaneUris
import dev.arcane.media.ChunkMapSource
import dev.arcane.platform.VaultDevice
import dev.arcane.vault.file.ChunkInputStream
import dev.arcane.vault.file.ChunkMap
import dev.arcane.vault.format.Hex
import dev.arcane.vault.index.FileRecord
import dev.arcane.vault.index.FolderRecord
import dev.arcane.vault.realm.PendingItem
import dev.arcane.vault.realm.ReconcileReport
import dev.arcane.vault.realm.RealmLockedException
import dev.arcane.vault.realm.VaultRealm
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the browser: a folder to walk into or a file to open. */
sealed interface VaultEntry {
    val name: String

    /** [itemCount] is what is directly inside, so an empty folder is visible before it is opened. */
    data class Folder(override val name: String, val record: FolderRecord, val itemCount: Int = 0) : VaultEntry
    data class File(override val name: String, val record: FileRecord) : VaultEntry
}

/** What one directory holds, plus the path the breadcrumb needs. */
data class VaultListing(
    val folderId: String? = null,
    val trail: List<FolderRecord> = emptyList(),
    val entries: List<VaultEntry> = emptyList(),
    val totalBytes: Long = 0,
)

/**
 * An import in flight, or one that just failed. [key] is the source uri, the only identifier that is stable
 * before the realm has a file id for it.
 */
data class Transfer(
    val key: String,
    val name: String,
    val doneBytes: Long,
    val totalBytes: Long,
    val failure: String? = null,
) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val isFinished: Boolean get() = failure != null || doneBytes >= totalBytes
}

/** The vault locked while work was running, which is a normal outcome rather than a bug. */
class VaultWasLocked : IOException("the vault locked while it was working")

/**
 * Every vault operation the UI can ask for, run on the one thread that owns the realm.
 *
 * The single-thread rule is not shyness about locks: [VaultRealm] holds a live index and a commit sequence,
 * so two threads committing generations against one realm would fight over the anchor. Import therefore
 * queues rather than parallelises, which is also what the flash wants, since chunk writes are I/O bound.
 */
class VaultRepository(
    private val device: VaultDevice,
    private val holder: VaultHolder,
    dispatcher: CoroutineDispatcher,
    context: Context,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _listing = MutableStateFlow(VaultListing())
    val listing: StateFlow<VaultListing> = _listing.asStateFlow()

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Refusals the user should see — a folder that still holds files, a move that would create a loop. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val _busy = MutableStateFlow(false)

    /** True while the vault thread is doing something a screen should show a skeleton for. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /**
     * Whether a listing has been published for the realm that is open now.
     *
     * The shell switches to the browser the moment the realm is adopted, which is before the index has been
     * read; without this flag the first thing every unlock shows is "nothing in here yet" over a folder that
     * may hold a thousand files. Cleared by the lock, because a listing is decrypted names.
     */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val isImporting: Boolean get() = _transfers.value.any { !it.isFinished }

    private var queue: Job? = null

    /** Publishes [folderId] to the browser. Called on navigation and after every mutation. */
    suspend fun show(folderId: String?) = working { realm ->
        _listing.value = read(realm, folderId)
        _loaded.value = true
    }

    /**
     * The first thing a freshly opened realm needs: the journal may hold promises the last process could not
     * keep, and until they are read the listing is a lie about what is on disk.
     */
    suspend fun reconcile(): ReconcileReport? = workingResult { realm ->
        val report = realm.reconcile()
        _listing.value = read(realm, _listing.value.folderId)
        report
    }

    /** The imports the journal is still holding, newest first. */
    suspend fun pending(): List<PendingItem> = withContext(scope.coroutineContext) {
        realm().pendingItems.sortedByDescending { it.startedAtMillis }
    }

    /**
     * Finishes a journalled import against a re-picked source.
     *
     * The size has to match before a byte is written: [dev.arcane.vault.realm.VaultRealm.resume] trusts the
     * caller to hand back the same file, and resuming a 4 GiB promise from a 2 KiB pick would fill the tail of
     * a video with the wrong plaintext. Refusing is cheaper than explaining a corrupt file later.
     */
    fun resumePending(item: PendingItem, uri: Uri) {
        queue?.cancel()
        queue = scope.launch {
            val size = runCatching { declareSize(uri) }.getOrElse {
                note(it)
                return@launch
            }
            if (size != item.plainSize) {
                _notices.tryEmit("That file is $size bytes; this import promised ${item.plainSize}.")
                return@launch
            }
            val offset = withContext(scope.coroutineContext) { realm().resumeOffset(item) }
            if (offset > size) {
                _notices.tryEmit("That import is already past the end of the file.")
                return@launch
            }
            var state = Transfer(uri.toString(), item.name, offset, size)
            track(state)
            try {
                val opened = app.contentResolver.openInputStream(uri) ?: throw IOException("nothing opened for $uri")
                opened.use { stream ->
                    stream.skip(offset)
                    withContext(scope.coroutineContext) {
                        realm().resume(
                            item = item,
                            source = stream,
                            onProgress = { done ->
                                state = state.copy(doneBytes = offset + done)
                                track(state)
                            },
                        )
                    }
                }
                holder.current?.realm?.let { _listing.value = read(it, item.folderId) }
            } catch (failure: Exception) {
                note(failure)
            } finally {
                _transfers.value = _transfers.value.filterNot { it.key == state.key }
            }
        }
    }

    /** Abandons a journalled operation and removes the blobs it had already written. */
    suspend fun discardPending(item: PendingItem) = working { realm ->
        realm.cancel(item)
        _listing.value = read(realm, _listing.value.folderId)
    }

    /**
     * Every folder in the realm, addressed by the path a person would say out loud, for a move sheet. The
     * root answers as `null`, which is what [moveFile] and [moveFolder] already take.
     */
    suspend fun folderChoices(excludeSubtreesOf: Set<String> = emptySet()): List<Pair<String?, String>> =
        withContext(scope.coroutineContext) {
            val index = realm().index
            val blocked = excludeSubtreesOf.flatMap { id -> index.descendantsOf(id).map { it.id } + id }.toSet()
            buildList {
                add(null to "Top level")
                index.folders
                    .filterNot { it.id in blocked }
                    .sortedBy { index.pathOfFolder(it.id).lowercase() }
                    .forEach { add(it.id to "/${index.pathOfFolder(it.id)}") }
            }
        }

    suspend fun folderChoices(excludeSubtreeOf: String?): List<Pair<String?, String>> =
        folderChoices(if (excludeSubtreeOf == null) emptySet() else setOf(excludeSubtreeOf))

    /**
     * Queues [uris] into [folderId], one at a time. A locked realm or a vanished source stops that item and
     * lets the rest of the queue run: losing one file out of forty is not a reason to lose thirty-nine.
     */
    fun import(uris: List<Uri>, folderId: String?) {
        // A pick grants read until this process dies. Persisting it is what lets an import that outlives the
        // screen — or the phone's screen — keep going rather than fail on the last chunk of a 4 GiB file.
        uris.forEach { uri ->
            runCatching {
                app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        queue?.cancel()
        queue = scope.launch {
            for (uri in uris) {
                runCatching { transfer(uri, folderId) }
                    .onFailure { note(it) }
            }
            holder.current?.realm?.let { _listing.value = read(it, folderId) }
        }
    }

    /**
     * Streams [rawUrl] into [folderId] without letting the bytes touch shared storage first, which is the whole
     * point of asking for a download instead of a pick: the response body goes straight into the chunked writer
     * a picked file already uses, so the longest a plaintext byte survives is as long as one 512 KiB buffer.
     *
     * The length has to be known before the first byte is written. The journal promises a size so that an import
     * killed halfway can be finished or undone, and a promise about a stream nobody measured cannot be kept —
     * so the size comes from `Content-Length`, then from a HEAD request, and a server that gives neither is
     * refused with the reason rather than quietly downloaded twice.
     */
    fun download(rawUrl: String, folderId: String?) {
        val target = parseHttpUrl(rawUrl)
        if (target == null) {
            _notices.tryEmit("That is not an http or https link I can open.")
            return
        }
        queue?.cancel()
        queue = scope.launch {
            runCatching { transferUrl(target, folderId) }
                .onFailure { note(it) }
            holder.current?.realm?.let { _listing.value = read(it, folderId) }
        }
    }

    fun dismissTransfer(key: String) {
        _transfers.value = _transfers.value.filterNot { it.key == key }
    }

    /** Called by the lock: the queue stops, and the journal is left holding whatever was promised. */
    fun cancelInFlight() {
        scope.coroutineContext.cancelChildren()
        _transfers.value = emptyList()
        // Names decrypted for the screen have no business outliving the key that produced them, and a stale
        // listing would be the first frame of the next unlock.
        _listing.value = VaultListing()
        _loaded.value = false
    }

    suspend fun delete(fileIds: List<ByteArray>) = working { realm ->
        fileIds.forEach { fileId ->
            if (realm.delete(fileId)) device.chunkCache.evict(fileId)
        }
        _listing.value = read(realm, _listing.value.folderId)
    }

    suspend fun createFolder(name: String, parentId: String?) = working { realm ->
        realm.createFolder(name, parentId)
        _listing.value = read(realm, parentId)
    }

    suspend fun renameFolder(id: String, newName: String) = working { realm ->
        realm.renameFolder(id, newName)
        whereItWas(realm)
    }

    suspend fun moveFolder(id: String, newParentId: String?) = working { realm ->
        realm.moveFolder(id, newParentId)
        whereItWas(realm)
    }

    suspend fun deleteFolder(id: String) = working { realm ->
        val parent = realm.index.folder(id)?.parentId
        realm.deleteFolder(id)
        _listing.value = read(realm, parent)
    }

    suspend fun renameFile(fileId: ByteArray, newName: String) = working { realm ->
        realm.renameFile(fileId, newName)
        whereItWas(realm)
    }

    /** Index-only, like rename: the ciphertext never mentions where a file lives. */
    suspend fun moveFile(fileId: ByteArray, folderId: String?) = working { realm ->
        realm.moveFile(fileId, folderId)
        whereItWas(realm)
    }

    /** Resumable imports the journal survived, which the browser offers to finish on first paint. */
    suspend fun pendingCount(): Int = withContext(scope.coroutineContext) { realm().pendingItems.size }

    /** The whole file, for viewers that want bytes. Refuses anything past [limitBytes]. */
    suspend fun readAll(fileId: ByteArray, limitBytes: Long = MAX_IN_RAM): ByteArray =
        withContext(scope.coroutineContext) {
            val map = handle(fileId)
            if (map.plainSize > limitBytes) throw IOException("too large to open in RAM")
            ChunkInputStream(device.chunkCache, map).use { it.readBytes() }
        }

    /**
     * A run of whole lines starting at [offset], which the caller keeps on a line boundary.
     *
     * A byte window cuts lines — and multi-byte characters — in half. A line window cannot, because the cut is
     * always made after a newline, which is what lets the reader append window after window without stitching
     * their edges. [LineWindow.next] is the byte where the following line begins.
     */
    suspend fun textLines(fileId: ByteArray, offset: Long, maxBytes: Int): LineWindow =
        withContext(scope.coroutineContext) {
            val map = handle(fileId)
            try {
                val start = offset.coerceIn(0L, map.plainSize)
                val remaining = (map.plainSize - start).coerceAtLeast(0L).toInt()
                if (remaining == 0) {
                    LineWindow(emptyList(), start, hasMore = false)
                } else {
                    val buffer = ByteArray(minOf(maxBytes, remaining))
                    var filled = 0
                    ChunkInputStream(device.chunkCache, map).use { source ->
                        source.skip(start)
                        while (filled < buffer.size) {
                            val read = source.read(buffer, filled, buffer.size - filled)
                            if (read < 0) break
                            filled += read
                        }
                    }
                    var cut = -1
                    for (i in filled - 1 downTo 0) {
                        if (buffer[i] == NEWLINE) {
                            cut = i
                            break
                        }
                    }
                    // No newline at all means one absurdly long line — a minified bundle. Cutting it where the
                    // window ends and saying so is worse than showing half of it, so the half is shown.
                    val used = if (cut < 0) filled else cut + 1
                    val parts = String(buffer, 0, used, Charsets.UTF_8).split('\n')
                    val endsAtNewline = used > 0 && buffer[used - 1] == NEWLINE
                    LineWindow(
                        lines = (if (endsAtNewline) parts.dropLast(1) else parts).map { it.trimEnd('\r') },
                        next = start + used,
                        hasMore = start + used < map.plainSize,
                    )
                }
            } finally {
                map.wipe()
            }
        }

    /**
     * Raw bytes, for the pane that shows a file's head as hex.
     *
     * Decoding first would turn every byte above 0x7F into a replacement character, and a header dump is the
     * one place where the exact bytes are the whole point of the screen.
     */
    suspend fun bytesAt(fileId: ByteArray, offset: Long, count: Int): ByteArray =
        withContext(scope.coroutineContext) {
            val map = handle(fileId)
            try {
                val start = offset.coerceIn(0L, map.plainSize)
                val want = minOf(count.toLong(), map.plainSize - start).toInt()
                device.chunkCache.readAt(map, start, want.coerceAtLeast(0))
            } finally {
                map.wipe()
            }
        }

    /** Everything the media layer needs: a handle per id, or null when the realm is not open. */
    fun chunkMaps(): ChunkMapSource = ChunkMapSource { fileId -> holder.current?.realm?.open(fileId) }

    fun playbackUri(fileId: ByteArray): Uri = ArcaneUris.forFileId(Hex.lower(fileId))

    /** The handle a viewer reads through; the caller closes the stream it builds from it. */
    suspend fun openHandle(fileId: ByteArray): ChunkMap = handle(fileId)

    /**
     * Hands a plaintext stream to [block] on the vault thread and returns what it made.
     *
     * For the formats that want a parser rather than a byte window — a zip central directory, the head of a
     * file that may not be text. [block] runs where the realm lives, so it must do I/O and computation only.
     */
    suspend fun <T> withStream(fileId: ByteArray, block: (java.io.InputStream) -> T): T =
        withContext(scope.coroutineContext) {
            val map = handle(fileId)
            try {
                ChunkInputStream(device.chunkCache, map).use(block)
            } finally {
                map.wipe()
            }
        }

    /**
     * The one bounded exception to "no plaintext on disk": a seekable file, for the viewers that need a
     * descriptor rather than a stream. The size gate is the point of returning a result instead of throwing —
     * a 500 MB PDF would otherwise write 500 MB of plaintext, so the viewer is told to offer export instead.
     *
     * The caller owns the file and must hand it back to [releaseTemp]; leaving it is what `sweepStale` is for,
     * but leaving it deliberately would be a design bug, not a crash-recovery case.
     */
    suspend fun materialize(fileId: ByteArray, onProgress: (Long) -> Unit = {}): TempPlain =
        withContext(scope.coroutineContext) {
            val realm = realm()
            val map = handle(fileId)
            if (map.plainSize > MAX_TEMP_PLAINTEXT) {
                map.wipe()
                return@withContext TempPlain.TooBig(map.plainSize)
            }
            try {
                TempPlain.Ready(device.tempPlaintext.materialize(realm.reader, map, onProgress) { realm.isUnlocked })
            } finally {
                map.wipe()
            }
        }

    /** Zeroed, then unlinked — the only reason a temp plaintext may be shorter-lived than a regular blob. */
    suspend fun releaseTemp(file: File) = withContext(scope.coroutineContext) {
        device.tempPlaintext.destroy(file)
    }

    /**
     * The same teardown, started on the vault scope rather than the caller's.
     *
     * A viewer being disposed cannot await anything: its coroutine scope is cancelled in the same breath as
     * the call, so `scope.launch { releaseTemp(...) }` inside `onDispose` never runs and the plaintext file
     * waits for the next lock.
     */
    fun releaseTempSoon(file: File) {
        scope.launch { runCatching { device.tempPlaintext.destroy(file) } }
    }

    /**
     * Writes the plaintext somewhere outside the vault. The confirm dialog that explains what this means is
     * the screen's job; this one only does the streaming, on the vault thread, so a lock mid-write stops it.
     */
    suspend fun exportTo(fileId: ByteArray, target: Uri) = working { realm ->
        val map = handle(fileId)
        try {
            val out = app.contentResolver.openOutputStream(target, "wt")
                ?: throw IOException("nothing would accept that file")
            out.use { ChunkInputStream(device.chunkCache, map).use { source -> source.copyTo(it) } }
        } finally {
            map.wipe()
        }
    }

    /** The viewer's export button, which has nothing to await: a finished transfer is a notice, not a result. */
    fun export(fileId: ByteArray, target: Uri) {
        scope.launch { exportTo(fileId, target) }
    }

    sealed interface TempPlain {
        data class Ready(val file: File) : TempPlain
        data class TooBig(val plainSize: Long) : TempPlain
    }

    data class LineWindow(val lines: List<String>, val next: Long, val hasMore: Boolean)

    private suspend fun transfer(uri: Uri, folderId: String?) {
        val size = declareSize(uri)
        val name = displayName(uri)
        var state = Transfer(uri.toString(), name, 0, size)
        track(state)
        try {
            val opened = app.contentResolver.openInputStream(uri)
                ?: throw IOException("nothing opened for $uri")
            opened.use { stream ->
                withContext(scope.coroutineContext) {
                    realm().import(
                        source = stream,
                        name = name,
                        folderId = folderId,
                        mimeType = app.contentResolver.getType(uri),
                        plainSize = size,
                        onProgress = { done ->
                            state = state.copy(doneBytes = done)
                            track(state)
                        },
                    )
                }
            }
        } finally {
            _transfers.value = _transfers.value.filterNot { it.key == state.key }
        }
    }

    /**
     * One download, run like an import. The connection is opened and its length settled on the IO pool, then
     * the body is read by the vault thread inside [VaultRealm.import] — which is the only ordering that keeps
     * the promise the journal makes: bytes reach the chunk writer at exactly the offset the journal says they
     * stopped at, so a lock or a death mid-transfer leaves something finishable rather than something corrupt.
     */
    private suspend fun transferUrl(url: URL, folderId: String?) {
        val (connection, size) = withContext(Dispatchers.IO) { openForDownload(url) }
        val name = nameFor(connection, url)
        var state = Transfer(url.toString(), name, 0, size)
        track(state)
        try {
            connection.inputStream.use { stream ->
                withContext(scope.coroutineContext) {
                    realm().import(
                        source = stream,
                        name = name,
                        folderId = folderId,
                        mimeType = connection.contentType,
                        plainSize = size,
                        onProgress = { done ->
                            state = state.copy(doneBytes = done)
                            track(state)
                        },
                    )
                }
            }
        } finally {
            connection.disconnect()
            _transfers.value = _transfers.value.filterNot { it.key == state.key }
        }
    }

    /**
     * The GET, with redirects followed by hand because [HttpURLConnection] will not cross between http and
     * https on its own and a link a person pasted is exactly the kind that does.
     *
     * @return the connection with its body still unread, and the plaintext length the journal will promise.
     */
    private fun openForDownload(start: URL): Pair<HttpURLConnection, Long> {
        var current = start
        repeat(MAX_REDIRECTS + 1) {
            val connection = request(current, "GET")
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IOException("the server said the file moved and not where")
                current = URL(current, location)
                if (parseHttpUrl(current.toString()) == null) {
                    throw IOException("the redirect left for ${current.protocol}, which this does not follow")
                }
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw IOException("the server answered $code")
            }
            val declared = connection.contentLengthLong
            if (declared > 0L) return connection to declared
            // A chunked response hides its length; a HEAD usually knows it. One small extra request beats
            // refusing, and beats far worse: downloading a file twice to count it.
            val probed = probeLength(current)
            if (probed <= 0L) {
                connection.disconnect()
                throw IOException("the server would not say how large this is, and the vault promises a size first")
            }
            return connection to probed
        }
        throw IOException("this link redirected too many times to follow")
    }

    /** Whether the same URL will hand over a length when asked politely. Returns -1 when it will not. */
    private fun probeLength(url: URL): Long = runCatching {
        val head = request(url, "HEAD")
        try {
            if (head.responseCode in 200..299) head.contentLengthLong else -1L
        } finally {
            head.disconnect()
        }
    }.getOrDefault(-1L)

    private fun request(url: URL, method: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = CONNECT_MILLIS
            readTimeout = READ_MILLIS
            // Dalvik's default identifies the app, the phone model and the build. A file server has no use for
            // any of that, and the vault is the reason the request exists at all.
            setRequestProperty("User-Agent", USER_AGENT)
            // A gzipped body reports the compressed length, which is not the length the journal promised.
            setRequestProperty("Accept-Encoding", "identity")
        }

    /**
     * The name the file lands with: what the server offered to be called, else what the URL path says, else
     * something dated. Separators and control characters are stripped because this string is stored in the
     * index and printed in the browser, and neither place has business holding a path.
     */
    private fun nameFor(connection: HttpURLConnection, url: URL): String {
        val offered = connection.getHeaderField("Content-Disposition")
            ?.substringAfter("filename=", "")
            ?.trim('"', '\'', ' ')
            ?.takeIf { it.isNotEmpty() }
        val fromPath = url.path.substringAfterLast('/').takeIf { it.isNotBlank() }
        val candidate = offered ?: fromPath ?: "download-${System.currentTimeMillis()}"
        val clean = candidate.map { if (it.isISOControl() || it == '/' || it == '\\') '_' else it }
            .joinToString("")
            .trim('.', ' ')
            .take(MAX_NAME_CHARS)
        return clean.ifEmpty { "download-${System.currentTimeMillis()}" }
    }

    /** Only http and https, with a bare "example.com/file" assumed to mean https rather than refused. */
    private fun parseHttpUrl(raw: String): URL? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return runCatching {
            val url = URI(withScheme).toURL()
            if (url.host.isBlank() || url.protocol !in HTTP_PROTOCOLS) null else url
        }.getOrNull()
    }

    private fun track(state: Transfer) {
        _transfers.value = _transfers.value.filterNot { it.key == state.key } + state
    }

    private suspend fun handle(fileId: ByteArray): ChunkMap = withContext(scope.coroutineContext) {
        realm().open(fileId) ?: throw FileNotFoundException("no such file in this realm")
    }

    private suspend fun working(block: suspend (VaultRealm) -> Unit) {
        workingResult { realm ->
            block(realm)
            Unit
        }
    }

    private suspend fun <T> workingResult(block: suspend (VaultRealm) -> T): T? {
        _busy.value = true
        try {
            return withContext(scope.coroutineContext) { block(realm()) }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            note(failure)
            return null
        } finally {
            _busy.value = false
        }
    }

    private fun realm(): VaultRealm = holder.current?.realm ?: throw VaultWasLocked()

    private fun whereItWas(realm: VaultRealm) {
        _listing.value = read(realm, _listing.value.folderId)
    }

    private fun note(failure: Throwable) {
        if (failure is CancellationException) return
        val message = when (failure) {
            is RealmLockedException, is VaultWasLocked -> "The vault locked."
            is IllegalArgumentException -> failure.message ?: "That change does not work here."
            else -> failure.message ?: "Could not do that."
        }
        _notices.tryEmit(message)
    }

    private fun read(realm: VaultRealm, folderId: String?): VaultListing {
        val index = realm.index
        val folders = index.childFolders(folderId).sortedBy { it.name.lowercase() }
        val files = index.childFiles(folderId).sortedBy { it.name.lowercase() }
        // Counts for the whole tree in one pass, rather than walking the index once per tile.
        val children = HashMap<String, Int>()
        index.folders.forEach { node -> node.parentId?.let { children[it] = (children[it] ?: 0) + 1 } }
        index.files.forEach { node -> node.folderId?.let { children[it] = (children[it] ?: 0) + 1 } }
        return VaultListing(
            folderId = folderId,
            trail = folderId?.let { index.ancestorsOf(it).asReversed() } ?: emptyList(),
            entries = folders.map { VaultEntry.Folder(it.name, it, children[it.id] ?: 0) } +
                files.map { VaultEntry.File(it.name, it) },
            totalBytes = files.sumOf { it.plainSize },
        )
    }

    private fun declareSize(uri: Uri): Long {
        val size = when (uri.scheme) {
            "file" -> uri.path?.let { File(it).length() } ?: -1L
            else -> app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }
        if (size < 0) throw IOException("cannot tell how big $uri is")
        return size
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c: Cursor ->
            if (c.moveToFirst()) {
                val column = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && !c.isNull(column)) name = c.getString(column)
            }
        }
        return sanitize(name ?: uri.lastPathSegment ?: "file")
    }

    private companion object {
        /** Big enough for a photograph, small enough that tapping a raw scan cannot OOM the process. */
        const val MAX_IN_RAM = 48L * 1024 * 1024

        /** How much plaintext a PDF viewer is allowed to put on flash for the length of one preview. */
        const val MAX_TEMP_PLAINTEXT = 100L * 1024 * 1024

        private val NEWLINE = '\n'.code.toByte()

        /** A file server is slower than flash; a stalled socket must not hold the vault thread forever. */
        private const val CONNECT_MILLIS = 15_000
        private const val READ_MILLIS = 30_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_NAME_CHARS = 120
        private val HTTP_PROTOCOLS = setOf("http", "https")

        /** Generic on purpose: the default would name this app, the phone model and the build. */
        private const val USER_AGENT = "Mozilla/5.0"
    }
}

/**
 * A name in this vault is one path element: names only ever exist inside the encrypted index and the
 * browser joins them with a slash, so anything that would read back as two elements gets flattened.
 */
private fun sanitize(display: String): String {
    val flat = display.replace('/', '-').replace('\\', '-').filter { !it.isISOControl() }.trim()
    return flat.take(180).ifBlank { "file" }
}

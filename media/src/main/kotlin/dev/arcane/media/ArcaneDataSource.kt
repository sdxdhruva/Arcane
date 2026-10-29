package dev.arcane.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import dev.arcane.vault.file.ChunkCache
import dev.arcane.vault.file.ChunkMap
import dev.arcane.vault.format.Hex
import dev.arcane.vault.format.VaultFormat
import java.io.IOException

/**
 * The only slice of an unlocked realm that playback needs: an id in, a read handle out. Null means the
 * realm is locked or the file is gone, which from a player's point of view is the same thing.
 */
fun interface ChunkMapSource {
    fun open(fileId: ByteArray): ChunkMap?
}

/** The content behind an `arcane://` uri cannot be opened. Media3 wants an IOException, so this is one. */
class VaultContentException(message: String) : IOException(message)

private fun cannotServe(uri: Uri, reason: String): VaultContentException =
    VaultContentException("cannot serve $uri: $reason")

/**
 * Media3's view of a vault file. It reports the plaintext length as the content length and serves reads at
 * any offset, which is what makes scrubbing feel ordinary: a seek decrypts the chunk the scrubber landed
 * on and nothing else, and a scrub back is usually answered from [ChunkCache] without touching the disk.
 *
 * There is no background prefetch on purpose. ExoPlayer buffers tens of seconds ahead while one 512 KiB
 * chunk decrypts in a few milliseconds, so the decrypt is already hidden by the buffer — a prefetch thread
 * would only add a second owner for the key material.
 *
 * Each open derives a fresh [ChunkMap] and each close wipes it, so a paused player holding a connection is
 * the only place the file key exists while it plays.
 */
class ArcaneDataSource(
    private val contents: ChunkMapSource,
    private val cache: ChunkCache,
) : BaseDataSource(/* isNetwork = */ false), DataSource {

    private var uri: Uri? = null
    private var map: ChunkMap? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val fileId = fileIdOf(dataSpec.uri)
        val handle = contents.open(fileId)
            ?: throw cannotServe(dataSpec.uri, "not in the open realm")
        if (dataSpec.position > handle.plainSize) {
            handle.wipe()
            throw cannotServe(dataSpec.uri, "position ${dataSpec.position} is past ${handle.plainSize}")
        }
        val remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) handle.plainSize - dataSpec.position
        else minOf(dataSpec.length, handle.plainSize - dataSpec.position)
        uri = dataSpec.uri
        map = handle
        position = dataSpec.position
        bytesRemaining = remaining
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(target: ByteArray, off: Int, len: Int): Int {
        val handle = map ?: throw VaultContentException("read without an open")
        if (bytesRemaining == 0L) return -1
        val wanted = minOf(bytesRemaining, len.toLong()).toInt()
        val placed = cache.readInto(handle, target, off, position, wanted)
        if (placed == 0) {
            bytesRemaining = 0
            return -1
        }
        position += placed
        bytesRemaining -= placed
        bytesTransferred(placed)
        return placed
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        val handle = map
        map = null
        bytesRemaining = 0
        if (opened) {
            opened = false
            transferEnded()
        }
        handle?.wipe()
    }

    private fun fileIdOf(uri: Uri): ByteArray {
        val hex = ArcaneUris.fileIdOf(uri) ?: throw cannotServe(uri, "not an arcane uri")
        if (hex.length != VaultFormat.FILE_ID_BYTES * 2) {
            throw cannotServe(uri, "file id is ${hex.length} hex characters, expected ${VaultFormat.FILE_ID_BYTES * 2}")
        }
        // Hex is case-insensitive on the way in even though the vault only ever writes lower case.
        return runCatching { Hex.decode(hex.lowercase()) }
            .getOrElse { throw cannotServe(uri, "file id is not hex") }
    }
}

/** Hands Media3 one decrypting source per connection, all of them sharing [cache]. */
class ArcaneDataSourceFactory(
    private val contents: ChunkMapSource,
    private val cache: ChunkCache,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = ArcaneDataSource(contents, cache)
}

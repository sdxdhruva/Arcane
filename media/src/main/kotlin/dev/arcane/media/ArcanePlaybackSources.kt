package dev.arcane.media

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener

/**
 * One factory that serves both halves of the app: `arcane://` goes to the decrypting source, everything
 * else — `content://` from SAF, `file://` from Simple mode, an http stream — goes to Media3's own defaults.
 *
 * That matters because there is one player. A single [androidx.media3.exoplayer.ExoPlayer] instance can
 * play a normal file and a vault file without being rebuilt, so the UI never has to care which mode it is
 * in, and the vault's keys stay inside [ArcaneDataSource] instead of leaking into the player's
 * configuration.
 */
class ArcanePlaybackSources(
    context: Context,
    private val vault: DataSource.Factory,
) : DataSource.Factory {

    private val appContext = context.applicationContext

    override fun createDataSource(): DataSource = Routing(appContext, vault)

    private class Routing(
        private val context: Context,
        private val vault: DataSource.Factory,
    ) : DataSource {

        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(listener: TransferListener) {
            listeners += listener
            delegate?.addTransferListener(listener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val created = if (dataSpec.uri.scheme == ArcaneUris.SCHEME) vault.createDataSource()
            else DefaultDataSource.Factory(context).createDataSource()
            listeners.forEach(created::addTransferListener)
            delegate = created
            return created.open(dataSpec)
        }

        override fun read(target: ByteArray, off: Int, len: Int): Int = current().read(target, off, len)

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            val active = delegate
            delegate = null
            active?.close()
        }

        private fun current(): DataSource =
            delegate ?: throw IllegalStateException("read before open")
    }
}

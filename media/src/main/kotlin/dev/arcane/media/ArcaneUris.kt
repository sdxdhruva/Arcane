package dev.arcane.media

import android.net.Uri
import androidx.media3.common.MediaItem

/**
 * In-process addressing for vault content. Media3 resolves these through the decrypting data source,
 * so playback never needs a loopback socket or a plaintext temp file.
 */
object ArcaneUris {
    const val SCHEME: String = "arcane"

    fun forFileId(fileIdHex: String): Uri = Uri.parse("$SCHEME://$fileIdHex")

    fun fileIdOf(uri: Uri): String? = uri.host.takeIf { uri.scheme == SCHEME }

    fun mediaItemFor(fileIdHex: String): MediaItem =
        MediaItem.Builder().setUri(forFileId(fileIdHex)).setMediaId(fileIdHex).build()
}

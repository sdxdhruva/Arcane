package dev.arcane.vault

import dev.arcane.vault.store.BlobStore
import dev.arcane.vault.store.FileBlobStore
import dev.arcane.vault.store.VaultLayout
import java.io.File

/** The vault's whole on-disk world, in a temp directory. */
fun vaultAt(root: File): Pair<VaultLayout, BlobStore> {
    val layout = VaultLayout(root)
    return layout to FileBlobStore(layout)
}

/**
 * Wraps a store and counts disk traffic, so a test can assert what a seek *costs* rather than only that
 * it returned the right bytes.
 */
class MeasuringBlobStore(private val delegate: BlobStore) : BlobStore by delegate {
    var reads = 0
        private set
    var bytesUploaded = 0L
        private set
    var writes = 0
        private set

    override fun read(blobId: ByteArray): ByteArray =
        delegate.read(blobId).also { account(it.size.toLong()) }

    override fun read(blobId: ByteArray, offset: Long, length: Int): ByteArray =
        delegate.read(blobId, offset, length).also { account(length.toLong()) }

    override fun write(blobId: ByteArray, payload: ByteArray) {
        writes++
        bytesUploaded += payload.size
        delegate.write(blobId, payload)
    }

    private fun account(bytes: Long) {
        reads++
        bytesUploaded += bytes
    }

    fun reset() {
        reads = 0
        bytesUploaded = 0
        writes = 0
    }
}

/** Deterministic pseudo-random content, so a failure is reproducible from the seed alone. */
fun pseudoRandom(size: Int, seed: Int): ByteArray {
    var state = seed.toLong() * 0x9E3779B97F4A7C15u.toLong()
    return ByteArray(size) {
        state = state * 6364136223846793005L + 1442695040888963407L
        (state ushr 33).toByte()
    }
}

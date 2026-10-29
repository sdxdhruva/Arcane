package dev.arcane.vault.index

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.arcane.vault.crypto.KdfParams
import dev.arcane.vault.crypto.KeyDerivation
import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.crypto.VaultAuthenticationException
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.pseudoRandom
import dev.arcane.vault.store.FileBlobStore
import dev.arcane.vault.store.VaultLayout
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class IndexStoreTest {

    @TempDir
    lateinit var root: File

    private val store: FileBlobStore by lazy { FileBlobStore(VaultLayout(root)) }
    private val indexStore: IndexStore by lazy { IndexStore(store) }

    private fun realm(tag: String): Pair<ByteArray, ByteArray> {
        val salt = pseudoRandom(16, tag.hashCode())
        val masterKey = KeyDerivation.argon2id(tag.toByteArray(), KdfParams(salt, 32 * 1024, 1, 1))
        return masterKey to KeyDerivation.deriveRealmId(masterKey, salt)
    }

    private fun record(name: String, folderId: String?, chunks: Int = 1): FileRecord = FileRecord(
        fileId = Secrets.randomBytes(VaultFormat.FILE_ID_BYTES),
        name = name,
        folderId = folderId,
        mimeType = "video/mp4",
        plainSize = (chunks * VaultFormat.CHUNK_PAYLOAD_BYTES).toLong(),
        chunkSalt = Secrets.randomBytes(VaultFormat.CHUNK_SALT_BYTES),
        addedAtMillis = 1_700_000_000_000,
        modifiedAtMillis = 1_700_000_000_000,
    )

    @Test
    fun `the whole virtual hierarchy survives a commit and a load`() {
        val (mk, realmId) = realm("hierarchy")
        val photos = FolderRecord("photos", null, "Photos")
        val year = FolderRecord("y2026", "photos", "2026")
        val loose = record("loose-note.txt", null)
        val movie = record("honeymoon-movie.mp4", "y2026", chunks = 3)
        val index = VaultIndex(folders = listOf(photos, year), files = listOf(loose, movie))

        assertWithMessage("an uncommitted realm has no index")
            .that(indexStore.load(mk, realmId))
            .isNull()

        indexStore.commit(mk, realmId, index, nowMillis = 1_700_000_000_000)
        val loaded = indexStore.load(mk, realmId)!!
        val (back, generation) = loaded

        assertThat(back.folders).containsExactly(photos, year).inOrder()
        assertThat(back.files).containsExactly(loose, movie)
        assertThat(back.pathOf(movie.fileId)).isEqualTo("/Photos/2026/honeymoon-movie.mp4")
        assertThat(back.pathOf(loose.fileId)).isEqualTo("/loose-note.txt")
        assertWithMessage("generation one is the first").that(generation.generation).isEqualTo(1)
    }

    @Test
    fun `five commits do not grow the blob pool because the old generation is deleted`() {
        val (mk, realmId) = realm("generations")
        val poolSizes = mutableListOf<Int>()

        repeat(5) { pass ->
            val index = VaultIndex(
                folders = listOf(FolderRecord("f", null, "Notes")),
                files = List(pass + 1) { record("note-$pass.txt", "f") },
            )
            indexStore.commit(mk, realmId, index, nowMillis = 1_700_000_000L + pass)
            poolSizes += store.blobIds().size
        }

        assertWithMessage("blob pool after each commit: $poolSizes")
            .that(poolSizes.last())
            .isAtMost(poolSizes.first() + 2)
        val (current, generation) = indexStore.load(mk, realmId)!!
        assertThat(generation.generation).isEqualTo(5)
        assertThat(current.fileCount).isEqualTo(5)
    }

    @Test
    fun `a wrong master key cannot even name the anchor blob`() {
        val (mk, realmId) = realm("locked-out")
        indexStore.commit(mk, realmId, VaultIndex(files = listOf(record("diploma.pdf", null))), 1)
        val (otherMk, otherRealm) = realm("wrong passphrase")

        assertWithMessage("anchor names come from the key, so there is nothing to find without it")
            .that(indexStore.anchorBlobId(otherMk, otherRealm).toList())
            .isNotEqualTo(indexStore.anchorBlobId(mk, realmId).toList())
        assertThat(store.exists(indexStore.anchorBlobId(otherMk, otherRealm))).isFalse()
        assertWithMessage("so a wrong key means no index at all, not a partially wrong one")
            .that(indexStore.load(otherMk, otherRealm))
            .isNull()
    }

    @Test
    fun `a tampered anchor fails authentication instead of returning garbage`() {
        val (mk, realmId) = realm("tamper")
        indexStore.commit(mk, realmId, VaultIndex(), 1)
        val anchorId = indexStore.anchorBlobId(mk, realmId)
        val blob = store.read(anchorId)
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        store.write(anchorId, blob)

        val failure = assertThrows<VaultAuthenticationException> { indexStore.load(mk, realmId) }
        assertThat(failure.chunkIndex).isEqualTo(0)
    }

    @Test
    fun `a large index is chunked and scattered like any other file`() {
        val (mk, realmId) = realm("spread")
        val many = VaultIndex(
            folders = listOf(FolderRecord("f", null, "Library")),
            files = (1..12_000).map { record("very-long-file-name-for-padding-reasons-$it.txt", "f") },
        )

        indexStore.commit(mk, realmId, many, 7)
        val (back, generation) = indexStore.load(mk, realmId)!!

        assertWithMessage("the index exceeds one chunk, so its own blobs are scattered too")
            .that(generation.chunkCount)
            .isGreaterThan(1)
        assertWithMessage("and the anchor stays one size class: it lists no blob ids")
            .that(store.size(indexStore.anchorBlobId(mk, realmId)))
            .isEqualTo((VaultFormat.SIZE_CLASS_BYTES + VaultFormat.GCM_TAG_BYTES).toLong())
        assertThat(back.fileCount).isEqualTo(12_000)
        assertWithMessage("no two blobs in the pool share a name")
            .that(store.blobIds().map { it.toList() }.toSet().size)
            .isEqualTo(store.blobIds().size)
    }
}

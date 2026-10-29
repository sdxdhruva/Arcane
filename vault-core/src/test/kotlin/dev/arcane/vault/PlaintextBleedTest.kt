package dev.arcane.vault

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.arcane.vault.crypto.KdfParams
import dev.arcane.vault.crypto.KeyDerivation
import dev.arcane.vault.crypto.RealmDefaults
import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.file.ChunkWriter
import dev.arcane.vault.format.Hex
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.index.FileRecord
import dev.arcane.vault.index.FolderRecord
import dev.arcane.vault.index.IndexStore
import dev.arcane.vault.index.VaultIndex
import dev.arcane.vault.realm.RealmHeaderStore
import dev.arcane.vault.realm.RealmOpener
import dev.arcane.vault.realm.UnlockedRealm
import dev.arcane.vault.store.FileBlobStore
import dev.arcane.vault.store.VaultLayout
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The direct test of the claim the app rests on: once files are in the vault, nothing in the data
 * directory — not a name, not a path, not a byte — identifies them. This walks every byte the app wrote
 * and looks for the strings and content that went in.
 */
class PlaintextBleedTest {

    @TempDir
    lateinit var root: File

    private val layout by lazy { VaultLayout(root) }
    private val blobs by lazy { FileBlobStore(layout) }
    private val headers by lazy { RealmHeaderStore(layout) }
    private val opener by lazy { RealmOpener(blobs, headers) }
    private val indexStore by lazy { IndexStore(blobs) }

    private val realPassphrase = "Correct-Horse-Battery-Staple-99".toByteArray()
    private val decoyPassphrase = "Groceries-And-Recipes".toByteArray()

    private val secretNames = listOf(
        "Diploma Of Secret Things",
        "honeymoon-2026-take",
        "notes about the boss",
        "Private Notes",
        "Photos",
        "banana-bread",
        ".pdf",
        ".mkv",
        ".txt",
        "video/x-matroska",
        "application/pdf",
        "text/plain",
    )

    private val canaries = listOf(
        "TOP-SECRET-2026-DO-NOT-READ",
        "MBA GRADUATE - CONFIDENTIAL",
        "boss is going to be fired on friday",
        "two eggs, one banana",
    )

    private fun params(tag: String) =
        KdfParams(pseudoRandom(16, tag.hashCode()), RealmDefaults.MEMORY_KIB / 2, 1, 1)

    @Test
    fun `nothing in the data directory identifies anything`() {
        val realm = opener.initialise(0, realPassphrase, params("bleed-real"))
        val decoy = opener.initialise(1, decoyPassphrase, params("bleed-decoy"))
        val photos = FolderRecord("photos", null, "Photos")
        val year = FolderRecord("y2026", "photos", "2026")
        val notes = FolderRecord("notes", null, "Private Notes")

        val diploma = import(
            realm,
            name = "Diploma Of Secret Things.pdf",
            folderId = year.id,
            mime = "application/pdf",
            content = ("%PDF-1.7 " + canaries[1]).toByteArray() + pseudoRandom(600_000, 21),
        )
        val movie = import(
            realm,
            name = "honeymoon-2026-take.mkv",
            folderId = year.id,
            mime = "video/x-matroska",
            content = canaries[0].toByteArray() + pseudoRandom(1_300_000, 22),
        )
        val memo = import(
            realm,
            name = "notes about the boss.txt",
            folderId = notes.id,
            mime = "text/plain",
            content = canaries[2].toByteArray() + pseudoRandom(4_096, 23),
        )
        // The decoy holds what a coercer would be satisfied with, in the very same blob pool.
        val recipe = import(
            decoy,
            name = "banana-bread.txt",
            folderId = null,
            mime = "text/plain",
            content = canaries[3].toByteArray(),
        )

        val index = VaultIndex(folders = listOf(photos, year, notes), files = listOf(diploma, movie, memo))
        indexStore.commit(realm.masterKey, realm.realmId, index, nowMillis = 1_700_000_000_000)
        indexStore.commit(decoy.masterKey, decoy.realmId, VaultIndex(files = listOf(recipe)), 1_700_000_000_001)

        val files = root.walkTopDown().filter { it.isFile }.toList()
        val blobFiles = files.filter { it.name.endsWith(VaultFormat.BLOB_SUFFIX) }
        val bytesOnDisk = files.sumOf { it.length() }
        val tokens = secretNames + canaries +
            listOf(String(realPassphrase), String(decoyPassphrase)) +
            listOf(diploma, movie, memo, recipe).flatMap { listOf(Hex.lower(it.fileId), it.name) } +
            listOf("Arcane", "arcane", "vault", "secret", "encrypted", "kdfheader")

        val hits = files.flatMap { file ->
            val text = file.readBytes().toString(Charsets.ISO_8859_1)
            tokens.filter { it.isNotEmpty() && text.contains(it) }.map { "${file.relativeTo(root)} contains \"$it\"" }
        } + files.flatMap { file ->
            val name = file.relativeTo(root).path
            tokens.filter { it.length > 4 && name.contains(it) }.map { "filename $name exposes \"$it\"" }
        }

        println(
            buildString {
                appendLine("arcane bleed scan: ${files.size} files, $bytesOnDisk bytes under ${root.name}")
                appendLine("  blob files: ${blobFiles.size}, header: ${files.filterNot { it == layout.kdfHeader }.size - blobFiles.size} stray")
                appendLine("  tokens searched: ${tokens.size}")
                appendLine("  longest alphanumeric run in the blob pool: ${longestAlnumRun(blobFiles)} chars")
            },
        )

        assertWithMessage("plaintext bleed: $hits").that(hits).isEmpty()
        assertWithMessage("every blob is 32-hex named, so names carry no meaning")
            .that(blobFiles.all { Regex("^[0-9a-f]{32}\\.bin$").matches(it.name) })
            .isTrue()
        assertWithMessage("nothing lives outside the vault directory")
            .that(files.all { it.toRelativeString(root).startsWith("vlt/") })
            .isTrue()
        assertWithMessage("no plaintext cache directory was created by the vault path")
            .that(layout.tempPlainRoot.walkTopDown().filter { it.isFile }.toList())
            .isEmpty()
        assertWithMessage("sizes are class-quantised, so a chunk of a movie and a chunk of the index match")
            .that(blobFiles.map { it.length() % VaultFormat.SIZE_CLASS_BYTES }.distinct())
            .containsExactly(VaultFormat.GCM_TAG_BYTES.toLong())
        assertThat(longestAlnumRun(blobFiles)).isAtMost(20)
    }

    private fun import(
        realm: UnlockedRealm,
        name: String,
        folderId: String?,
        mime: String,
        content: ByteArray,
    ): FileRecord {
        val fileId = Secrets.randomBytes(VaultFormat.FILE_ID_BYTES)
        val chunkSalt = Secrets.randomBytes(VaultFormat.CHUNK_SALT_BYTES)
        ChunkWriter(
            blobs = blobs,
            fileId = fileId,
            chunkSalt = chunkSalt,
            fileKey = KeyDerivation.deriveFileKey(realm.masterKey, fileId),
            nameKey = KeyDerivation.deriveBlobNameKey(realm.masterKey, realm.realmId),
            plainSize = content.size.toLong(),
        )
            .apply { write(content) }
            .finish()
        return FileRecord(
            fileId = fileId,
            name = name,
            folderId = folderId,
            mimeType = mime,
            plainSize = content.size.toLong(),
            chunkSalt = chunkSalt,
            addedAtMillis = 1_700_000_000_000,
            modifiedAtMillis = 1_700_000_000_000,
        )
    }

    /**
     * Random data has short letter-and-digit runs; a leaked filename, hex digest or base64 blob has long
     * ones. This is the shape a memory-forensics analyst would grep for first.
     */
    private fun longestAlnumRun(files: List<File>): Int {
        var best = 0
        var current = 0
        files.forEach { file ->
            file.forEachBlock(64 * 1024) { buffer, read ->
                for (i in 0 until read) {
                    val v = buffer[i].toInt() and 0xff
                    current = if ((v in 48..57) || (v in 65..90) || (v in 97..122)) current + 1 else 0
                    if (current > best) best = current
                }
            }
        }
        return best
    }
}

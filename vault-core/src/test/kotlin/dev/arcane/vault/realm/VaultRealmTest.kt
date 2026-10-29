package dev.arcane.vault.realm

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.arcane.vault.crypto.KdfParams
import dev.arcane.vault.crypto.KeyDerivation
import dev.arcane.vault.crypto.RealmDefaults
import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.file.ChunkMap
import dev.arcane.vault.file.ChunkWriter
import dev.arcane.vault.index.FileRecord
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.pseudoRandom
import dev.arcane.vault.store.FileBlobStore
import dev.arcane.vault.store.VaultLayout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

private const val CHUNK = VaultFormat.CHUNK_PAYLOAD_BYTES

/** Acts like a process killed mid-import: bytes flow, then the reader simply stops existing. */
private class FlakySource(private val content: ByteArray, private val killAfter: Int) : InputStream() {
    private var sent = 0

    override fun read(): Int {
        if (sent >= killAfter) throw IOException("process died")
        return content[sent++].toInt() and 0xff
    }

    override fun read(target: ByteArray, off: Int, len: Int): Int {
        if (sent >= killAfter) throw IOException("process died")
        val n = minOf(len, killAfter - sent, content.size - sent)
        if (n <= 0) throw IOException("source exhausted")
        content.copyInto(target, off, sent, sent + n)
        sent += n
        return n
    }
}

class VaultRealmTest {

    @TempDir
    lateinit var root: File

    private val layout by lazy { VaultLayout(root) }
    private val blobs by lazy { FileBlobStore(layout) }
    private val headers by lazy { RealmHeaderStore(layout) }
    private val opener by lazy { RealmOpener(blobs, headers) }
    private val journal by lazy { WriteJournal(blobs) }

    private var now = 1_700_000_000_000L

    private fun params(tag: String) =
        KdfParams(pseudoRandom(16, tag.hashCode()), RealmDefaults.MEMORY_KIB / 2, 1, 1)

    private fun facade(
        unlocked: UnlockedRealm,
        stride: Long = WriteJournal.PROGRESS_STRIDE_CHUNKS,
    ): VaultRealm = VaultRealm(unlocked, blobs, journal = journal, progressStride = stride, nowMillis = { now })

    private fun newRealm(tag: String = "passphrase-for-test", slot: Int = 0): UnlockedRealm =
        opener.initialise(slot, tag.toByteArray(), params(tag))

    /** Reads the index back off disk, which is what a process that survived the crash would see. */
    private fun reopen(tag: String = "passphrase-for-test"): VaultRealm =
        facade(requireNotNull(opener.unlock(tag.toByteArray())) { "realm would not reopen" })

    private fun names(vault: VaultRealm, fileId: ByteArray): List<ByteArray> {
        val map: ChunkMap = vault.open(fileId)!!
        return try {
            map.blobIds()
        } finally {
            map.wipe()
        }
    }

    private fun readWhole(vault: VaultRealm, fileId: ByteArray, size: Int): ByteArray {
        val map = vault.open(fileId)!!
        return try {
            vault.reader.readAt(map, 0, size)
        } finally {
            map.wipe()
        }
    }

    private fun importInto(vault: VaultRealm, name: String, size: Int, folderId: String? = null): FileRecord =
        vault.import(
            ByteArrayInputStream(pseudoRandom(size, seed = name.hashCode())),
            name,
            folderId,
            "application/octet-stream",
            size.toLong(),
        )

    private fun deleteItem(fileId: ByteArray, plainSize: Long, chunkSalt: ByteArray) = PendingItem(
        kind = PendingKind.Delete,
        fileId = fileId,
        plainSize = plainSize,
        chunkSalt = chunkSalt,
        startedAtMillis = now,
    )

    @Test
    fun `an import lands in the tree and reads back byte for byte`() {
        val vault = facade(newRealm())
        val content = pseudoRandom(CHUNK + 4096, seed = 41)
        val photos = vault.createFolder("Photos", null)

        val ticks = mutableListOf<Long>()
        val record = vault.import(
            ByteArrayInputStream(content),
            "holiday.mkv",
            photos.id,
            "video/x-matroska",
            content.size.toLong(),
            onProgress = { ticks += it },
        )

        assertWithMessage("the bar sees every read, including the final short one")
            .that(ticks)
            .containsExactly(CHUNK.toLong(), content.size.toLong())
        assertThat(vault.index.files).containsExactly(record)
        assertThat(vault.index.pathOf(record.fileId)).isEqualTo("/Photos/holiday.mkv")
        assertWithMessage("a completed import leaves no promise behind")
            .that(vault.pendingItems)
            .isEmpty()

        val map = vault.open(record.fileId)!!
        try {
            assertThat(vault.reader.readAt(map, 0, content.size)).isEqualTo(content)
            assertThat(vault.reader.readAt(map, (CHUNK - 100).toLong(), 500))
                .isEqualTo(content.copyOfRange(CHUNK - 100, CHUNK + 400))
        } finally {
            map.wipe()
        }
    }

    @Test
    fun `an import killed mid-way resumes at the journaled chunk and takes only the rest`() {
        val unlocked = newRealm()
        val vault = facade(unlocked, stride = 2)
        val content = pseudoRandom(3 * CHUNK + 1234, seed = 42)

        assertThrows<IOException> {
            vault.import(
                FlakySource(content, killAfter = 2 * CHUNK + 10),
                "cut.mkv",
                null,
                "video/x-matroska",
                content.size.toLong(),
            )
        }
        assertWithMessage("a half-finished import is not listed").that(vault.index.files).isEmpty()
        val item = vault.pendingItems.single()
        assertWithMessage("progress is kept at chunk granularity")
            .that(item.chunksDone)
            .isEqualTo(2L)
        assertThat(item.isComplete).isFalse()
        assertThat(item.name).isEqualTo("cut.mkv")
        assertWithMessage("which is where the original file has to be positioned for the retry")
            .that(vault.resumeOffset(item))
            .isEqualTo(2L * CHUNK)

        // Handing over the *tail* only is the proof that nothing before the journaled chunk is re-read.
        val record = vault.resume(item, ByteArrayInputStream(content.copyOfRange(2 * CHUNK, content.size)))

        assertThat(record.name).isEqualTo("cut.mkv")
        assertThat(vault.pendingItems).isEmpty()
        assertWithMessage("resumed chunks and original chunks are indistinguishable and correct")
            .that(readWhole(vault, record.fileId, content.size))
            .isEqualTo(content)
        assertWithMessage("the resumed file occupies exactly the slots the first attempt would have used")
            .that(names(vault, record.fileId))
            .hasSize(4)
    }

    @Test
    fun `journal progress moves in strides rather than every chunk`() {
        val vault = facade(newRealm())
        val content = pseudoRandom(68 * CHUNK, seed = 47)

        assertThrows<IOException> {
            vault.import(
                FlakySource(content, killAfter = 66 * CHUNK),
                "many.bin",
                null,
                null,
                content.size.toLong(),
            )
        }

        assertWithMessage("66 chunks committed, but only the stride boundary was worth an fsync")
            .that(vault.pendingItems.single().chunksDone)
            .isEqualTo(WriteJournal.PROGRESS_STRIDE_CHUNKS)
    }

    @Test
    fun `cancelling a partial import takes its chunks with it`() {
        val vault = facade(newRealm())
        assertThrows<IOException> {
            vault.import(
                FlakySource(pseudoRandom(CHUNK + 5, seed = 43), killAfter = CHUNK),
                "abandoned.txt",
                null,
                "text/plain",
                (CHUNK + 5).toLong(),
            )
        }
        assertWithMessage("chunk, promise, listing and anchor")
            .that(blobs.blobIds())
            .hasSize(4)

        vault.cancel(vault.pendingItems.single())

        assertThat(vault.pendingItems).isEmpty()
        assertWithMessage("nothing of an abandoned import survives")
            .that(blobs.blobIds())
            .hasSize(2)
    }

    @Test
    fun `a delete removes the listing and every derived name it ever promised`() {
        val vault = facade(newRealm())
        val record = importInto(vault, "sensitive.pdf", 2 * CHUNK)
        val names = names(vault, record.fileId)
        assertWithMessage("two chunks of content").that(names).hasSize(2)

        assertThat(vault.delete(record.fileId)).isTrue()

        assertWithMessage("no blob of the deleted file is left").that(names.any(blobs::exists)).isFalse()
        assertWithMessage("and nothing else is in the pool either")
            .that(blobs.blobIds())
            .hasSize(2)
        assertThat(vault.file(record.fileId)).isNull()
        assertThat(vault.pendingItems).isEmpty()
        assertWithMessage("deleting what is already gone is a no, not an error")
            .that(vault.delete(record.fileId))
            .isFalse()
    }

    @Test
    fun `closing a viewer does not corrupt the listing it came from`() {
        val vault = facade(newRealm())
        val record = importInto(vault, "watch-me.mp4", CHUNK)

        vault.open(record.fileId)!!.wipe()

        assertWithMessage("the record kept its own copy of the salt, so the file still plays")
            .that(readWhole(vault, record.fileId, CHUNK))
            .isEqualTo(pseudoRandom(CHUNK, seed = "watch-me.mp4".hashCode()))
        assertWithMessage("and its chunk names are still the ones on disk")
            .that(names(vault, record.fileId).all(blobs::exists))
            .isTrue()
    }

    @Test
    fun `rename and move are index-only writes that leave every ciphertext byte alone`() {
        val vault = facade(newRealm())
        val folder = vault.createFolder("Archive", null).id
        val record = importInto(vault, "before-name.mkv", 2 * CHUNK)
        val bytesBefore = names(vault, record.fileId).map { blobs.read(it).toList() }

        vault.renameFile(record.fileId, "after-name.mkv")
        vault.moveFile(record.fileId, folder)

        assertWithMessage("the tree moved, the content did not")
            .that(names(vault, record.fileId).map { blobs.read(it).toList() })
            .isEqualTo(bytesBefore)
        assertThat(vault.file(record.fileId)?.name).isEqualTo("after-name.mkv")
        assertThat(vault.index.pathOf(record.fileId)).isEqualTo("/Archive/after-name.mkv")
        assertWithMessage("the key follows the id, so a rename still decrypts")
            .that(readWhole(vault, record.fileId, 2 * CHUNK).toList())
            .isEqualTo(pseudoRandom(2 * CHUNK, seed = "before-name.mkv".hashCode()).toList())
    }

    @Test
    fun `reconcile re-lists an import whose chunks were all written but never committed`() {
        val unlocked = newRealm()
        val content = pseudoRandom(CHUNK, seed = 44)
        val fileId = Secrets.randomBytes(VaultFormat.FILE_ID_BYTES)
        val chunkSalt = Secrets.randomBytes(VaultFormat.CHUNK_SALT_BYTES)
        // The kill lands between the last journal tick and the index commit: chunks exist, no listing does.
        ChunkWriter(
            blobs = blobs,
            fileId = fileId,
            chunkSalt = chunkSalt,
            fileKey = KeyDerivation.deriveFileKey(unlocked.masterKey, fileId),
            nameKey = KeyDerivation.deriveBlobNameKey(unlocked.masterKey, unlocked.realmId),
            plainSize = content.size.toLong(),
        ).apply { write(content) }.finish()
        journal.record(
            unlocked.masterKey,
            unlocked.realmId,
            PendingItem(
                kind = PendingKind.Import,
                fileId = fileId,
                plainSize = content.size.toLong(),
                chunkSalt = chunkSalt,
                chunksDone = 1,
                name = "unlisted.mkv",
                folderId = null,
                mimeType = "video/x-matroska",
                startedAtMillis = now,
            ),
        )

        val fresh = reopen()
        assertThat(fresh.file(fileId)).isNull()
        val report = fresh.reconcile()

        assertWithMessage("the file is recovered rather than thrown away").that(report.recovered).isEqualTo(1)
        assertThat(fresh.file(fileId)?.name).isEqualTo("unlisted.mkv")
        assertThat(fresh.pendingItems).isEmpty()
        assertWithMessage("and it plays, byte for byte").that(readWhole(fresh, fileId, CHUNK)).isEqualTo(content)
    }

    @Test
    fun `reconcile finishes a delete that died before the listing was dropped`() {
        val unlocked = newRealm()
        val vault = facade(unlocked)
        val doomed = importInto(vault, "doomed.txt", 2 * CHUNK)
        val names = names(vault, doomed.fileId)

        journal.record(unlocked.masterKey, unlocked.realmId, deleteItem(doomed.fileId, doomed.plainSize, doomed.chunkSalt))
        val report = reopen().reconcile()

        assertWithMessage("the user asked for this file to be gone, so the restart finishes asking")
            .that(report.deletionsFinished)
            .isEqualTo(1)
        assertWithMessage("nothing of it is left in the pool").that(names.any(blobs::exists)).isFalse()
        assertThat(blobs.blobIds()).hasSize(2)
    }

    @Test
    fun `reconcile sweeps the chunks a delete left behind mid-way`() {
        val unlocked = newRealm()
        val vault = facade(unlocked)
        val doomed = importInto(vault, "half-gone.mkv", 2 * CHUNK)
        val names = names(vault, doomed.fileId)
        journal.record(unlocked.masterKey, unlocked.realmId, deleteItem(doomed.fileId, doomed.plainSize, doomed.chunkSalt))
        // The crash lands inside the sweep: one slot is already overwritten and unlinked.
        blobs.delete(names.first())

        val afterCrash = reopen()
        val report = afterCrash.reconcile()

        assertThat(report.deletionsFinished).isEqualTo(1)
        assertWithMessage("the surviving chunk is removed with the rest")
            .that(names.any(blobs::exists))
            .isFalse()
        assertThat(afterCrash.file(doomed.fileId)).isNull()
        assertThat(blobs.blobIds()).hasSize(2)
    }

    @Test
    fun `one realm's cleanup cannot reach into the other realm's blobs`() {
        val real = facade(newRealm("the-real-one", slot = 0))
        val decoy = facade(newRealm("the-decoy", slot = 1))
        val realFile = importInto(real, "tax-return.pdf", CHUNK)
        val decoyFile = importInto(decoy, "banana-bread.txt", CHUNK)
        val decoyNames = names(decoy, decoyFile.fileId)

        real.delete(realFile.fileId)
        val report = real.reconcile()

        assertWithMessage("the real realm's sweep cannot name the decoy's blobs").that(report.recovered).isEqualTo(0)
        assertWithMessage("so they are still exactly where the decoy left them")
            .that(decoyNames.all(blobs::exists))
            .isTrue()
        assertWithMessage("and the decoy still reads its own file, untouched")
            .that(readWhole(decoy, decoyFile.fileId, CHUNK))
            .isEqualTo(pseudoRandom(CHUNK, seed = decoyFile.name.hashCode()))
    }

    @Test
    fun `a folder cannot be reparented into its own subtree`() {
        val vault = facade(newRealm())
        val top = vault.createFolder("Top", null)
        val middle = vault.createFolder("Middle", top.id)
        val leaf = vault.createFolder("Leaf", middle.id)

        assertThrows<IllegalArgumentException> { vault.moveFolder(top.id, middle.id) }
        assertThrows<IllegalArgumentException> { vault.moveFolder(top.id, top.id) }
        assertWithMessage("a rejected move leaves the tree exactly as it was")
            .that(vault.index.pathOfFolder(leaf.id))
            .isEqualTo("/Top/Middle/Leaf")

        vault.moveFolder(leaf.id, null)
        assertThat(vault.index.pathOfFolder(leaf.id)).isEqualTo("/Leaf")
        assertThat(vault.index.pathOfFolder(middle.id)).isEqualTo("/Top/Middle")
    }

    @Test
    fun `a folder holding a file refuses to be dropped`() {
        val vault = facade(newRealm())
        val folder = vault.createFolder("Keep", null)
        importInto(vault, "inside.txt", 128, folderId = folder.id)

        assertThrows<IllegalArgumentException> { vault.deleteFolder(folder.id) }
        assertThat(vault.index.folder(folder.id)).isNotNull()

        assertThat(vault.delete(vault.index.files.single().fileId)).isTrue()
        assertThat(vault.deleteFolder(folder.id)).isTrue()
        assertThat(vault.index.folders).isEmpty()
    }

    @Test
    fun `a deleted folder's descendants go with it but its siblings stay`() {
        val vault = facade(newRealm())
        val top = vault.createFolder("Top", null)
        val child = vault.createFolder("Child", top.id)
        vault.createFolder("Grandchild", child.id)
        vault.createFolder("Sibling", null)

        assertThat(vault.deleteFolder(top.id)).isTrue()

        assertWithMessage("the whole subtree is gone")
            .that(vault.index.folders.map { it.name })
            .containsExactly("Sibling")
        assertWithMessage("and the listing is still an index commit, not a hand-edit")
            .that(reopen().index.folders.map { it.name })
            .containsExactly("Sibling")
    }

    @Test
    fun `an import into a folder that does not exist is refused before any blob is written`() {
        val vault = facade(newRealm())
        val content = pseudoRandom(1024, seed = 51)

        assertThrows<IllegalArgumentException> {
            vault.import(ByteArrayInputStream(content), "orphan.txt", "no-such-folder", null, content.size.toLong())
        }

        assertWithMessage("refusal happens at the promise, so the pool never saw a byte")
            .that(blobs.blobIds())
            .hasSize(2)
        assertThat(vault.pendingItems).isEmpty()
    }

    @Test
    fun `a source that shrank under the declared size stops at the truth`() {
        val vault = facade(newRealm(), stride = 1)
        val declared = 3 * CHUNK
        val actuallyThere = CHUNK + 100

        val failure = assertThrows<SourceShrank> {
            vault.import(
                ByteArrayInputStream(pseudoRandom(actuallyThere, seed = 52)),
                "lying.txt",
                null,
                null,
                declared.toLong(),
            )
        }

        assertWithMessage(failure.message.orEmpty()).that(vault.index.files).isEmpty()
        val item = vault.pendingItems.single()
        assertWithMessage("the full chunk was worth recording, the short tail was not")
            .that(item.chunksDone)
            .isEqualTo(1L)
        vault.cancel(item)
        assertThat(blobs.blobIds()).hasSize(2)
    }

    @Test
    fun `locking the realm mid import stops the transfer and costs the vault nothing`() {
        val unlocked = newRealm()
        val vault = facade(unlocked, stride = 1)
        val content = pseudoRandom(4 * CHUNK, seed = 71)
        val before = layout.allBlobFiles().size
        val source = SelfLockingSource(content, lockAfter = CHUNK + 16, lock = unlocked::wipe)

        assertThrows<RealmLockedException> {
            vault.import(source, "holiday.mp4", null, "video/mp4", content.size.toLong())
        }

        assertThat(vault.index.files).isEmpty()
        val report = reopen().reconcile()
        assertWithMessage("the transfer stopped early, so there is nothing to resume")
            .that(report.resumable)
            .isEmpty()
        assertWithMessage("and the undo reclaimed every byte the aborted chunks took")
            .that(layout.allBlobFiles().size)
            .isEqualTo(before)

        val after = reopen()
        val record = after.import(
            ByteArrayInputStream(content),
            "holiday.mp4",
            null,
            "video/mp4",
            content.size.toLong(),
        )
        assertWithMessage("the same passphrase still opens the same realm, with the same anchor")
            .that(readWhole(after, record.fileId, content.size))
            .isEqualTo(content)
    }

    @Test
    fun `a locked realm refuses to write and refuses to be read`() {
        val unlocked = newRealm()
        val vault = facade(unlocked)
        val note = pseudoRandom(1024, seed = 72)
        val listed = vault.import(ByteArrayInputStream(note), "note.txt", null, "text/plain", note.size.toLong())
        val folder = vault.createFolder("Notes", null)

        unlocked.wipe()

        assertThrows<RealmLockedException> { vault.createFolder("Second", folder.id) }
        assertThrows<RealmLockedException> { vault.delete(listed.fileId) }
        assertThrows<RealmLockedException> { vault.renameFile(listed.fileId, "renamed.txt") }
        assertThrows<RealmLockedException> { vault.reconcile() }
        assertThrows<RealmLockedException> {
            val junk = pseudoRandom(64, seed = 73)
            vault.import(ByteArrayInputStream(junk), "x.bin", null, null, junk.size.toLong())
        }
        assertWithMessage("a handle derived from a zeroed key would only fail authentication further downstream")
            .that(vault.open(listed.fileId))
            .isNull()
    }
}

class WriteJournalTest {

    @TempDir
    lateinit var root: File

    private val layout by lazy { VaultLayout(root) }
    private val blobs by lazy { FileBlobStore(layout) }
    private val journal by lazy { WriteJournal(blobs) }

    private fun params(tag: String) =
        KdfParams(pseudoRandom(16, tag.hashCode()), RealmDefaults.MEMORY_KIB / 2, 1, 1)

    private val masterKey by lazy { KeyDerivation.argon2id("journal-test".toByteArray(), params("journal-test")) }
    private val realmId by lazy { KeyDerivation.deriveRealmId(masterKey, params("journal-test").salt) }

    private fun item(index: Int, nameLength: Int = 24, plainSize: Long = 1234L) = PendingItem(
        kind = PendingKind.Import,
        fileId = pseudoRandom(VaultFormat.FILE_ID_BYTES, index),
        plainSize = plainSize,
        chunkSalt = pseudoRandom(VaultFormat.CHUNK_SALT_BYTES, index),
        name = "n".repeat(nameLength),
        folderId = null,
        mimeType = null,
        startedAtMillis = 1_700_000_000_000L + index,
    )

    @Test
    fun `the journal is one size class no matter how much is in it`() {
        journal.record(masterKey, realmId, item(1))
        val name = journal.blobId(masterKey, realmId)
        val smallSize = blobs.size(name)
        repeat(30) { journal.record(masterKey, realmId, item(it + 2, nameLength = 200)) }

        assertWithMessage("a 31-item journal and a 1-item journal are the same number of bytes")
            .that(blobs.size(name))
            .isEqualTo(smallSize)
        assertThat(journal.load(masterKey, realmId)).hasSize(31)
        assertWithMessage("and it is exactly a padded chunk, like any other small blob in the pool")
            .that(smallSize)
            .isEqualTo((VaultFormat.SIZE_CLASS_BYTES + VaultFormat.GCM_TAG_BYTES).toLong())
    }

    @Test
    fun `a settled journal disappears instead of sitting there empty`() {
        journal.record(masterKey, realmId, item(7))
        assertThat(blobs.exists(journal.blobId(masterKey, realmId))).isTrue()

        journal.settle(masterKey, realmId, item(7).fileId)

        assertWithMessage("an empty promise list is not a blob")
            .that(blobs.exists(journal.blobId(masterKey, realmId)))
            .isFalse()
        assertThat(journal.load(masterKey, realmId)).isEmpty()
    }

    @Test
    fun `more promises than one envelope can hold is a refusal, not a silent drop`() {
        var thrown: JournalFull? = null
        var kept = 0
        repeat(200) { index ->
            try {
                journal.record(masterKey, realmId, item(index, nameLength = 300))
                kept = index + 1
            } catch (full: JournalFull) {
                thrown = full
            }
        }

        assertWithMessage("refusing is safe; forgetting a promise would strand its blobs forever")
            .that(thrown)
            .isNotNull()
        assertWithMessage("and the journal still holds what it held before the refusal")
            .that(journal.load(masterKey, realmId))
            .hasSize(kept)
    }

    @Test
    fun `progress ticks rewrite the same item rather than adding one`() {
        journal.record(masterKey, realmId, item(3))

        journal.record(masterKey, realmId, item(3, plainSize = 64L * VaultFormat.CHUNK_PAYLOAD_BYTES))
        repeat(20) { tick -> journal.progress(masterKey, realmId, item(3).fileId, tick.toLong()) }
        val items = journal.load(masterKey, realmId)

        assertThat(items).hasSize(1)
        assertWithMessage("the last tick is what a later process would resume from")
            .that(items.single().chunksDone)
            .isEqualTo(19L)
    }

    @Test
    fun `a journal written by one realm cannot be read by another`() {
        journal.record(masterKey, realmId, item(11))
        val other = KeyDerivation.argon2id("different passphrase".toByteArray(), params("other-realm"))
        val otherRealm = KeyDerivation.deriveRealmId(other, params("other-realm").salt)

        assertWithMessage("there is no pointer to follow, only a name both realms would have to guess")
            .that(blobs.exists(journal.blobId(other, otherRealm)))
            .isFalse()
        assertThat(journal.load(other, otherRealm)).isEmpty()
        assertThat(journal.load(masterKey, realmId)).hasSize(1)
    }

    @Test
    fun `a delete promise needs no name because there is nothing to list it under`() {
        val deleted = PendingItem(
            kind = PendingKind.Delete,
            fileId = pseudoRandom(16, 5),
            plainSize = 4096,
            chunkSalt = pseudoRandom(4, 5),
            startedAtMillis = now(),
        )

        journal.record(masterKey, realmId, deleted)

        assertThat(journal.load(masterKey, realmId).single()).isEqualTo(deleted)
        assertWithMessage("a delete knows its chunk count from size alone")
            .that(deleted.chunkCount)
            .isEqualTo(1L)
    }

    private fun now() = 1_700_000_000_000L
}

/** Hands over bytes, then locks the realm partway through — the background timer firing during a transfer. */
private class SelfLockingSource(
    private val content: ByteArray,
    private val lockAfter: Int,
    private val lock: () -> Unit,
) : InputStream() {
    private var sent = 0

    override fun read(): Int {
        fire()
        return if (sent >= content.size) -1 else content[sent++].toInt() and 0xff
    }

    override fun read(target: ByteArray, off: Int, len: Int): Int {
        fire()
        val n = minOf(len, content.size - sent)
        if (n <= 0) return -1
        content.copyInto(target, off, sent, sent + n)
        sent += n
        return n
    }

    private fun fire() {
        if (sent >= lockAfter) lock()
    }
}

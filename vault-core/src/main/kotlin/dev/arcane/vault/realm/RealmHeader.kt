package dev.arcane.vault.realm

import dev.arcane.vault.crypto.KdfParams
import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.format.Hex
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.store.VaultLayout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * One realm's public key-derivation record. This is the only structure on disk that is not encrypted,
 * and it carries only what a password-based scheme has to publish: a salt and the Argon2id cost.
 *
 * Nothing records which realm is real and which is the decoy. A slot that is not in use is filled with
 * random bytes, so the header is always exactly [RealmSlot.HEADER_BYTES] and an empty slot is
 * indistinguishable from a live one.
 */
data class RealmSlot(
    val formatVersion: Int,
    val salt: ByteArray,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    val kdfParams: KdfParams
        get() = KdfParams(salt, memoryKiB, iterations, parallelism)

    init {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
    }

    companion object {
        const val SALT_BYTES = 16
        const val SLOT_BYTES = 32
        const val SLOT_COUNT = 2
        const val HEADER_BYTES = SLOT_BYTES * SLOT_COUNT

        const val MIN_MEMORY_KIB = KdfParams.MIN_MEMORY_KIB
        const val MAX_MEMORY_KIB = 1024 * 1024
        const val MAX_ITERATIONS = 16

        /**
         * Turns 32 stored bytes into a slot, or null if they are not a slot. Random filler is the
         * normal case here, so this runs against garbage constantly and must never be able to talk
         * the caller into allocating a silly amount of memory.
         */
        fun decode(bytes: ByteArray): RealmSlot? {
            if (bytes.size != SLOT_BYTES) return null
            val version = readIntBe(bytes, 0)
            if (version != VaultFormat.FORMAT_VERSION) return null
            val memoryKiB = readIntBe(bytes, 4)
            val iterations = readIntBe(bytes, 8)
            val parallelism = readIntBe(bytes, 12)
            if (memoryKiB !in MIN_MEMORY_KIB..MAX_MEMORY_KIB) return null
            if (iterations !in 1..MAX_ITERATIONS) return null
            if (parallelism !in 1..KdfParams.MAX_PARALLELISM) return null
            return RealmSlot(version, bytes.copyOfRange(16, 16 + SALT_BYTES), memoryKiB, iterations, parallelism)
        }

        fun encode(slot: RealmSlot): ByteArray =
            ByteArray(SLOT_BYTES).also { out ->
                writeIntBe(slot.formatVersion, out, 0)
                writeIntBe(slot.memoryKiB, out, 4)
                writeIntBe(slot.iterations, out, 8)
                writeIntBe(slot.parallelism, out, 12)
                slot.salt.copyInto(out, 16)
            }

        private fun readIntBe(bytes: ByteArray, at: Int): Int =
            ((bytes[at].toInt() and 0xff) shl 24) or
                ((bytes[at + 1].toInt() and 0xff) shl 16) or
                ((bytes[at + 2].toInt() and 0xff) shl 8) or
                (bytes[at + 3].toInt() and 0xff)

        private fun writeIntBe(value: Int, into: ByteArray, at: Int) {
            into[at] = ((value shr 24) and 0xff).toByte()
            into[at + 1] = ((value shr 16) and 0xff).toByte()
            into[at + 2] = ((value shr 8) and 0xff).toByte()
            into[at + 3] = (value and 0xff).toByte()
        }
    }
}

/** The `vlt/kdf` file: exactly [RealmSlot.HEADER_BYTES] bytes, always, from the moment of creation. */
class RealmHeaderStore(private val layout: VaultLayout) {

    fun read(): ByteArray {
        val file = layout.kdfHeader
        if (!file.isFile) throw VaultNotInitialised(file)
        val bytes = file.readBytes()
        if (bytes.size != RealmSlot.HEADER_BYTES) throw VaultNotInitialised(file)
        return bytes
    }

    /** Creates a header of pure random filler, so the size is right before any realm exists. */
    fun initialiseIfAbsent(): ByteArray =
        if (layout.kdfHeader.isFile && layout.kdfHeader.length().toInt() == RealmSlot.HEADER_BYTES) {
            read()
        } else {
            writeWhole(Secrets.randomBytes(RealmSlot.HEADER_BYTES))
        }

    fun writeSlot(slotIndex: Int, slot: RealmSlot) {
        require(slotIndex in 0 until RealmSlot.SLOT_COUNT) { "slot $slotIndex" }
        val bytes = if (layout.kdfHeader.isFile && layout.kdfHeader.length().toInt() == RealmSlot.HEADER_BYTES) {
            read()
        } else {
            Secrets.randomBytes(RealmSlot.HEADER_BYTES)
        }
        RealmSlot.encode(slot).copyInto(bytes, slotIndex * RealmSlot.SLOT_BYTES)
        writeWhole(bytes)
    }

    /** Slots that parse as headers, paired with the index they occupy. Unused slots are usually absent. */
    fun usableSlots(): List<Pair<Int, RealmSlot>> {
        val bytes = read()
        return (0 until RealmSlot.SLOT_COUNT).mapNotNull { index ->
            RealmSlot.decode(
                bytes.copyOfRange(index * RealmSlot.SLOT_BYTES, (index + 1) * RealmSlot.SLOT_BYTES),
            )?.let { index to it }
        }
    }

    private fun writeWhole(bytes: ByteArray): ByteArray {
        val dir = layout.vaultRoot
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("could not create $dir")
        // A random hex name, not ".tmp": the write is torn only until the rename, and a half-written
        // header file that survives a crash must not be the one obviously-labelled artefact in the dir.
        val temp = File(dir, Hex.lower(Secrets.randomBytes(RealmSlot.SALT_BYTES)))
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(layout.kdfHeader)) throw IOException("could not commit vault header")
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
        return bytes
    }
}

class VaultNotInitialised(file: File) : IOException("$file is not a vault header")

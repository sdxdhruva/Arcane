package dev.arcane.vault.crypto

import java.security.SecureRandom

/** Key material handling. The JVM cannot lock or pin memory, so zeroing is best effort. */
object Secrets {

    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    fun nextInt(bound: Int): Int = random.nextInt(bound)

    /**
     * Overwrites a buffer that held key material. This narrows the window in which a heap dump or a
     * memory image can recover the bytes; it does not close it, because the GC may have copied the
     * array and the JVM may page it out. Key material is therefore never held in a String and never
     * logged.
     */
    fun wipe(buffer: ByteArray?) {
        if (buffer == null) return
        java.util.Arrays.fill(buffer, 0.toByte())
    }

    /** Overwrites the bytes a plaintext buffer exposed, before the buffer itself is wiped. */
    fun wipePlaintext(buffer: ByteArray?) = wipe(buffer)
}

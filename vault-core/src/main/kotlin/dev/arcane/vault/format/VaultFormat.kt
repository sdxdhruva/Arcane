package dev.arcane.vault.format

/**
 * Wire-level constants for the vault format. Changing any of these is a format break and must
 * bump [FORMAT_VERSION].
 *
 * Only APIs present on Android API 28 may be used from this package onward: no java.util.HexFormat,
 * no java.time, no NDK-backed primitives.
 */
object VaultFormat {
    const val FORMAT_VERSION: Int = 1

    /** Plaintext bytes carried by one chunk. A chunk is the unit of random access, so this is also
     *  the seek granularity of every viewer. */
    const val CHUNK_PAYLOAD_BYTES: Int = 512 * 1024

    /** GCM tag appended to every chunk blob. */
    const val GCM_TAG_BYTES: Int = 16

    const val NONCE_BYTES: Int = 12

    /** Chunk salt (4) + big-endian chunk index (8). */
    const val CHUNK_SALT_BYTES: Int = 4

    /** Tail padding granularity. Leaks a file's length only within this class boundary. */
    const val SIZE_CLASS_BYTES: Int = 32 * 1024

    const val BLOB_ID_BYTES: Int = 16

    const val FILE_ID_BYTES: Int = 16

    /** Number of bucket directories chunks are scattered across. */
    const val BUCKET_COUNT: Int = 256

    const val BLOB_SUFFIX: String = ".bin"
}

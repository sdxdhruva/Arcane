package dev.arcane.vault.crypto

/**
 * Known-answer vectors produced by foreign implementations, not by this code:
 * Argon2id from argon2-cffi (the C reference libargon2 binding) and HKDF/AES-256-GCM from
 * pyca/cryptography over OpenSSL. Regenerate with `python3 tools/kat_vectors.py`.
 *
 * If the AAD or nonce layout ever changes, these vectors fail and the change becomes deliberate
 * rather than accidental.
 */
object KatVectors {

    data class Argon2id(
        val secretHex: String,
        val saltHex: String,
        val timeCost: Int,
        val memoryKiB: Int,
        val parallelism: Int,
        val hashLen: Int,
        val tagHex: String,
    )

    val ARGON2ID = listOf(
        Argon2id(
            secretHex = "636f727265637420686f727365206261747465727920737461706c65",
            saltHex = "000102030405060708090a0b0c0d0e0f",
            timeCost = 3, memoryKiB = 16384, parallelism = 2, hashLen = 32,
            tagHex = "59515ce8536110257a0136abab1580908f32bdca7fb5f3e09a60e88ad346ea08",
        ),
        Argon2id(
            secretHex = "",
            saltHex = "00000000000000000000000000000000",
            timeCost = 2, memoryKiB = 1024, parallelism = 1, hashLen = 32,
            tagHex = "1c85b3888312748ae15f15fb29ab4a3a4e35126960fde87646df7a6f2ffbdf63",
        ),
        Argon2id(
            secretHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f",
            saltHex = "0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f",
            timeCost = 4, memoryKiB = 4096, parallelism = 4, hashLen = 32,
            tagHex = "fe19744af27847945c0aff3ccd6abe77f69dbc5d7297904c38adf146b12fe92d",
        ),
        Argon2id(
            secretHex = "617263616e65",
            saltHex = "73616c742d73616c742d73616c742121",
            timeCost = 1, memoryKiB = 256, parallelism = 1, hashLen = 16,
            tagHex = "f747139d01b41b378f08f8a4fd7f6190",
        ),
    )

    const val MASTER_KEY_HEX = "1111111111111111111111111111111111111111111111111111111111111111"

    val HKDF_FILE_KEYS = listOf(
        "00000000000000000000000000000000" to
            "9cc4bc0d7bc5c07c58c49d79f94b666fd6bc9a44d49b2e91f0fea345c8d325b0",
        "ffffffffffffffffffffffffffffffff" to
            "55a9e553f9f79c43604085cbf778a1b32af11fb6cdd14dadd4f38d9d93de0811",
        "9a8c3f7e2b1d0045ab6c7d8e9f012345" to
            "3117d4686580f9132441f7386513d8764fd16c5f67415526749f46b7cc5ea8f7",
    )

    const val FILE_ID_HEX = "9a8c3f7e2b1d0045ab6c7d8e9f012345"
    const val FILE_KEY_HEX = "3117d4686580f9132441f7386513d8764fd16c5f67415526749f46b7cc5ea8f7"
    const val CHUNK_SALT_HEX = "a1b2c3d4"

    data class Chunk(
        val chunkIndex: Long,
        val chunkCount: Long,
        val plaintextHex: String,
        val blobSha256Hex: String,
    )

    /** Payload pattern is `(i * 37 + chunkIndex) and 0xFF`, matching the generator. */
    val CHUNKS = listOf(
        Chunk(
            chunkIndex = 0, chunkCount = 3,
            plaintextHex = "00254a6f94b9de03284d7297bce1062b50759abfe4092e53789dc2e70c31567b" +
                "a0c5ea0f34597ea3c8ed12375c81a6cbf0153a5f84a9cef3183d6287acd1f61b",
            blobSha256Hex = "2b07b9453647085d8d12b66af66d340a8bcfabe130e3e126204ccfc9aca1b1e4",
        ),
        Chunk(
            chunkIndex = 1, chunkCount = 3,
            plaintextHex = "01264b7095badf04294e7398bde2072c51769bc0e50a2f54799ec3e80d32577c" +
                "a1c6eb10355a7fa4c9ee13385d82a7ccf1163b6085aacff4193e6388add2f71c",
            blobSha256Hex = "ba323bc466b8aa9c5f8bc6946b3f8d295f4678b09c906addb20af7c4f1380f9c",
        ),
        Chunk(
            chunkIndex = 2, chunkCount = 3,
            plaintextHex = "02274c7196bbe0052a4f7499bee3082d52",
            blobSha256Hex = "eb078298f5c39199b4b24661e2e28456b70b005d5f0857b10eb278f5c3d00112",
        ),
    )

    /** Full 32 KiB chunk, pinned by digest because the literal would bloat the source. */
    const val FULL_CLASS_PAYLOAD_BYTES = 32768
    const val FULL_CLASS_BLOB_SHA256 = "912ae141af75d0b2ed580e4d256ab00098a733255e0560eea402e9ed065f4e21"

    /** "payload-0" encrypted at index 0 of 3; every mutation of the inputs below must be rejected. */
    const val TAMPER_BLOB_HEX = "cb75051b5f3f6ca8aac303048b54456d7cab4ee476789baaf4"
    const val TAMPER_PLAINTEXT_HEX = "7061796c6f61642d30"

    const val AAD_INDEX0_COUNT3 = "9a8c3f7e2b1d0045ab6c7d8e9f012345000000000000000000000000000000030001"
    const val NONCE_INDEX0 = "a1b2c3d40000000000000000"
    const val NONCE_INDEX1 = "a1b2c3d40000000000000001"
}

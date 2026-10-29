#!/usr/bin/env python3
"""Generate known-answer vectors for Arcane's vault-core crypto from independent reference
implementations, so the Kotlin tests are checked against a foreign implementation rather than
against themselves.

Uses argon2-cffi (the C reference libargon2 binding) and pyca/cryptography (OpenSSL backend).
Re-run after any change to the AAD or nonce layout and paste the output into KatVectors.kt:

    python3 tools/kat_vectors.py

Arcane's construction, which these vectors pin down:
    FK            = HKDF-SHA256(MK, salt=0*32, info="arcane/file/" || fileId, L=32)
    nonce         = chunkSalt(4) || chunkIndex(8, big endian)
    AAD           = fileId(16) || chunkIndex(8 BE) || chunkCount(8 BE) || formatVersion(2 BE)
    blob bytes    = AES-256-GCM(FK, nonce, paddedPlaintext, AAD) || 16-byte tag
"""

import hashlib
import json
import os

from argon2 import Type
from argon2.low_level import hash_secret_raw
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes

FILEINFO_PREFIX = b"arcane/file/"
AAD_LEN = 16 + 8 + 8 + 2


def be(value: int, width: int) -> bytes:
    return value.to_bytes(width, "big")


def nonce_for(chunk_salt: bytes, chunk_index: int) -> bytes:
    assert len(chunk_salt) == 4
    return chunk_salt + be(chunk_index, 8)


def aad_for(file_id: bytes, chunk_index: int, chunk_count: int, version: int) -> bytes:
    blob = file_id + be(chunk_index, 8) + be(chunk_count, 8) + be(version, 2)
    assert len(blob) == AAD_LEN
    return blob


def main() -> None:
    vectors = {}

    vectors["argon2id"] = []
    for secret, salt, t, m, p, lanes in [
        (b"correct horse battery staple", bytes(range(16)), 3, 16384, 2, 32),
        (b"", bytes(16), 2, 1024, 1, 32),
        (bytes(range(64)), bytes.fromhex("0f" * 16), 4, 4096, 4, 32),
        (b"arcane", b"salt-salt-salt!!", 1, 256, 1, 16),
    ]:
        tag = hash_secret_raw(
            secret=secret,
            salt=salt,
            time_cost=t,
            memory_cost=m,
            parallelism=p,
            hash_len=lanes,
            type=Type.ID,
        )
        vectors["argon2id"].append(
            {
                "secretHex": secret.hex(),
                "saltHex": salt.hex(),
                "timeCost": t,
                "memoryKiB": m,
                "parallelism": p,
                "hashLen": lanes,
                "tagHex": tag.hex(),
            }
        )

    master_key = bytes.fromhex("11" * 32)
    hkdf_cases = []
    for label, file_id in [
        ("zeros", bytes(16)),
        ("ones", bytes.fromhex("ff" * 16)),
        ("mixed", bytes.fromhex("9a8c3f7e2b1d0045ab6c7d8e9f012345")),
    ]:
        derived = HKDF(
            algorithm=hashes.SHA256(),
            length=32,
            salt=bytes(32),
            info=FILEINFO_PREFIX + file_id,
        ).derive(master_key)
        hkdf_cases.append({"fileIdHex": file_id.hex(), "fileKeyHex": derived.hex(), "label": label})
    vectors["hkdfFileKey"] = {"masterKeyHex": master_key.hex(), "cases": hkdf_cases}

    file_key = bytes.fromhex(hkdf_cases[2]["fileKeyHex"])
    file_id = bytes.fromhex(hkdf_cases[2]["fileIdHex"])
    chunk_cases = []
    for index, count, payload_len in [(0, 3, 64), (1, 3, 64), (2, 3, 17), (0, 1, 32768)]:
        plaintext = bytes((i * 37 + index) & 0xFF for i in range(payload_len))
        salt = bytes.fromhex("a1b2c3d4")
        blob = AESGCM(file_key).encrypt(nonce_for(salt, index), plaintext, aad_for(file_id, index, count, 1))
        case = {
            "chunkIndex": index,
            "chunkCount": count,
            "chunkSaltHex": salt.hex(),
            "plaintextHex": plaintext.hex(),
            "blobHex": blob.hex(),
            "blobSha256Hex": hashlib.sha256(blob).hexdigest(),
            "nonceHex": nonce_for(salt, index).hex(),
            "aadHex": aad_for(file_id, index, count, 1).hex(),
        }
        # Keep the source-embedded vectors small: a full 32 KiB chunk is verified by digest only.
        if payload_len > 256:
            case["plaintextHex"] = case["plaintextHex"][:512] + "…"
            case["blobHex"] = case["blobHex"][:512] + "…"
            case["fullBlob"] = False
        else:
            case["fullBlob"] = True
        chunk_cases.append(case)
    vectors["chunkGcm"] = {"fileKeyHex": file_key.hex(), "fileIdHex": file_id.hex(), "cases": chunk_cases}

    # A chunk moved between files or reordered must fail: pin the tag for a wrong-index decrypt
    # attempt so the Kotlin side can prove it rejects rather than silently mis-decrypts.
    salt = bytes.fromhex("a1b2c3d4")
    blob = AESGCM(file_key).encrypt(nonce_for(salt, 0), b"payload-0", aad_for(file_id, 0, 3, 1))
    vectors["tamper"] = {
        "fileKeyHex": file_key.hex(),
        "fileIdHex": file_id.hex(),
        "blobHex": blob.hex(),
        "plaintextHex": b"payload-0".hex(),
        "chunkSaltHex": salt.hex(),
    }

    print(json.dumps(vectors, indent=2))


if __name__ == "__main__":
    main()

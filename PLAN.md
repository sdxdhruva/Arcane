# Arcane — Secure Android File Vault

Status: **built and host-verified; awaiting the phone**. Everything in §4-§6 is implemented, the crypto suite runs green here (see §7), and what is left is the on-device pass — the design items §5.1 defers and the instrumented unlock tests it cannot run without a screen. The entry flow and the failover were rebuilt once on your testing notes: the app now opens at the gate with no switch anywhere, and a refused-enough passphrase lands on plain browsing rather than on a second encrypted folder that looked like the first.

## 1. What gets built

An Android app with two surfaces that never appear at the same time:

- **Vault mode** — files stored as scattered encrypted chunks in the app's private area. There is no folder tree, filename, extension, or manifest on disk that says what anything is. The mapping from "random blob" back to "holiday.mp4" exists only inside an encrypted index that is itself stored as scattered chunks.
- **Plain browsing** — a normal, unencrypted browse of the phone's own media (MediaStore), with a tap-to-open-with-another-app path. Nothing is touched or encrypted here, and nothing here says a second surface exists.

The app opens at a **passphrase gate**, always. There is no switch, no tab strip, no tile and no wording that moves between the two surfaces: the only way into either one is the gate.

Coercion behaviour you specified: the correct passphrase opens the encrypted folder. A wrong passphrase shows one flat, generic refusal. **After a number of consecutive wrong attempts — drawn per install somewhere between two and five, so it cannot be learned by counting — the app stops refusing and puts up plain browsing instead**, with no error and no banner. So a forced owner can mistype a few times and land somewhere harmless, and an attacker sees nothing that distinguishes that from a file manager that only ever had one screen.

Why plain browsing and not a second encrypted "decoy realm": a decoy realm has to look exactly like the real vault to work, and once it looks like the vault it is no longer a safe answer to be caught with — it invites "then show me what *this* one holds". Plain browsing needs no key, no cached wrap, no seeded filler and no second passphrase field at setup, and it cannot be confessed to because it contains nothing. The one property worth keeping from the decoy design is that the failover must be *silent*, and it is.

## 2. Threat model — stated honestly

| Adversary / scenario | Verdict | Why |
|---|---|---|
| File pulled via `adb pull`, unrooted | **Defended** | Data lives in `/data/data/<pkg>/files`, not readable over adb; `allowBackup=false` + `dataExtractionRules` block adb/cloud backup of it |
| Anyone browsing shared storage, a file manager, "Downloads" | **Defended** | Nothing is ever written to shared storage |
| Forensic image of a **locked / powered-off** phone | **Defended** | OS-level FBE keys are gone at that point, and our payload is separately AES-256-GCM encrypted under a key that only exists after the passphrase is typed. Without it they have random bytes. |
| Rooted analyst reading the vault dir while locked | **Defended** | Same as above — no filenames, no manifest, no magic bytes in plaintext |
| **RAM dump while the vault is unlocked** | **Partially defended — cannot be fully solved** | Once unlocked, the master key *is* in process memory. No app-layer design can remove that; only hardware-backed streaming decryption could, and Android does not expose that for arbitrary app files. Mitigation is window minimisation: key held in a `ByteArray` that is explicitly zeroed (never a `String`, which the GC may copy), dropped on background/screen-off/timeout, never logged, `FLAG_SECURE` to stop screenshot and recents capture of plaintext. **This is the single biggest limit of the project — do not let the marketing copy claim otherwise.** |
| Someone forcing the owner to unlock | **Partially defended — this is the honest limit** | The 2-5 strike failover hands over plain browsing with no error, so the owner can comply harmlessly *once*. There is no decoy passphrase to hand over, so if the coercer then demands the real one the remaining defence is refusal, and nothing in the app can improve on that. What the design does remove is the tell: an examiner with the phone sees a media browser, not a vault with a lock on it. |
| A second passphrase as a cooperative answer | **Removed by design** | A decoy realm that looks like the vault is a worse answer than a surface that holds nothing; the setup screen asking for two passphrases was itself the advertisement this replaces |
| Size/filename/count metadata | **Partially defended** | Chunk size classes quantize lengths; blob names are random; but total capacity and blob count leak approximately, and ext4/FBE metadata is outside app control |
| "Secure delete" of the *original* file from shared storage | **Cannot be guaranteed unrooted** | SAF hands us a stream, not block control, and SSD/eMMC wear-levelling means an overwrite does not land on the same physical cells. We can guarantee unlink + MediaStore removal and best-effort overwrite where a raw FD is available. Documented in-app so no one is misled. |
| Play Store distribution + "see every file" | **Constrained** | `MANAGE_EXTERNAL_STORAGE` is not grantable for a normal Play listing. Plain browsing uses `READ_MEDIA_*` only, and imports use per-file SAF picks. Full-disk browse requires sideload + All Files Access, which the user grants manually. |
| Forgotten real passphrase | **Unrecoverable by design** | No server escrow, and the passphrase is the key material rather than a check against it. Setup says so on the screen that asks for it. Plain browsing needs no passphrase at all, so the failover path can never be the one left unreachable. |

## 3. On-disk layout

```
files/
  vlt/                                  <- neutral dir name, no "secret"/"vault" string
    kdf                                 <- salt + Argon2id params + format version (public, leaks nothing)
    <2 hex buckets 00..ff>/             <- ~256 dirs, chunk blobs spread across them
      0a1f9c2e...7d.bin                 <- one chunk: nonce derived, ciphertext, GCM tag
    5b3e88ac...f4.bin                 <- index generations, the anchor and the journal:
                                           the same name shape, the same size classes
  cache/vtmp/                           <- short-lived plaintext only where a viewer
                                           genuinely needs a seekable file (PDF)
```

Crypto per stored file:

- `MK_realm = Argon2id(passphrase, salt, m=64MiB, t=3, p=1)` — params calibrated once per device at setup so unlock lands near 500 ms, and the chosen params are written into `kdf`.
- `FK = HKDF-SHA256(MK, "arcane/file/" + fileId)` — one key per file, so no cross-file structure is possible.
- Chunk payload 512 KiB. `nonce = fileSalt(4) || chunkIndex(8 BE)` — unique because the key is per-file and chunk indices never repeat, so no per-chunk nonce needs storing.
- AAD binds `fileId || chunkIndex || totalChunks || formatVer`, and the plaintext is padded to a 32 KiB size class. Consequence: chunks cannot be moved between files or reordered without GCM rejecting them, and lengths leak only at 32 KiB granularity.
- Blob names are **derived, not stored**: `blobId = HKDF-SHA256(NK, "arcane/blob/" + fileId + chunkIndex)`, where `NK = HKDF(MK, "arcane/blob-name/" + realmId)`. A realm's whole chunk layout is therefore recomputable from `(fileId, size)` alone, which is what lets the index, the journal and the garbage collector name a file's chunks without carrying 16 bytes of bookkeeping per chunk — and lets an interrupted import be finished or undone from its promise alone. The index anchor and the journal are located the same way (`"arcane/anchor-name/"`, `"arcane/journal-name/"`), so no pointer to either exists on disk.
- Index = CBOR list of `(fileId, name, mime, size, chunkSalt, timestamps, folder)`, itself encrypted, chunked and scattered like any other file under `HKDF(MK, "index")`. Without the key the directory is a pile of same-shaped random blobs.
- `MK` is **never stored, wrapped or cached**. The passphrase is the only input that derives it, on every unlock, in every session; the phone's PIN, its fingerprint and its secure element play no part. There is no quick-unlock shortcut, so a lost passphrase is a lost vault — and a phone that has been unlocked once cannot be reopened by whoever holds it now.

Deleting a file: zero the index entry, overwrite each chunk blob with random bytes and `fsync`, unlink, rewrite the index.

Durability during import is settled at the **commit boundary, not per chunk**. Chunks are written as they are produced, a single `fsync` runs across the batch, and only then is the index entry committed. Doing this per chunk instead would cost 2048 fsyncs at 5-20 ms each on a 1 GiB import — 10-40 s of pure durability tax for a guarantee that needs paying once.

Multi-gigabyte transfers cross process death through a **write-ahead journal**: one size-classed envelope per realm, holding the promises that have not been kept yet — an import with its `fileId`, chunk salt, size, name, folder and how many chunks landed, or a delete with the same identity. The order is always promise first, settle last, with the index commit somewhere between them, so a crash at any point lands on a state `reconcile()` can read: an import whose chunks all landed is re-listed rather than thrown away, a partial one is offered for resume, and a promised delete is finished. Every blob it touches is named from this realm's key, which is why a sweep in one realm can never strand or delete another realm's data: a blob belonging to a key not in hand is simply unnameable. (The format carries slots for more than one realm and the core is tested that way; the app uses one.) Progress is journalled every 64 chunks (32 MiB), not every chunk, because each tick is itself a full envelope write.

No NDK anywhere — BouncyCastle's lightweight `Argon2BytesGenerator` is pure Java and does not need a JCE provider registration. That matters here: this machine has no NDK installed, and it also gives a single architecture-neutral APK.

### 3.1 Scale — gigabyte files are the expected case, not the edge case

1 GiB = exactly 2048 chunks. Overhead is the 16-byte tag on each (32 KiB) plus at most one 32 KiB padding class on the tail, so ~0.006% on a 1 GiB file; a 4 GiB file is 8192 chunks, and a 4 GB decimal file is 7630. `chunkIndex` is 8 bytes wide, so the format has no per-file ceiling. Index cost is per *file*, not per chunk — one fixed record of roughly 80 bytes — because chunk positions are derived, so a 4 GiB video and a 4 KiB note cost the index the same.

- **Import is streaming** — read a chunk from SAF, encrypt, write, forget. Peak extra disk is the ciphertext; there is never a plaintext temp copy of a large file.
- **1 GiB round trip, measured on this host** (`LargeFileRoundTripTest`, run with `-Parcane.scale=on --rerun-tasks`; x86-64, NVMe, JDK 21 — not the phone): import **20.9 s (48 MiB/s)**, full decrypt-and-verify **6.0 s (169 MiB/s)**, 32 random 4096-byte seeks **avg <1 ms, worst 3 ms**, 2048 chunks, ciphertext exactly plaintext + 32 KiB of tags, and 65 KiB for index + header slots + journal — none of it per chunk. Import is I/O-bound as predicted; decryption runs ~3.5x faster than import because it reads back what the page cache already holds. A phone's flash is slower than NVMe, so treat the import figure as the optimistic end. With ARMv8 Crypto Extensions a 512 KiB GCM decrypt is under 1 ms, so 2048 of them is ~1 s of CPU that overlaps the I/O, and SHA-256 for verify-before-delete rides the same streaming passes.
- **Seek costs ~1-3 ms measured** — the earlier 5-15 ms estimate was the pessimistic end — one chunk read plus one chunk decrypt, and prefetch hides it. Playing back 10 MB/s video means decrypting 20 chunks a second, roughly 1% CPU. Large-file scrubbing is indistinguishable from an unencrypted player.
- **The ceiling is free space in `/data`.** App-private storage is internal flash, so the vault cannot extend onto a microSD card; a 4 GB vault needs 4 GB free internally. This is the number that actually limits a user's library.
- Whole index is decrypted into memory at unlock. Clean up to the low hundreds of thousands of files; beyond that the index needs segmented lazy-loading, which is a documented future knob rather than a v1 surprise.

### 3.2 What the unlocked vault looks like

Ordinary. Folders, breadcrumbs, real filenames and extensions, sizes, thumbnails, grid or list, sort, search, multi-select, drag between folders — the entire tree is available the moment the realm unlocks, with no per-folder reveal step, because names and paths live in the encrypted index.

Two consequences of that design:

- The hierarchy is **virtual**. On disk there are no directories and no names, only flat random blobs; `/Photos/2026/private.jpg` exists solely inside the encrypted index, so folder structure never reaches the filesystem.
- Content keys derive from `fileId`, and names live only in the index, so **renaming or moving a file is an index-only write — no re-encryption**. Renaming a 4 GiB video is instant.

The honest flip side: unlocked means plaintext names and contents are on screen. `FLAG_SECURE` blocks screenshots and recents thumbnails while a realm is open, and the lock button wipes the key, but a shoulder-surfer with an open vault sees everything. That is the case the failover is for: the screen a bystander is left looking at is the one with nothing in it.

## 4. Modules

| Module | Kind | Contents |
|---|---|---|
| `vault-core` | **plain Kotlin JVM** | chunking, AEAD, key derivation, blob naming/spreading, index format + journal, size classes, realm logic, secure wipe. Zero Android dependencies so every crypto test runs in milliseconds on the host JVM. |
| `platform-android` | Android lib | real file layout, temp plaintext cache, lifecycle key-zeroing, session clock, ringer borrow |
| `media` | Android lib | `ArcaneDataSource` + `DataSource.Factory` for Media3, chunk LRU cache, thumbnail extraction, playback position mapping |
| `app` | Android app | Compose UI, gate + failover, plain browser, vault browser, all viewers |

The `vault-core` split is deliberate: the promise "an analyst cannot tell what these files are" is testable as pure-JVM code, which is where it should be tested.

## 5. Playback and viewing

Seekable video over an encrypted, chunked, scattered file is the hardest UI-level piece, and it decides the whole storage format — which is why chunks are independently encrypted.

- `ArcaneDataSource.open(DataSpec)` maps `position -> chunkIndex = pos / 524288`, `offset = pos % 524288`, decrypts that one chunk, and streams from the offset. Sequential playback decrypts one 512 KiB chunk every ~2 s of 1080p video — a few ms of AEAD work per chunk on any phone with ARMv8 Crypto Extensions. Two-entry LRU holds the current chunk plus prefetch of the next, so a scrub lands instantly and backward nudges are free.
- ExoPlayer gets a real `ProgressiveMediaSource` via `DefaultMediaSourceFactory(arcaneFactory)` with a fake `arcane://<fileId>` URI, so ordinary MP4/MKV/WebM demuxing (including fast-start `moov` at the tail) works unchanged. No localhost HTTP server, no plaintext on disk, nothing listening on a socket.
- Player features (MX Player's gesture grammar, because muscle memory is a feature): one gesture loop arbitrates the axis before it commits — horizontal anywhere scrubs the timeline, the left half of the screen drags brightness, the right half drags media volume, and nothing is consumed until the drag passes `touchSlop`, so taps still reach the chrome. Double-tap left/right skips ±10 s, a single tap shows or hides the deck, and the deck carries prev/next within the folder, speed, resize mode (fit/fill/stretch) and a screen lock that leaves only an unlock control on screen while it is engaged. Brightness and volume are restored when the viewer unmounts. Scrub-preview thumbnails, subtitle sidecars loaded as encrypted files, audio-track and track-selection, and PiP remain out of scope; background audio through a MediaSession is deliberately absent, because a player that keeps decrypting after the screen closes keeps the realm alive after it should have locked.
- Thumbnails: extracted with **our own ExoPlayer instance over the same DataSource**, not `MediaMetadataRetriever` — the retriever needs a seekable FD and would force a plaintext temp file.
- Images: `ImageDecoder` with pinch-zoom/pan, HEIF/WebP/animated GIF, EXIF hidden by default and stripped on export. A left/right swipe turns to the next or previous same-kind item in the folder — the sibling list is the already-decrypted listing, so paging never re-reads a tree — and it gives way the moment a second finger lands or the picture is zoomed, because a zoomed image is being moved rather than turned. The ends do not wrap, and the same prev/next buttons exist for anyone who cannot swipe.
- Text/code: streamed decryption with a lazily built line index, so a 500 MB log opens without loading it; search, wrap, monospace.
- HTML: WebView sandboxed — JavaScript **off** by default, `allowFileAccess=false`, remote resource loads blocked, with an explicit per-file "this one I trust, enable JS" toggle. A vault that auto-executes JS from an untrusted `.html` is an exploit chain, not a viewer.
- PDF: `PdfRenderer` (API 31+, feature-gated) needs a seekable FD, so PDF is the one format that materialises into `cache/vtmp` — mode 0600, zeroized on close, swept on every lock and process start. This is a conscious, bounded plaintext-on-disk exception and it is per-file opt-out in settings. It is also **size-gated**: a 500 MB PDF would otherwise write 500 MB of plaintext, so above a configurable threshold (default 100 MB) the viewer refuses and offers export instead of silently spilling. The same gate applies to ZIP "extract all", which is the other path that can turn one encrypted file into many plaintext ones.
- ZIP list/extract, audio via the same player stack. Anything else opens in a hex/preview pane; "open externally" requires a confirm dialog stating that plaintext leaves the vault.

### 5.1 Design language — premium, not stock Material

The app must not read as a template. A vault that looks like a boilerplate Material demo undermines the one thing it is selling, so the visual layer is a deliverable in its own right, held to the standard of a well-funded consumer product.

- **Identity.** Dark-first, single accent (violet on near-black `#0B0E13`), elevation expressed through tonal surface tints rather than drop shadows, one corner-radius scale, one 4pt spacing rhythm. No stock snackbars, no default FAB, no unthemed dialogs.
- **Type.** Bundle an OFL-licensed variable face (Inter or Manrope) in `res/font`. Downloadable fonts are out: the `INTERNET` permission this app now declares is for the user-asked-for download-into-the-vault path and nothing may reach for a server on its own — no font fetch, no telemetry, no remote content. Tighten letter-spacing at display sizes, keep body at 15–16sp.
- **Motion.** Spring-based, 250–350ms, with Material 3 Expressive shared-element transitions from a file tile into its viewer, so opening a video feels like the tile growing rather than a screen change. Predictive back throughout. `Settings.Global` reduced-motion respected — motion is an enhancement, never a requirement.
- **Custom components over library defaults.** A filmstrip scrubber with frame-accurate preview and haptic ticks at chapter boundaries, folder tiles with tinted cover previews, skeleton shimmer instead of spinner soup, empty states with real illustration and a single clear action, long-press context sheets with springy detents, a pull-down header that collapses into a breadcrumb.
- **Detail work that separates premium from adequate.** Blur behind control overlays (`RenderEffect`, API 31+, tinted-scrim fallback below), HDR10+ and wide-gamut passthrough in the player, per-format glyphs drawn rather than emoji, icon set from Material Symbols Rounded subset bundled as vectors, no layout jump on any state change, and no jank while decrypting — decode and inflate off the main thread.
- **Accessibility is part of the premium bar, not a checklist afterthought.** TalkBack labels on every control, 48dp minimum targets, font scale to 2.0 without clipping, AA contrast everywhere and AAA on the unlock field, and every gesture reachable by a button for anyone who cannot perform it.
- **Where design meets the threat model.** Screenshots are blocked only while a realm is open — a flag set on a screen that holds no secret is itself the tell, and it would put a black recents card on an app that wants to look like a media browser. The gate carries no affordance toward anything: no tab, no padlock tile, no second field, and no tagline that hints at what is behind it — the wordmark says only "Locked to this phone.", which is true of the key and reads like any passphrase app. And the plain surface says no word that points at the other one: neither "vault", "encrypted", "hidden" nor "secure" appears on it, in its empty states, in its notices, or in its accessibility labels.
- **Thumbnails are vault content too.** Preview images are generated at import, encrypted as ordinary chunks, and decrypted on demand into an in-memory LRU. Never a plaintext thumbnail cache on disk — that would leak the entire library through a folder of `.jpg` files, which is exactly the leak this app exists to prevent. The scrubber filmstrip is the same machinery asked to decrypt several frames at once.

Design tokens live in one place (`app/ui/theme`) so color, type, spacing and motion are tuned as a system rather than per-screen.

**What shipped, and what waits for the phone.** Built and verified here: the token set itself (single violet accent on near-black `#0B0E13`, tonal elevation instead of drop shadows, one radius scale, one spacing rhythm), the custom control set — action buttons, chips that carry a `selected` state to TalkBack, transfer meters, prompt and choice sheets with back handling, empty states with an illustration and exactly one action, skeleton shimmer — plus the tile and row sets, breadcrumb trail, header, selection bar and the browser's grid/list switch. The theme is deliberately **dark-only**: a light Material scheme next to the hardcoded dark palette produced unreadable contrast, so `ArcaneTheme` always hands out the dark scheme and `MainActivity` pins both system bars to `SystemBarStyle.dark` instead of leaving them on `auto`.

Four items are held back rather than half-done, each because it cannot be judged without the screen in hand — they belong to the adb phase, not to a rebuild of this host:

- **Bundled OFL variable font.** The type scale is real and applied consistently through system `SansSerif`/`Serif`/`Monospace`; swapping the family is a taste decision that has to be made while looking at it on a phone, and it is one file in `res/font` plus one `fontFamily` line.
- **`RenderEffect` blur behind control overlays.** Needs a graphics layer; today those overlays use the documented tinted-scrim fallback, which is what ships below API 31 anyway.
- **M3 Expressive shared-element tile→viewer transition.** The fade-and-rise gate and the viewer's own entry are in place; the morph is the part that shows jank or it does not, and jank is only visible on device.
- **Filmstrip frame previews on the scrubber, and the collapsing pull-down header.** Both are decrypt-heavy or gesture-heavy, so they get built where they can be measured.

Reduced motion is honoured everywhere through `ArcaneMotion.durationScale()`, and every gesture-driven control has a button equivalent.

## 6. Lifecycle and hardening

Key is wiped the moment the app leaves the foreground — `onStop` arms a **zero-length** grace, because a vault that is not on screen is a vault in someone else's hands — and after 90 s of no interaction. A rotation costs nothing: `MainActivity` declares its own `configChanges`, so the activity never stops for one. Also locks on the manual button and `onTaskRemoved`; resuming re-prompts with the passphrase field. Typing the passphrase every session is the point, not a cost to be optimised away.

The only door is the passphrase. There is no biometric quick-unlock and no Keystore-wrapped key to open the vault behind a fingerprint: the phone's own PIN and biometric are not this vault's secret, so the app never asks them to vouch for anyone, and a session that ends has to be rebuilt from Argon2id and the typed passphrase.

The one exemption is a **handoff**. A file pick or an export destination is the app handing its own screen to something it asked for, which is not the user leaving; without the exemption the instant background lock fires inside the phone's picker and every import ends as a gate the user has to pass again with a pick they have to redo. So a for-result launch is bracketed (`beginHandoff` before `launch`, `endHandoff` as the first act of the callback), nested counts are supported (a pick whose result starts an export), and while one is outstanding the background lock waits for a **120 s ceiling** instead of firing at once — a ceiling, not a permission: an abandoned picker still locks, and a handoff that ends off-screen does not buy back the idle window. `vault-core`'s `SessionGuardTest` drives all of it on a clock the test owns.

Two things follow the realm rather than a screen: `FLAG_SECURE` (§5) and the **ringer**. While a folder is open the phone is put on vibrate and handed back to whatever mode it was in when the lock runs, because a call on speaker over an open folder is a louder tell than anything drawn on it. Android only moves the ringer for an app the owner has granted Do-Not-Disturb access to, so `ACCESS_NOTIFICATION_POLICY` is declared but not assumed: the vault menu row states which of the three states it is in (off / on / needs permission) and a tap on the last one opens the system page rather than promising silence it cannot deliver. The previous mode is written down before it is changed, and restored on the next start only if the ringer is still where this app left it — so a process kill mid-viewing cannot leave the phone on vibrate with nothing remembering why, and a mode the owner chose themselves is not quietly overruled.

Both system bars are hidden app-wide (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, re-asserted on every window-focus change because the picker and the permission sheet reset them). The status bar is the reason: a notification headline slides across the top of an open folder and no in-app screen can retract it afterwards. The phone's own gestures keep working, a swipe brings the bars back transiently, and the same full-bleed frame is what lets a video fill the glass.

R8 full mode, no debuggable release, no logcat of paths or keys, `FLAG_SECURE` while a realm is open, cookie and WebView network access disabled, optional refusal to unlock when a debugger is attached or a `TracerPid` is set (configurable, because these heuristics false-positive).

Plain browsing is the surface a coercer is left on, so it is deliberately **not** `FLAG_SECURE`-protected and carries no lock button of its own: back returns to the gate, which is how the owner gets their passphrase back, and a screen that refuses screenshots is a screen that gets asked about. The manual lock lives in the vault browser's overflow sheet, where it can only be seen by someone who already has the passphrase.

## 7. Verification

Phase 1, here, no device:

- `vault-core` unit tests: KDF known-answer vectors against RFC 9106 parameters; round-trip; chunk boundaries (0 B, 1 B, exactly 512 KiB, 512 KiB+1, size-class multiples); nonce-uniqueness sweep; tamper tests proving a spliced or reordered chunk is rejected; truncation detection; index round-trip; **crash-injection** that kills the writer at every byte offset of a journal commit and asserts recovery.
- **The headline test — plaintext-bleed scan.** Import a fixture containing a unique magic header and an embedded marker string; then walk *every* byte of the produced data directory asserting the marker and magic appear nowhere; repeat after previewing, after full video playback, and after export; assert blob filenames have zero correlation with logical names and that both realm dirs are statistically indistinguishable. This is the direct, repeatable evidence for the core claim, and I will show its output, not just assert it passed.
- Seek-math property tests: random offsets must yield exactly `plaintext[offset..]`; cache eviction under interleaved seeks; concurrent `DataSource` instances reading the same file.
- Large-file tests: a 1 GiB round-trip asserting chunk count, ciphertext overhead and index size land inside the bounds in §3.1, plus wall-clock for import and full-decrypt on the host JVM so the numbers I report are measured rather than estimated. A kill-at-random-chunk crash test proving the commit-boundary rule leaves either a complete file or collectable orphans, never a partially-registered one.
- Compose UI tests for unlock: correct→realm, wrong→the one generic refusal, strike-N→plain browsing with no error surfaced, and no vault vocabulary anywhere in the plain surface's semantics tree; import-then-delete-original with a stub SAF provider.
- `assembleRelease` green with R8; `apkanalyzer` sanity that no vault path leaks and backup rules are compiled in.

**Status after the build pass:** **109 vault-core tests, 0 failures, 1 skipped** (`./gradlew :vault-core:test`) — the skipped one is the opt-in 1 GiB round trip (`-Parcane.scale=on --rerun-tasks`) and its measured numbers are in §3.1. The plaintext-bleed scan and crash-injection are among the passing 108. `:app:assembleDebug` and the instrumented test sources compile clean with no warnings. `:app:assembleRelease` is green through R8 full mode with resource shrinking and `lintVital` — **5.3 MB, 342 entries, unsigned** — and its merged manifest answers the threat-model lines that are build-time checkable: `allowBackup="false"` with both `dataExtractionRules` and `fullBackupContent` compiled in, and a declared permission set of `READ_MEDIA_*`, `ACCESS_NOTIFICATION_POLICY` plus **`INTERNET`, which is pull-only and exists for one feature** — streaming a user-typed http/https link straight into the vault. Nothing uploads, the WebView blocks remote subresources in code, `network_security_config` restricts trust anchors to the system store, and every other byte the app writes goes to app-private storage.

Still open in Phase 1, and open *because* of the missing device:

- Compose UI tests for unlock (correct→realm, wrong→the generic refusal, strike-N→plain browsing, import-then-delete-original). The decision they assert is covered at the core level — `FailoverPolicy` and `RealmUnlockTest` prove the 2-5 strike rule and that a realm is byte-indistinguishable from its neighbours — but the "no seam on screen" half needs a running UI. The app has no unit-test source set and no Robolectric, so these land as instrumented tests; `ArcaneDesignSystemTest` is the first one and has never actually executed.
- **R8 kept the vault readable rather than merely building.** The release build's own crypto tests still have to run — minified Argon2/GCM/index code is a different artifact from tested code. The mapping shows all 12 generated `$$serializer` classes surviving, obfuscated to short names with their `INSTANCE` members remapped, which is the shape that works only if the serialization plugin's keep rules did their job; the way to know is to unlock a realm on a phone with this APK. If it fails it will fail as an authentication error on the first index read, and the fix is `-keep` for the BouncyCastle and CBOR paths — a five-minute change.

Phase 2, your phone over adb — when you connect it: install the signed release-equivalent debug APK, run the instrumented suite for real, and walk each viewer by hand, then a fix-and-improve pass. If something needs a device earlier (AEAD throughput numbers, an emulator-undetectable bug), I'll say so at that moment rather than guessing around it.

## 8. Build environment fit

Measured on this machine, not assumed: `~/Android/Sdk` has `platforms/android-36` plus the `android-37.*` platforms that `compileSdk = 37` resolves against, `build-tools/36.0.0`, and accepted licenses — **no NDK and no cmdline-tools**; Gradle **9.6.1 is already in the wrapper dist cache**; JDK 21; system Gradle 4.4.1 is too old to use, so the wrapper path is required; Google Maven and Maven Central both reachable; no device attached.

Therefore: Gradle wrapper 9.6.1 (cached, no download), AGP 9.4.1 (built against `gradle-api` 9.4.1, so the cached distribution satisfies it), Kotlin 2.4.21 with the bundled Compose compiler plugin, Compose BOM 2026.09.00, Media3 1.11.1, BouncyCastle `bcprov-jdk18on` 1.86, kotlinx-serialization CBOR, Coil 3, DataStore. `compileSdk` 37 — the platform AGP 9.4.1 builds against — with `targetSdk` 36 and `minSdk` 28. The pure-JVM crypto choice removes the missing NDK as a blocker outright. The M0 contingency (drop to AGP 8.13.x) was never needed; the first build was green.

Contingency: if AGP 9.4.1 and Kotlin 2.4.21 disagree in the first build, drop to AGP 8.13.x and download wrapper 8.13 (~130 MB, network allows it) — a M0 step, not a surprise later. That fallback stayed unused.

## 9. Milestones

M0-M7 are done on this host. The two gates that need hardware are marked as such rather than quietly reworded.

| | Deliverable | Gate | Here |
|---|---|---|---|
| M0 | Scaffold, modules, version matrix, `assembleDebug` green on this host | build succeeds | yes |
| M1 | `vault-core`: format, AEAD, KDF, index+journal, wipe | all crypto tests + plaintext-bleed scan pass | yes — 108 tests green |
| M2 | Passphrase-only unlock, the realm, 2-5 strike failover to plain browsing, one-passphrase setup | failover UI tests pass | logic yes (`FailoverPolicy`, `RealmUnlockTest`); the UI half needs the device |
| M3 | Storage layout, import pipeline with verify-before-delete, secure delete, reconcile/repair | round-trip on real files | yes, on generated streams incl. 1 GiB; SAF itself needs the device |
| M4 | Simple-mode browser (MediaStore + SAF) | browse + import works | built; MediaStore cursors need the device |
| M5 | Video/image/text/HTML/PDF/ZIP viewers, `ArcaneDataSource` | seek math tests pass | yes — math green, seek measured at <1 ms avg |
| M6 | Vault browser, folders, search, sorting, thumbnails, multi-select | usable UI end to end | built; walkthrough needs the device |
| M7 | Lifecycle zeroing, `FLAG_SECURE`, backup/restore portable archive, settings, hardening | threat-model table re-checked line by line | built; `FLAG_SECURE` and screen-off are device observations |
| M8 | **Your device on adb**: install, instrumented suite, manual walkthrough, fix-and-improve | you sign off | next |
| M9 | Signed release, R8 mapping retained locally, store-prep notes on the permission limits | APK handed over | unsigned R8 release builds (5.3 MB); needs your keystore to sign |

## 10. Open calls, deferred to you in the moment

1. Vault entry point — **settled**: the app opens at the passphrase gate, always, and nothing else is drawn there. Plain browsing is reached only by the failover, and the vault only by the passphrase.
2. Package id `com.arcane.vault` vs something unremarkable — a Play listing or `pm list packages` still spells the name, so this only matters if camouflage ever gets added.
3. Chunk size 512 KiB is the default trade (seek latency vs. blob count). 1 MiB halves blob count and doubles seek decrypt; tunable per realm in the format header.
4. Whether "open externally" exists at all in v1, since it is the one path that knowingly writes plaintext to shared storage.

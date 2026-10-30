# Arcane

Arcane is a privacy-focused Android app for keeping personal files and media in a passphrase-protected vault. It is designed around the idea that sensitive data should not be stored in obvious file names, visible folders, or plaintext caches on disk.

The project combines a secure vault layer with a normal media browser experience, giving users a passphrase gate and a failover path that can appear like a harmless everyday gallery instead of an obvious encrypted container.

## Why this project exists

Modern phones make it easy to store a library of personal photos, videos, notes, and documents. But those files are often left in plain view, exposed to backups, file managers, or device-level forensic recovery.

Arcane tries to address that by:

- storing vault contents as encrypted chunks rather than plaintext files
- deriving the vault key from a passphrase instead of a device-bound secret alone
- keeping the vault index encrypted and not trivially discoverable from the filesystem
- allowing ordinary media playback and browsing only after the user unlocks the vault
- providing a silent plain-browsing failover rather than a second obvious "decoy vault"

## Key features

- Passphrase-gated vault access
- Chunked encrypted file storage
- Encrypted index and metadata handling
- Media browsing and playback for images, video, and audio
- Web content download flow that re-encrypts into the vault
- Android Compose-based UI
- Modular architecture with separate vault, media, and platform layers

## Architecture

The repository is split into a few focused modules:

- `app` — Android application UI and app wiring
- `vault-core` — core encrypted vault logic, key derivation, index handling, and file layout
- `media` — Media3/data-source integration for playing vault-backed files
- `platform-android` — Android-specific platform helpers and session behavior

This separation keeps the cryptographic core testable as a Kotlin JVM module while leaving the app layer in Android-specific code.

## Security model (overview)

Arcane is built around a practical threat model rather than marketing claims:

- the vault key is derived from a user passphrase
- file chunks are encrypted and distributed with derived blob names
- metadata is handled through an encrypted index instead of plaintext file names and directory layouts
- the app avoids writing obvious plaintext copies into ordinary shared storage
- the app is designed to minimize the visible “vault” footprint and avoid a second obvious locked surface

Important note: no app-layer design can fully protect data against a rooted device, an unlocked live memory dump, or a malicious OS-level compromise. This project is best understood as a strong privacy-focused design with practical limits, not a guarantee against all forensic or device-level attacks.

## Project structure

```text
Arcane/
├── app/                 # Android app
├── media/               # Media playback and vault-backed content access
├── platform-android/    # Android-specific utilities
├── vault-core/          # Core encrypted vault logic
├── gradle/              # Gradle wrapper and version catalog
├── build.gradle.kts     # root Gradle config
├── settings.gradle.kts  # project modules
├── PLAN.md              # design and implementation notes
├── gradlew              # Gradle wrapper
├── gradlew.bat          # Windows wrapper
├── .gitignore
├── local.properties     # local Android SDK config
└── README.md            # this file
```

## Getting started

### Prerequisites

- Android Studio or IntelliJ IDEA with Android tooling
- JDK 17
- Android SDK installed
- Gradle wrapper included in the repo

### Build the project

From the repository root:

```bash
./gradlew :vault-core:test
./gradlew :app:assembleDebug
```

For a release build:

```bash
./gradlew :app:assembleRelease
```

If you want to sign a release build locally, update the signing configuration in `app/build.gradle.kts` to use your own keystore rather than the local development one.

## Running locally

1. Open the project in Android Studio.
2. Sync Gradle.
3. Select the `app` module and run it on an Android device or emulator.
4. Use the passphrase gate to unlock a vault session.

## Development notes

- The app uses Kotlin and Jetpack Compose.
- The vault logic is intentionally separated from Android-specific code so it can be tested independently.
- The repository includes detailed design notes in `PLAN.md` if you want more context on the threat model and implementation decisions.

## Contributing

Contributions are welcome. If you want to help:

1. Open an issue to discuss a feature or bug.
2. Fork the repo and create a branch for your change.
3. Keep changes focused and well-documented.
4. Validate with the relevant Gradle tasks before submitting a PR.

## License

This repository does not currently include a license file. Before publishing or redistributing the project publicly, add an appropriate open-source license such as MIT, Apache 2.0, or GPL, and update this section to match it.

## Status

Arcane is a functional Android app project with a privacy-first vault architecture and a structured modular implementation. The repository is intended for local development, experimentation, and further hardening before broader distribution.

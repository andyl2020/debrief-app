# Debrief

Debrief is a local-first Android app for reviewing long field recordings. It links to a folder on the phone, transcribes recordings with speaker labels and word timestamps, and provides synced playback, full-text search, timestamped comments, speaker renaming, Markdown export, and reinstall-safe JSON sidecars.

## What works

- Persistent Android Storage Access Framework folder permission with automatic rescans
- MP3, M4A, WAV, and AAC library with new/queued/transcribing/ready/failed states
- WorkManager transcription queue with unmetered Wi-Fi by default and automatic retry
- On-device mono AAC upload preparation at 64 kbps
- Deepgram Nova-3 batch transcription with diarization, utterances, punctuation, keyterms, and word timestamps
- Pluggable AssemblyAI fallback using upload, submit, and polling APIs
- Media3 playback, saved position, transcript follow/highlight, tap-to-seek, and Sync
- SQLCipher-encrypted Room database and bundled FTS5 search across transcripts and comments
- Timestamped comment create/edit/delete and recording-wide speaker aliases
- Markdown share-sheet export
- JSON sidecars next to each recording, restored after reinstall

## Privacy and secrets

API keys are entered in Settings and encrypted with a non-exportable Android Keystore AES-GCM key. They are excluded from Android backup and never written to source, Gradle properties, logs, sidecars, or APK resources. The Room/FTS database is encrypted at rest with SQLCipher using a random passphrase protected by the same Keystore mechanism.

The app does not provide cloud storage. Audio is prepared in the app cache, sent directly to the selected transcription provider over HTTPS, and deleted from the cache after the request completes. Original recordings and all durable app data remain on the phone.

## Build

Requirements: JDK 17 and Android SDK 35.

```text
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
```

Release signing is supplied only through process environment variables:

```text
DEBRIEF_KEYSTORE_PATH
DEBRIEF_KEYSTORE_PASSWORD
DEBRIEF_KEY_ALIAS
```

Signing material and local tooling are ignored by Git.

## Install

Download the latest APK from GitHub Releases, allow your browser to install unknown apps if Android asks, and open the downloaded APK. On first launch, link the folder containing recordings and save the Deepgram key in Settings.

## Scope

Android 10 or newer. Recording, video, live transcription, cloud sync, collaboration, and iOS are intentionally out of scope for v1.

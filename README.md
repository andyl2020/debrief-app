# Debrief

Debrief is a local-first Android app for reviewing long field recordings. It links to a folder on the phone, transcribes recordings with speaker labels and word timestamps, and provides synced playback, AI-assisted conversation chapters, full-text search, timestamped comments, speaker naming, Markdown export, and reinstall-safe JSON sidecars.

## What works

- Persistent Android Storage Access Framework folder permission with automatic rescans
- MP3, M4A, WAV, and AAC library with new/queued/transcribing/ready/failed states
- WorkManager transcription queue with unmetered Wi-Fi by default and automatic retry
- Long-press multi-select with explicit checkbox-based batch transcription
- On-device mono AAC upload preparation at 64 kbps
- Deepgram Nova-3 batch transcription with diarization, utterances, punctuation, keyterms, and word timestamps
- Pluggable AssemblyAI fallback using upload, submit, and polling APIs
- Per-key local usage tracking plus Deepgram provider usage, spend, and balance when the key has read scopes
- Gemini, OpenAI-compatible, or Claude post-transcription AI pass for conversation sets, summaries, speaker suggestions, and intelligent physical rename with undo
- Per-recording AI privacy skip, automatic/re-run controls, configurable silence gap, manual set merge/split, and local per-key AI usage
- Media3 playback, saved position, transcript follow/highlight, tap-to-seek, and transcript reload
- SQLCipher-encrypted Room database and bundled FTS5 search across transcripts, summaries, and comments
- Timestamped comment create/edit/delete and recording-wide speaker aliases
- Markdown share-sheet export
- JSON sidecars next to each recording, restored after reinstall

## Privacy and secrets

API keys are entered in Settings and encrypted with a non-exportable Android Keystore AES-GCM key. They are excluded from Android backup and never written to source, Gradle properties, logs, sidecars, or APK resources. The Room/FTS database is encrypted at rest with SQLCipher using a random passphrase protected by the same Keystore mechanism.

The app does not provide cloud storage. Audio is prepared in the app cache, sent directly to the selected transcription provider over HTTPS, and deleted from the cache after the request completes. The optional AI pass sends transcript text, never audio, to the AI provider selected in Settings. Original recordings and all durable app data remain on the phone.

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

Download the latest APK from GitHub Releases, allow your browser to install unknown apps if Android asks, and open the downloaded APK. On first launch, link the folder containing recordings and save the Deepgram key in Settings. Add a Gemini key under AI Pass to enable the default post-transcription analysis.

GitHub-hosted APKs are sideloaded rather than installed through Google Play, so Play Protect may show an unknown-developer warning. Only continue when the APK came from this repository's release page.

On Samsung Galaxy devices, Auto Blocker prevents all unknown-source installs while enabled. Open **Settings → Security and privacy → Auto Blocker** and temporarily turn it off, then search Settings for **Install unknown apps** and allow the app that opened the APK (usually My Files or Chrome). Turn Auto Blocker back on after installation.

If Android reports a package conflict, uninstall an older Debrief build and retry. Releases from this repository share one signing certificate and upgrade normally; independently signed debug builds do not.

## Scope

Android 10 or newer. Recording, video, live transcription, cloud sync, collaboration, and iOS are intentionally out of scope for v1.

The universal APK includes ARM64 libraries for devices such as the OnePlus 13. CI verifies that every ARM64 and x86-64 native load segment is aligned for Android devices using 16 KB memory pages.

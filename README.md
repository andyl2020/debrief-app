# Debrief

Debrief is a local-first Android app for capturing and reviewing long field recordings. It records offline into a linked phone folder, transcribes recordings with speaker labels and word timestamps, and provides synced playback, reversible text/audio redaction mode, Transcript Quality reports, manual conversation sets, full-text search, timestamped comments, speaker naming, optional AI Enhance repair diffs, Markdown export, and reinstall-safe JSON sidecars.

## What works

- Persistent Android Storage Access Framework folder permission with automatic rescans
- Dedicated offline Recorder tab with an editable filename, live level meter, timer, pause/resume, stop/save, screen-off foreground capture, dismissible notification controls, call interruption handling, storage safeguards, recoverable local parts, and lossless M4A joining
- MP3, M4A, WAV, and AAC library with new/queued/transcribing/ready/failed states and physical file rename controls
- WorkManager transcription queue with unmetered Wi-Fi by default and automatic retry
- Long-press multi-select with explicit checkbox-based batch transcription
- AssemblyAI transcription is the recommended default for noisy field recordings, with Deepgram Nova-3 still available as a fallback
- Selectable transcription upload quality: unchanged original audio by default, balanced 96 kbps mono AAC, or 64 kbps data saver
- Transcript Quality reports that flag suspicious outputs such as missing timestamps, large transcript gaps, truncation, low density, and diarization issues
- Per-key local usage tracking plus Deepgram provider usage, spend, and balance when the key has read scopes
- Gemini, OpenAI-compatible, or Claude post-transcription AI pass for summaries, speaker suggestions, and intelligent physical rename with undo
- Optional Advanced/Experimental AI Enhance with low-confidence rough-spot detection, conservative Gemini text repair, optional short-clip audio re-listen, selection enhancement, versioned repair runs, Cleaned view, and accept/revert review
- Per-recording AI privacy skip, automatic/re-run controls, manual set start/end/edit/delete/merge/split, and local per-key AI usage
- Side-opening Chapters table of contents combining manual conversation sets and timestamped comments, with tap-to-jump navigation
- Reversible redaction mode for screen recording/sharing: mask transcript text as `[redacted]` and mute playback over redacted timestamp ranges without modifying source audio
- Media3 playback with circular ± 3-second skip controls, long-press skip interval cycling, immediate 1×/1.2×/1.5×/2×/3×/4× speed control, saved position, transcript follow/highlight, tap-to-seek, and transcript reload
- SQLCipher-encrypted Room database with transcript-only in-player search and broad Library FTS5 search across filenames, transcripts, summaries, and comments
- Timestamped comment create/edit/delete and recording-wide speaker aliases
- Markdown share-sheet export
- JSON sidecars next to each recording, restored after reinstall

## Privacy and secrets

API keys are entered in Settings and encrypted with a non-exportable Android Keystore AES-GCM key. They are excluded from Android backup and never written to source, Gradle properties, logs, sidecars, or APK resources. The Room/FTS database is encrypted at rest with SQLCipher using a random passphrase protected by the same Keystore mechanism. Private developer fixtures and provider keys under `local-testing` are gitignored and never used by normal CI.

The app does not provide cloud storage. Audio is prepared in the app cache, sent directly to the selected transcription provider over HTTPS, and deleted from the cache after the request completes. AI Enhance never sends whole recordings to Gemini; when enabled, it sends only short extracted clips for targeted re-listening. The optional Organize Recording pass sends transcript text, never audio, to the AI provider selected in Settings. Redaction mode stores timestamp metadata and mutes playback in-app; it does not edit source recordings. Original recordings and all durable app data remain on the phone.

Recording is completely offline. During an active session Debrief writes protected local M4A parts in app-specific device storage, then losslessly joins and copies the finished recording into the linked folder when Stop is tapped. Temporary parts are removed after a verified folder save.

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

Download the latest APK from GitHub Releases, allow your browser to install unknown apps if Android asks, and open the downloaded APK. On first launch, link the folder containing recordings and save the AssemblyAI key in Settings for the recommended noisy-field transcription path. Deepgram remains available as a fallback. Add a Gemini key only if you want optional AI tools such as Organize Recording or Advanced/Experimental AI Enhance.

GitHub-hosted APKs are sideloaded rather than installed through Google Play, so Play Protect may show an unknown-developer warning. Only continue when the APK came from this repository's release page.

See [RELEASE_NOTES.md](RELEASE_NOTES.md) for the complete feature guide, usage instructions, release history, limitations, and known edge cases.

Private real-audio transcription fixtures are opt-in and local-only. See [docs/LOCAL_AUDIO_TESTING.md](docs/LOCAL_AUDIO_TESTING.md).

Release signing and APK publishing are automated through GitHub Actions. See [docs/release-process.md](docs/release-process.md).

Future iOS planning is intentionally separate from current Android development. See [docs/future/ios-and-shared-contract-strategy.md](docs/future/ios-and-shared-contract-strategy.md) for the agreed Android-first/shared-contract strategy.

On Samsung Galaxy devices, Auto Blocker prevents all unknown-source installs while enabled. Open **Settings → Security and privacy → Auto Blocker** and temporarily turn it off, then search Settings for **Install unknown apps** and allow the app that opened the APK (usually My Files or Chrome). Turn Auto Blocker back on after installation.

If Android reports a package conflict, uninstall an older Debrief build and retry. Releases from this repository share one signing certificate and upgrade normally; independently signed debug builds do not.

## Scope

Android 10 or newer. Video, live transcription, cloud sync, collaboration, and iOS are intentionally out of scope for v1.

The universal APK includes ARM64 libraries for devices such as the OnePlus 13. CI verifies that every ARM64 and x86-64 native load segment is aligned for Android devices using 16 KB memory pages.

# Debrief release notes and feature guide

This is the cumulative guide to what the current APK includes, how to use it, and what to expect. Every release keeps this file up to date.

## Current feature guide

### Recordings and transcription

- Link a phone folder from the Library screen. Debrief scans MP3, M4A, WAV, and AAC files in place; it does not copy or upload them until you explicitly transcribe.
- Press and hold recordings to enter multi-select, check the recordings you want, then tap **Transcribe**. Batch transcription is disabled until at least one eligible recording is selected.
- Choose Deepgram or AssemblyAI in Settings and save the matching API key. Missing or invalid configuration is reported in the UI instead of crashing the app.
- Choose an upload-quality mode in Settings. **Original** streams the linked file unchanged and is the default for best transcription accuracy; **Balanced** creates a temporary 96 kbps mono AAC copy; **Data saver** creates a temporary 64 kbps mono AAC copy. Original recordings are never modified.
- Deepgram uses Nova-3 diarization, punctuation, utterances, and word timestamps. Long timelines are rebuilt from complete provider word data so gaps in provider utterance grouping do not discard speech.

### Review and playback

- Tap a recording to open its synchronized transcript. Tap any transcript segment, search result, chapter, or comment chapter to seek to that time.
- Tap the speed label beside Play/Pause to choose **1×, 1.2×, 1.5×, 2×, 3×, or 4×**. Changes take effect during playback without restarting or losing position.
- Use the refresh icon beside search to reload transcript state and recover a saved sidecar when local transcript rows are absent.
- Search within a recording from the review search field, or search all recordings from the Library search action.

### Chapters and AI analysis

- Tap the **Chapters** list icon beside Add Comment. A drawer opens from the side with AI-detected conversation sets and comments merged into one chronological table of contents.
- Tap any entry to seek to its timestamp and close the drawer. The set containing the current playback position is highlighted.
- The drawer is the canonical place for detected sets. The old expandable Library set list and large in-transcript set panel were removed to avoid duplicate, inconsistent navigation.
- Use **Merge with next** on a set or **Split active set** at the current playback position to correct chapter boundaries.
- The compact **AI analysis** card in Chapters shows status, summary, errors, speaker-name suggestions, Skip AI state, and recording-rename undo. Tap the sparkle button beside refresh to run or rerun analysis.
- Supported AI providers are Gemini, OpenAI-compatible endpoints, and Claude/Anthropic. The transcript text—not audio—is sent to the selected AI provider.

### Comments, search, and export

- Tap Add Comment at the current playback position. Comments remain visible beside transcript context and also become jump targets in Chapters.
- Comments can be edited or deleted inline. Comments before the first segment, in transcript gaps, and after the last segment remain visible.
- Export creates Markdown through Android's share sheet with timestamped transcript text and comments.

### Usage, storage, and privacy

- Settings tracks local per-key transcription and AI usage. Deepgram provider usage, spend, and balance appear when the key has the provider scopes required for those endpoints.
- API keys are encrypted using Android Keystore and are excluded from backup, source control, sidecars, logs, and APK resources.
- The Room/FTS database is encrypted with SQLCipher. JSON sidecars beside recordings preserve transcript, comments, aliases, AI summary, sets, and speaker suggestions across reinstall/rescan.

## Limitations, gotchas, and edge cases

- Debrief is sideloaded from GitHub rather than installed from Google Play. Play Protect may warn about an unknown developer. Install only the APK attached to this repository's release.
- Android 10 or newer is required. Universal APKs include ARM64 and are verified for 16 KB memory pages used by modern phones such as the OnePlus 13.
- The Chapters drawer opens from its toolbar button. Closed-edge swipe is intentionally disabled so it does not interfere with Android back gestures; swipe-to-close works while the drawer is open.
- AI-detected titles, summaries, names, and boundaries can be wrong. Review them before relying on them. Rerunning the AI analysis replaces the detected set analysis, so perform final manual merge/split corrections after the last rerun.
- Split is enabled only when playback is inside a detected set and at least one second from either boundary. The final set cannot merge forward.
- Comments are durable user data and are not removed by rerunning AI. A comment and set at the same timestamp both appear, with the set first.
- High playback speeds preserve position but may reduce intelligibility and can expose decoder limitations in damaged or unusual audio files.
- Provider usage totals depend on API-key permissions and provider availability. Local usage remains visible when provider billing endpoints are unavailable.
- Debrief has no cloud sync, collaboration, iOS app, video support, live recording, or live transcription. Original audio and durable app data remain on the phone.
- Releases signed by this repository upgrade in place. Debug or independently signed APKs must be uninstalled first because Android treats their signature as a different developer.

## Release history

### v1.5.0 — Original-quality transcription uploads (2026-07-08)

- Added Original, Balanced, and Data saver transcription-audio modes with Original as the safe default for existing and new installs.
- Original mode streams the linked recording directly to Deepgram or AssemblyAI without transcoding or creating a second full-size cache copy.
- Balanced mode uses a temporary 96 kbps mono AAC upload; Data saver retains the previous temporary 64 kbps mono AAC behavior.
- Replaced Deepgram's deprecated `diarize=true` request with `diarize_model=latest` for the current batch diarizer.
- This is Phase 1 of the audio-quality roadmap. Enhanced playback and safe retranscription controls remain separate future phases so their quality impact can be measured independently.
- Verified 14/14 instrumentation tests on Android 11 with 4 KB pages and Android 15 with 16 KB pages, plus unit tests, lint, R8, production signing, native alignment, and signed upgrades from v1.4.1.
- APK: 8,930,151 bytes; SHA-256 `1FCD3FDB1ED5867FFEB48711F11AC71BE09AD8A2A26B937C55F2CD19C3D4B815`.

### v1.4.1 — Transcript-only player search (2026-07-08)

- Fixed recording-level search matching the audio filename even when the words were absent from its transcript.
- In-player search now searches transcript text only. It intentionally excludes the filename, comments, AI summary, and detected-set titles/summaries.
- Library-wide search remains broad and continues to search filenames, transcript text, comments, AI summaries, and detected sets.
- No database migration or reindex is required; this is a query-scope correction.
- Verified 12/12 instrumentation tests on Android 11 with 4 KB pages and Android 15 with 16 KB pages, plus unit tests, lint, R8, production signing, native alignment, and signed upgrades from v1.4.0.
- APK: 8,924,121 bytes; SHA-256 `BDE849E424120677512E1EB6D3D9ECB3256BEF726D400C3447C026F19153CE7B`.

### v1.4.0 — Chapters and UI consolidation (2026-07-07)

- Added the side-opening Chapters table of contents combining detected sets and comments in playback order.
- Added tap-to-jump, current-set highlighting, set merge, and current-position split controls inside Chapters.
- Moved AI status, summary, speaker confirmations, Skip AI, errors, and rename undo into a compact Chapters overview.
- Removed duplicate set lists from Library cards and the transcript body.
- Added this cumulative feature guide and a mandatory repository release-documentation policy.
- Verified 12/12 instrumentation tests on Android 11 with 4 KB pages and Android 15 with 16 KB pages, plus unit tests, lint, R8, production signing, native alignment, and signed upgrades from v1.3.0.
- APK: 8,924,056 bytes; SHA-256 `F148221E0C65C3270730B735650CCEB1915A3857B9263899BBDE71FB66C577C7`.

### v1.3.0 — Playback speed ramping

- Added immediate 1×, 1.2×, 1.5×, 2×, 3×, and 4× playback speed selection beside Play/Pause.
- Preserved playback position and transcript following when changing speed.

### v1.2.2 — Error and comment hardening

- Added actionable missing-key/configuration errors instead of crashes.
- Isolated comment writes from optional search-index and sidecar failures.
- Restored visibility for leading, gap, and trailing comments.
- Placed the AI action between refresh and Add Comment.

### v1.2.1 — AI retry hardening

- Added exponential worker retry when both Gemini Flash and Flash-Lite are temporarily quota-limited.

### v1.2.0 — AI pass and usage controls

- Added structured AI conversation sets, summaries, speaker suggestions, physical rename with undo, provider selection, local AI usage, merge/split, privacy skip, and sidecar v2 persistence.
- Added Deepgram usage credentials/visibility and checkbox-based batch transcription.

### v1.1.1 — Transcript integrity and reload

- Preserved transcript children during folder rescans.
- Rebuilt complete word timelines to prevent missing long-recording chunks.
- Added transcript reload and sidecar recovery.

### v1.0.0 — Initial Android release

- Added local-first folder linking, transcription, synchronized playback, search, timestamped comments, speaker aliases, Markdown export, encrypted storage, and reinstall-safe sidecars.

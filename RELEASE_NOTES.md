# Debrief release notes and feature guide

This is the cumulative guide to what the current APK includes, how to use it, and what to expect. Every release keeps this file up to date.

## Current feature guide

### Recordings and transcription

- Link a phone folder from the Library screen. Debrief scans MP3, M4A, WAV, and AAC files in place; it does not copy or upload them until you explicitly transcribe.
- Press and hold recordings to enter multi-select, check the recordings you want, then tap **Transcribe**. Batch transcription is disabled until at least one eligible recording is selected.
- Choose AssemblyAI or Deepgram in Settings and save the matching API key. AssemblyAI is the recommended default for noisy field recordings; Deepgram remains available as a fallback. Missing or invalid configuration is reported in the UI instead of crashing the app.
- Choose an upload-quality mode in Settings. **Original** streams the linked file unchanged and is the default for best transcription accuracy; **Balanced** creates a temporary 96 kbps mono AAC copy; **Data saver** creates a temporary 64 kbps mono AAC copy. Original recordings are never modified.
- Deepgram uses Nova-3 diarization, punctuation, utterances, and word timestamps. Long timelines are rebuilt from complete provider word data so gaps in provider utterance grouping do not discard speech.
- Every successful transcription stores a **Transcript Quality** report. The Library shows a compact quality chip, and the Review screen shows a dismissible card with details for suspicious outputs such as missing timestamps, large transcript gaps, early truncation, very low transcript density, and diarization issues.
- If a recording was stuck in **Queued** because it was started while mobile data was disabled and no unmetered Wi-Fi work actually ran, Debrief re-enqueues queued transcriptions with the current network setting when the app starts or when the mobile-data toggle changes.

### Review and playback

- Tap a recording to open its synchronized transcript. Tap any transcript segment, search result, chapter, or comment chapter to seek to that time.
- Tap the circular skip buttons beside Play/Pause to jump backward or forward. The default is **-5 seconds** and **+5 seconds**; long-press either skip button to cycle the interval through **1 second**, **3 seconds**, and **5 seconds**.
- Tap the speed label beside Play/Pause to choose **1×, 1.2×, 1.5×, 2×, 3×, or 4×**. Changes take effect during playback without restarting or losing position.
- Use the refresh icon beside search to reload transcript state and recover a saved sidecar when local transcript rows are absent.
- Search within a recording from the review search field, or search all recordings from the Library search action.

### AI Enhance

- AI Enhance is now an **Advanced / Experimental** tool. Enable **Settings → Advanced / Experimental → Show AI Enhance tools** to show the sparkle button, rough-spot cards, scrubber heat ticks, Cleaned view, and Enhance Selection.
- When enabled, tap the sparkle button beside transcript reload to run **AI Enhance**. The old organize/summarize pass remains in the top-right overflow menu as **Organize Recording**.
- Debrief flags low-confidence transcript spans locally after transcription. Rough spots appear as amber transcript cards, amber scrubber heat ticks, and a count badge on the Enhance button.
- Auto Enhance uses Gemini as a targeted second opinion. It first asks for conservative text-only repair diffs, then optionally sends only short extracted audio clips to Gemini for re-listening. Full source recordings are never sent to Gemini.
- Long-press a transcript card to start a selection, long-press another card to end the selection, then tap **Enhance Selection**. Selection mode is capped at 15 minutes per run and chunks audio into short clips.
- Raw transcripts are immutable. Cleaned view is derived from versioned repair diffs and can be toggled on/off after repairs land.
- Tap **Review** on a green repaired span to compare original vs repaired text, accept a suggested repair, revert an applied repair, and loop any saved clip at 0.75Ã—.
- Settings includes **Send short clips to Gemini** only while AI Enhance tools are enabled. Turning it off keeps text repair but disables audio re-listen. **Run automatically** is off by default and is ignored unless the Advanced AI Enhance toggle is enabled.
- Library recording cards and the player show Enhance status, determinate progress, partial-failure/error copy, and resume controls.

### Chapters and Organize Recording

- Tap the **Chapters** list icon beside Add Comment. A drawer opens from the side with AI-detected conversation sets and comments merged into one chronological table of contents.
- Tap any entry to seek to its timestamp and close the drawer. The set containing the current playback position is highlighted.
- The drawer is the canonical place for detected sets. The old expandable Library set list and large in-transcript set panel were removed to avoid duplicate, inconsistent navigation.
- Use **Merge with next** on a set or **Split active set** at the current playback position to correct chapter boundaries.
- The compact **AI analysis** card in Chapters shows status, summary, errors, speaker-name suggestions, Skip AI state, and recording-rename undo. Use **Organize Recording** in the player overflow menu to run or rerun this dormant organize pass.
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
- Transcript Quality checks are mechanical integrity checks, not a guarantee that every word is correct. A green result means no obvious missing chunks, broken timestamps, or suspicious truncation were detected.
- Provider usage totals depend on API-key permissions and provider availability. Local usage remains visible when provider billing endpoints are unavailable.
- AI Enhance can make plausible wrong fixes. High-confidence repairs auto-apply to Cleaned view; medium/low repairs remain suggestions until accepted. Revert any bad fix from the review dialog.
- Audio re-listen clips are short derived cache files, not original recordings. They may be cleared by Android cache cleanup, and they are not written to sidecars.
- Some noisy speech is unrecoverable. Debrief should mark `[inaudible]` rather than invent words when Gemini cannot hear the clip clearly.
- If the Gemini key is missing, rate-limited, offline, or blocked by the **Send short clips** toggle, Enhance fails gracefully or runs only the available text stage.
- Debrief has no cloud sync, collaboration, iOS app, video support, live recording, or live transcription. Original audio and durable app data remain on the phone.
- Releases signed by this repository upgrade in place. Debug or independently signed APKs must be uninstalled first because Android treats their signature as a different developer.

## Release history

### v1.7.0 - Provider-first reliability cleanup (2026-07-10)

- Made AssemblyAI the recommended default transcription provider for fresh installs, based on real noisy-field results beating Fireflies with Original upload. Existing installs keep their saved provider.
- Kept Original upload as the default quality path and clarified Settings copy around AssemblyAI + Original for noisy field recordings.
- Added deterministic Transcript Quality reports after successful transcription. Reports store provider, upload mode, audio duration, transcript coverage, segment/word/speaker counts, words per minute, warnings, and recommended action.
- Added Library quality chips: **Quality good**, **Check transcript**, and **Possible issue**.
- Added a dismissible Review-screen quality card and details dialog for warnings such as large timestamp gaps, invalid/missing timestamps, low transcript density, early truncation, missing word timings, one-speaker diarization, and low provider confidence when available.
- Demoted AI Enhance to **Advanced / Experimental**. Its UI is hidden by default, but the v1.6.0 database tables and code paths remain for upgrade safety and optional use.
- Preserved the shipped AI Enhance state in Git branch `archive/ai-enhance-v1.6.0`.
- Fixed stale queued transcriptions by re-enqueuing `QUEUED` recordings with current network constraints when the app starts and when the mobile-data toggle changes. Explicit transcription queueing now replaces stale unique WorkManager jobs.
- Added unit coverage for Transcript Quality analysis, including a regression for a large missing transcript gap.
- Verification: unit tests, debug lint/build, release lint/R8, production signing, ARM64/x86-64 16 KB native alignment, Android 11 instrumentation, Android 15 PS16K instrumentation, and signed v1.6.0 -> v1.7.0 upgrades passed.
- Public unauthenticated APK download from GitHub matched the verified local artifact byte-for-byte.
- APK: 9,069,287 bytes; SHA-256 `D54F7117E7898440C6851DFB94865D86A1A988176B77BE09F4A2779344F21F98`.

### v1.6.0 - AI Enhance clarity release (2026-07-09)

- Repurposed the player sparkle button into **AI Enhance** and moved the old sets/speaker/summarize/rename pass to **Organize Recording** in the overflow menu.
- Added word-confidence storage, local low-confidence span detection, amber transcript/scrubber rough-spot indicators, and an Enhance count badge.
- Added versioned `repair_runs` and `repairs` storage. Raw transcript rows are never rewritten; Cleaned view is derived from accepted repair diffs.
- Added Auto Enhance: Gemini text repair first, then optional short-clip audio re-listen capped to the worst rough spots. Whole recordings are never sent to Gemini.
- Added Enhance Selection: long-press transcript cards to mark a range, then enhance only that range. Runs are soft-capped at 15 minutes and chunked into 120-second clips.
- Added WorkManager foreground Enhance jobs with determinate progress, library/player status, partial-failure preservation, retry/backoff, and Resume.
- Added repair review: original vs repaired text, source, confidence, reason, accept/revert, and 0.75Ã— clip loop playback when a clip exists.
- Added Settings controls for manual-first auto-run and whether short audio clips may be sent to Gemini.
- Added local validation gates for model diffs: edit-only output, word-ratio bounds, supported proper nouns, confidence handling, and `[inaudible]` fallback.
- Verification: unit tests, debug lint, debug build, release lint/R8, production signing, ARM64/x86-64 16 KB native alignment, Android 11 instrumentation, Android 15 PS16K instrumentation, and signed v1.5.0 -> v1.6.0 upgrades passed.
- Public unauthenticated APK download from GitHub matched the verified local artifact byte-for-byte.
- APK: 9,039,347 bytes; SHA-256 `C68C007DFED7B19E0378CFD2C2EE4ADE5BE7B7562EE996F79E84640B4A301548`.

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

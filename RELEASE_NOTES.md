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

### Offline Recorder

- Use the bottom **Record** tab to capture a new recording completely offline. If a recordings folder is already linked, the large record button starts after microphone permission is granted. If no folder is linked, Debrief opens the folder picker first.
- The Recorder shows a live timer, microphone level, editable recording name, pause/resume, trash, and Stop. The name can be changed before starting, while recording, while paused, or before retrying a failed save. Debrief preserves the `.m4a` audio extension and replaces characters Android file providers cannot safely use.
- During recording or pause, tap the **trash** button to review a permanent-delete confirmation, then tap **Delete recording**. For the quick path, press and hold trash: it changes to delete-forever, gives haptic feedback, and immediately discards the current session without saving it to the linked folder.
- It continues in a foreground microphone service with a notification while the screen is off or another app is open.
- The notification opens the Recorder and offers pause/resume. Stop and final save remain inside Debrief to reduce accidental termination of a long session.
- On Android 13 or newer, swipe the active-recording notification away once to hide it for the rest of that recording. Capture continues, including through app switching and screen-off use. Return to Debrief for pause/resume/Stop after dismissing it.
- Recordings use 48 kHz mono 128 kbps AAC in an M4A container, approximately 58 MB per hour.
- Long recordings roll into protected local parts at roughly nine-minute boundaries without stopping the active `MediaRecorder`. Tapping Stop losslessly joins the parts and writes one normal M4A file into the currently linked folder.
- Debrief checks storage before and during capture. It requires 256 MiB free to start, pauses below 32 MiB, and resumes automatically after at least 64 MiB becomes available.
- Android call/communication mode pauses capture and resumes after the call. A user pause remains paused. If Android temporarily supplies silence because another app has higher microphone priority, Debrief shows a warning and keeps the recorder alive until microphone audio returns.
- If the folder write fails, **Save needs attention** keeps the local recovery audio and offers **Retry save** or **Choose another folder**. An unfinished session with finalized parts is recovered on the next app open.
- After a successful save, Debrief rescans the linked folder so the new recording appears in Library ready for playback or transcription.
- In Library, tap the pencil beside a recording to physically rename the source file. Existing transcript/search metadata and the reinstall-safe sidecar are refreshed to the new name.

### Review and playback

- Tap a recording to open its synchronized transcript. Tap any transcript segment, search result, chapter, or comment chapter to seek to that time.
- Tap the circular skip buttons beside Play/Pause to jump backward or forward. The default is **-3 seconds** and **+3 seconds**; long-press either skip button to cycle the interval through **1 second**, **3 seconds**, and **5 seconds**.
- Tap the speed label beside Play/Pause to choose **1×, 1.2×, 1.5×, 2×, 3×, or 4×**. Changes take effect during playback without restarting or losing position.
- Use the refresh icon beside search to reload transcript state and recover a saved sidecar when local transcript rows are absent.
- Search within a recording from the review search field, or search all recordings from the Library search action.
- Redactions are on by default in Review. Tap the **shield** button beside the review actions to turn redactions off or back on. When on, redacted transcript cards display as `[redacted]` and playback volume is muted over those timestamp ranges with a small safety pad.
- With the shield on, long-press a transcript card and tap the inline **Redact** button to redact the whole card. Redacted cards show an **N selected** chip; tap it to cancel the redaction for a specific word, or long-press the card and tap **Remove redaction** to restore the whole card.
- Redactions are designed for screen recording and feedback sharing. They are reversible in-app metadata; original transcript text and original audio files are not modified.

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

### Manual sets, Chapters, and Organize Recording

- Tap the **Chapters** list icon beside Add Comment. A drawer opens from the side with manual conversation sets and comments merged into one chronological table of contents.
- Tap Add Comment for a normal timestamped comment. Long-press Add Comment to reveal **Set start** and **Set end** actions for the current playback position.
- Manual set markers work like durable comments: **Set start** creates an open marker in Chapters, **Set end** closes the explicit range, and the next set uses the next alternating color. Transcript cards are colored only inside a closed start-to-end set range; blank gaps outside closed sets stay blank.
- Tap any entry to seek to its timestamp and close the drawer. A closed set containing the current playback position is highlighted.
- Closed manual sets appear as colored bands on the playback scrubber. Redactions do not appear in Chapters/bookmarks; they appear only in the transcript and scrubber while the shield is on.
- The drawer is the canonical place for sets. The old expandable Library set list and large in-transcript set panel were removed to avoid duplicate, inconsistent navigation.
- Use **Edit** on a set to change its title, start time, or end time. Use **Delete** to remove the marker without deleting audio, transcript text, or comments. Use **Merge next** on a set or **Split active set** at the current playback position to correct manual boundaries.
- The compact **AI analysis** card in Chapters shows status, summary, errors, speaker-name suggestions, Skip AI state, and recording-rename undo. Use **Organize Recording** in the player overflow menu to run or rerun this dormant organize pass. Organize Recording no longer creates or overwrites sets.
- Supported AI providers are Gemini, OpenAI-compatible endpoints, and Claude/Anthropic. The transcript text—not audio—is sent to the selected AI provider.

### Comments, search, and export

- Tap Add Comment at the current playback position. Comments remain visible beside transcript context and also become jump targets in Chapters.
- Comments can be edited or deleted inline. Comments before the first segment, in transcript gaps, and after the last segment remain visible.
- Export creates Markdown through Android's share sheet with timestamped transcript text and comments.

### Usage, storage, and privacy

- Settings tracks local per-key transcription and AI usage. Deepgram provider usage, spend, and balance appear when the key has the provider scopes required for those endpoints.
- Settings shows the installed Debrief version and version code near the bottom of the screen.
- API keys are encrypted using Android Keystore and are excluded from backup, source control, sidecars, logs, and APK resources.
- The Room/FTS database is encrypted with SQLCipher. JSON sidecars beside recordings preserve transcript, comments, aliases, AI summary, sets, and speaker suggestions across reinstall/rescan.

## Limitations, gotchas, and edge cases

- Debrief is sideloaded from GitHub rather than installed from Google Play. Play Protect may warn about an unknown developer. Install only the APK attached to this repository's release.
- Android 10 or newer is required. Universal APKs include ARM64 and are verified for 16 KB memory pages used by modern phones such as the OnePlus 13.
- No Android app can guarantee recording through force-stop, uninstall, reboot/power loss, revoked microphone/storage permission, full or failed storage, hardware failure, or an OEM killing the foreground service. Debrief checkpoints finalized parts, but an abruptly terminated currently open M4A part (up to roughly nine minutes) may not be recoverable.
- Active audio is first written to app-specific device storage and copied to the linked folder only when Stop/finalization succeeds. Do not uninstall or clear Debrief data while **Save needs attention** is present; that removes the local recovery copy.
- Recorder trash is intentionally irreversible. The confirmation protects normal taps, but a completed press-and-hold deletes the current session immediately. It removes only the in-progress app-private parts; it does not delete previously saved Library recordings. Once Stop has entered final saving, a stale trash action is ignored rather than racing the folder export.
- Normal Android apps cannot record the audio of a phone call. Debrief pauses for detected call/communication mode. Some OEM or calling apps may not expose mode changes consistently.
- Android may give Debrief silence while a higher-priority app uses the microphone. Debrief warns when the platform reports this and continues automatically, but it cannot reconstruct speech that Android did not deliver.
- OnePlus and other aggressive battery managers may offer an app-specific **Allow background activity** or **Unrestricted** battery option. The foreground service and wake lock are designed for screen-off capture; enabling that OEM option provides additional protection for critical multi-hour sessions.
- Denying notification permission does not grant another app microphone access, but it can make the ongoing foreground-service notice less visible. Grant notifications for clear long-session status.
- Android 12 and older normally keep foreground-service notifications non-dismissible. On Android 13+, a dismissed recording notification stays hidden until the next recording, but Android can still list Debrief under **Active apps** because recording continues. Dismissing the notification removes its pause/resume buttons; reopen Debrief for controls.
- Recorder and Library renames preserve the file's actual audio extension. Blank names are rejected, unsupported filename characters are replaced, and a document provider may reject a name collision; the original file remains unchanged when a rename fails.
- The Chapters drawer opens from its toolbar button. Closed-edge swipe is intentionally disabled so it does not interfere with Android back gestures; swipe-to-close works while the drawer is open.
- AI-generated summaries, speaker names, and rename suggestions can be wrong. Sets are manual-only because automatic boundaries were not reliable enough.
- Split is enabled only when playback is inside a closed set and at least one second from either boundary. The final set cannot merge forward. An open set must be ended before another set can start.
- Comments are durable user data and are not removed by rerunning AI. A comment and set at the same timestamp both appear, with the set first.
- Redaction mode mutes Debrief's in-app player by setting playback volume to zero during redacted ranges. This is appropriate for screen recording from Debrief, but it is not a permanent export-safe edit of the source audio file.
- Redaction creation is card-level in the Review UI. When word timestamps exist, Debrief stores card redactions as word-level ranges so individual words can be unredacted from the **N selected** chip. If word timing is missing, Debrief falls back to whole-card redaction/removal.
- High playback speeds preserve position but may reduce intelligibility and can expose decoder limitations in damaged or unusual audio files.
- Transcript Quality checks are mechanical integrity checks, not a guarantee that every word is correct. A green result means no obvious missing chunks, broken timestamps, or suspicious truncation were detected.
- Provider usage totals depend on API-key permissions and provider availability. Local usage remains visible when provider billing endpoints are unavailable.
- AI Enhance can make plausible wrong fixes. High-confidence repairs auto-apply to Cleaned view; medium/low repairs remain suggestions until accepted. Revert any bad fix from the review dialog.
- Audio re-listen clips are short derived cache files, not original recordings. They may be cleared by Android cache cleanup, and they are not written to sidecars.
- Some noisy speech is unrecoverable. Debrief should mark `[inaudible]` rather than invent words when Gemini cannot hear the clip clearly.
- If the Gemini key is missing, rate-limited, offline, or blocked by the **Send short clips** toggle, Enhance fails gracefully or runs only the available text stage.
- Debrief has no cloud sync, collaboration, iOS app, video support, or live transcription. Original audio and durable app data remain on the phone.
- Releases signed by this repository upgrade in place. Debug or independently signed APKs must be uninstalled first because Android treats their signature as a different developer.

## Release history

### v1.9.2 - Active recording discard (2026-07-23)

- Added a dedicated trash control while the Recorder is recording or paused.
- A normal tap opens explicit permanent-delete confirmation. Press-and-hold changes the icon to delete-forever, gives haptic feedback, and discards immediately.
- Discard stops the microphone service, releases the wake lock, removes every app-private part for that session, clears recovery state, and never enters the linked-folder export path.
- Late/stale discard actions are ignored after final saving begins so deletion cannot race a normal Stop/save.
- Added Compose tests for button presence, tap confirmation, and direct hold behavior plus a real microphone-service test that proves all private session parts are deleted.
- Verification before tagging: JVM unit tests, debug/release lint, debug build, and release R8 passed; 28 Android 11 instrumentation tests passed with an empty crash buffer; 28 Android 15/true-16 KB tests passed, followed by a clean eight-test v1.9.2 recorder/service slice with an empty crash buffer. Production signing, exact artifact verification, and upgrade testing are completed below after publication.

### v1.9.1 - Recording names and notification control (2026-07-23)

- Added a clean **Recording name** field to the Recorder. Names can be edited before or during capture and remain checkpointed through pause, interruption, and save retry.
- Added a pencil action to every Library recording for physical source-file rename, including search-index and sidecar refresh.
- Fixed active-recording notifications repeatedly returning after the user swiped them away. Android 13+ dismissal is now remembered for the current session while capture continues normally.
- Added filename sanitization/extension-preservation unit coverage, Recorder and Library Compose coverage, foreground-service rename persistence checks, deterministic dismissal-state coverage, and a real Android 15 system-notification swipe test.
- Verification before tagging: JVM unit tests, debug lint/build, 25 Android 11 instrumentation tests, 25 Android 15/16 KB instrumentation tests, a separate physical notification-shade swipe test, real microphone pause/resume/recovery checks, and clean app crash buffers passed. Production signing, release R8, published APK size/hash, and final 16 KB artifact verification are completed by the release workflow.
- GitHub Release: https://github.com/andyl2020/debrief-app/releases/tag/v1.9.1
- Independently verified public APK: 9,200,363 bytes; SHA-256 `AF4030A673E7902EA40B423A8452F2ED571A40B852E5F3A7139D2F405774A30A`. Production signature, version code 22/name 1.9.1, ARM64/x86-64 16 KB alignment, clean launch, and signed v1.9.0 → v1.9.1 upgrade passed.

### v1.9.0 - Reliable offline Recorder (2026-07-23)

- Added a dedicated bottom-navigation Recorder with default-recorder-style timer, level meter, large record control, pause/resume, stop/save, folder status, and visible offline/format information.
- Added Android native `MediaRecorder` capture in an exported-false foreground microphone service with persistent notification controls and a 12-hour-capped partial wake lock for screen-off reliability.
- Added 48 kHz mono 128 kbps AAC/M4A recording with protected 8 MiB local rollover parts and a lossless `MediaExtractor`/`MediaMuxer` join at Stop.
- Added automatic call/communication pause/resume, Android client-silence warnings for competing microphone apps, two-attempt encoder restart, proactive storage checks, and low-storage automatic pause/resume.
- Added durable session checkpointing, next-open recovery, folder-save retry/change-folder UX, local-file retention on export failure, and immediate Library rescan after a successful save.
- Fixed a transcription-settings race so an immediate Transcribe tap uses the latest persisted provider and mobile-data choice instead of a briefly stale in-memory value.
- Added real device regression coverage for actual microphone capture, pause/resume, forced folder-save failure, playable local recovery, M4A joining, and expanded Recorder Compose states.
- Added fully gitignored `local-testing` fixtures with an opt-in real Deepgram test and local transcript output. Private audio, keys, and results are excluded from Git, normal CI, APK resources, and releases.
- See `docs/RECORDING_ARCHITECTURE.md` for the reliability contract and `docs/LOCAL_AUDIO_TESTING.md` for private fixture setup.
- Verification passed for the JVM unit suite, debug and release lint/build/R8, 23 Android 11 instrumentation tests, 23 Android 15 16 KB instrumentation tests, real microphone/pause/resume/recovery/join checks, production signing, and ARM64/x86-64 16 KB native alignment.
- GitHub Release: https://github.com/andyl2020/debrief-app/releases/tag/v1.9.0
- Independently verified public APK: 9,191,636 bytes; SHA-256 `52E46E7ABC8A6DB73E3770433E3764443FCF82CC393182812B80ADD44AC273EF`.

### v1.8.3 - Readable selective redaction undo (2026-07-21)

- Updated the **N selected** redaction undo menu to show the actual word beside each cancel action, for example **Cancel redaction 5: Vancouver**.
- Kept the word number in the label so repeated words remain distinguishable.
- Added regression coverage so redacted-word choices always include visible word text for the undo menu.

### v1.8.2 - Selective redaction undo (2026-07-21)

- Kept the simple whole-card redaction creation flow: shield on, long-press a transcript card, tap **Redact**.
- Added an **N selected** chip on redacted cards. Tap it to choose **Cancel redaction 1**, **Cancel redaction 2**, and so on for the redacted words in that card.
- New card redactions are stored as word-level timestamp ranges when provider word timing is available, while still rendering as one clean `[redacted]` pill when the whole card is redacted.
- Existing v1.8.1 full-card redactions can be selectively unredacted too; Debrief converts the card into individual word redactions and removes only the selected word.
- If a transcript card has no word timestamps, selective word undo is unavailable and Debrief keeps the reliable whole-card redaction fallback.

### v1.8.1 - Card-level redaction UX fix (2026-07-21)

- Simplified redaction creation to whole transcript cards only. Word-level long-press selection was removed because it was too fussy and unreliable on-device.
- Redaction mode is now on by default in Review. The shield button explicitly turns it off or back on.
- Long-pressing an unredacted transcript card shows an inline **Redact** action for the whole card.
- Long-pressing an already-redacted transcript card shows an inline **Remove redaction** action.
- Fixed a card-redaction masking bug where the first word could remain visible. Whole-card redactions now render the card as exactly `[redacted]`.
- Audio muting remains toggleable with the shield and covers the full redacted card timestamp range.

### v1.8.0 - Reversible redaction mode (2026-07-21)

- Added a shield toggle in Review for reversible redaction mode.
- Added inline transcript redaction creation: shield on, long-press a word, optionally long-press another word in the same segment to extend the range, then tap **Redact** inside the transcript card.
- Added whole-segment redaction by long-pressing the transcript card/header while shield mode is on.
- Redacted transcript text displays as `[redacted]` while the shield is on and returns to original text when the shield is off.
- Playback now mutes automatically over redacted timestamp ranges while the shield is on, with a small safety pad to reduce audio leakage at the edges.
- Existing redactions can be removed inline from affected transcript cards; redactions are not shown in Chapters/bookmarks.
- Redactions are persisted in the encrypted Room database and JSON sidecars. Source audio and raw transcript data remain untouched.
- Added colored scrubber bands for closed manual sets and black scrubber ticks for active redactions.
- Added Room migration v5 and regression tests for redaction masking, selection, and audio mute timing.

### v1.7.3 - Explicit set ranges only (2026-07-20)

- Changed transcript set display so sets no longer auto-fill blank transcript space by default.
- Open **Set start** markers remain visible/editable in Chapters, but they do not color or label following transcript cards until **Set end** closes the range.
- Closed sets color and label only the explicit start-to-end range; gaps before, after, or between sets remain normal transcript cards.
- Added regression tests for open marker behavior and closed set range boundaries.

### v1.7.2 - Manual set CRUD (2026-07-20)

- Added full manual set update/delete support from Chapters.
- Set rows now expose **Edit** and **Delete** actions.
- Edit supports title, start time, and end time changes using `m:ss` or `h:mm:ss` timestamp input. Leaving end time blank keeps a set open.
- Delete removes only the set marker; transcript text, comments, and audio remain untouched.
- New manually created sets avoid reusing deleted `Set N` names.
- Added test coverage for set edit/delete actions, timestamp parsing, and set numbering.

### v1.7.1 - Manual set markers and player polish (2026-07-19)

- Removed automatic set creation from Organize Recording. The AI pass still handles summaries, speaker suggestions, and rename ideas, but it no longer creates, replaces, or deletes conversation sets.
- Added manual set marking from the Review toolbar: long-press Add Comment, then tap **Set start** or **Set end** at the current playback position.
- Added alternating set colors in transcript cards and Chapters so manually marked regions are visually distinct.
- Changed playback skip default to 3 seconds while keeping long-press interval cycling through 1, 3, and 5 seconds.
- Added a visible app version/code in Settings.
- Fixed secure API-key writes to commit synchronously so saved keys are durable immediately and security-at-rest tests are deterministic.
- Verification: unit tests, debug lint/build, release lint/R8, production signing, ARM64/x86-64 16 KB native alignment, and Android 11 instrumentation passed. Android 15 PS16K emulator was attached but skipped by Gradle as Unknown API Level.
- APK: 9,079,617 bytes; SHA-256 `8DB552F8D2E389570189010E6205244E5D6FFBFB6ED5041AB80B244E45080A57`.

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

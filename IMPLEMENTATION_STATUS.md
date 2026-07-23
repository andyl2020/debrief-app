# Debrief implementation checkpoint

Last updated: 2026-07-23

## Objective

Implement and release Debrief v1.9.2 with a safe active-recording trash action: confirmed tap or haptic press-and-hold discard, complete private-part cleanup, and no destination export.

## Shipped checkpoint

- Release: v1.1.1
- Commit: `0e29fee`
- Bug fixes: recording rescans preserve transcript children; complete provider word timelines render without missing utterance gaps; review refresh reloads and restores transcript data.
- Verification: JVM unit tests, Android instrumentation tests on Android 11 and Android 15 16 KB, signed upgrade tests, lint, and APK alignment checks passed.

## Current checkpoint

- v1.9.2 adds a dedicated trash control during recording and pause. Tap opens a permanent-delete confirmation; press-and-hold changes the icon, gives haptic feedback, and discards immediately.
- Discard is isolated from Stop/save: it stops and releases `MediaRecorder`, removes callbacks and the wake lock, deletes all app-private session parts, clears recovery state, and stops the service without calling folder export. Stale actions are ignored once finalization begins.
- Verification so far: JVM tests, debug/release lint, debug build, release R8, 28 Android 11 instrumentation tests with an empty crash buffer, 28 Android 15 true-16 KB tests, and a clean eight-test v1.9.2 Android 15 recorder/service slice with an empty crash buffer passed.
- Source checkpoint `5bc7879` is pushed. Version/release documentation, tagged production signing, public APK verification, and signed upgrade verification are in progress. Local release packaging correctly stops only at the intentionally absent local production keystore; GitHub Actions owns signing.
- v1.9.1 adds an editable Recorder filename before/during capture, a Library pencil rename action, shared filename sanitization/extension preservation, search/sidecar refresh, and a persisted per-session notification-dismissed state.
- Android 13+ notification dismissal uses an exported-false broadcast receiver. Once the system reports the user's swipe, timer and pause/resume notification updates are suppressed while the foreground microphone service and recording continue.
- Verification so far: unit tests, debug lint/build, 25 Android 11 tests, 25 Android 15 16 KB tests, a separate real Android 15 notification-shade swipe test, real microphone pause/resume/save-failure recovery, Recorder/Library UI tests, visual Recorder inspection, and empty final app crash buffers passed.
- Source checkpoints `8703956`, `60a6458`, and `a16f096` plus release checkpoint `51a4c7d` are pushed.
- GitHub Actions run 30023447851 passed unit tests, release lint/R8, production signing, 16 KB alignment, artifact preparation, and publication from annotated tag `v1.9.1`.
- GitHub Release v1.9.1 is public: https://github.com/andyl2020/debrief-app/releases/tag/v1.9.1
- The independently downloaded public APK is 9,200,363 bytes with SHA-256 `AF4030A673E7902EA40B423A8452F2ED571A40B852E5F3A7139D2F405774A30A`.
- Public-artifact verification passed: package `com.andyluu.debrief`, version code 22/name 1.9.1, production certificate SHA-256 `32BB05383EBD2FE29B70306D607842F1AAED8066C193C720A35EA5B8B8F60FE0`, APK Signature Scheme v3, ARM64/x86-64 16 KB ELF alignment, clean launch, and signed v1.9.0 → v1.9.1 upgrade.
- v1.9.0 recording is implemented behind a dedicated Record tab.
- The capture engine uses Android's maintained native `MediaRecorder` in a microphone foreground service with a partial wake lock, 48 kHz mono 128 kbps AAC/M4A, live amplitude, manual pause/resume, and app-only Stop.
- Calls detected through Android audio mode pause and resume automatically. Concurrent microphone capture is monitored; if Android silences Debrief for a higher-priority app, the UI explains that capture remains alive and audio returns automatically.
- Long recordings roll into protected local parts at roughly nine-minute/8 MiB boundaries using `MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING` and `setNextOutputFile`, then join without re-encoding through `MediaExtractor`/`MediaMuxer`.
- Start requires 256 MiB free. Active capture pauses below 32 MiB and resumes after 64 MiB is available.
- Folder-export failures preserve local audio and expose Retry/choose-another-folder. Interrupted sessions recover on the next app open when at least one finalized part is playable.
- The linked folder is rescanned after save so a new recording appears in Library immediately.
- `local-testing/` is fully gitignored. The opt-in real Deepgram fixture test accepts private audio from that folder, validates transcript/word timing, and writes its output locally.
- Verification so far: unit tests, debug lint/build, release lint/R8, 23 Android 11 tests, 23 Android 15 16 KB tests, actual microphone capture/pause/resume, forced export failure recovery, M4A part joining, on-device UI inspection, and empty post-test crash buffers passed.
- The Android 11 suite exposed and verified a fix for stale provider/mobile-data StateFlow reads during immediate transcription queueing.
- GitHub Actions built, tested, R8-optimized, production-signed, 16 KB-verified, and published v1.9.0 from release checkpoint `7f6b7bf`.
- GitHub Release v1.9.0 is public: https://github.com/andyl2020/debrief-app/releases/tag/v1.9.0
- The independently downloaded public APK is 9,191,636 bytes with SHA-256 `52E46E7ABC8A6DB73E3770433E3764443FCF82CC393182812B80ADD44AC273EF`.
- Public-artifact verification passed: package `com.andyluu.debrief`, version code 21/name 1.9.0, production RSA-4096 certificate SHA-256 `32BB05383EBD2FE29B70306D607842F1AAED8066C193C720A35EA5B8B8F60FE0`, APK Signature Scheme v3, and ARM64/x86-64 16 KB ELF alignment.

- The Claude artifact is saved as `debrief-ai-features-prd-addendum.md`.
- AI persistence/migration, deterministic set detection, Gemini/OpenAI-compatible/Claude structured-output clients, retry worker, summary search indexing, sidecar v2, and SAF rename support are implemented.
- Settings and review/library controls, local AI-key usage, manual merge/split, speaker confirmation, privacy skip, physical rename, and undo are implemented.
- Provider request/response tests cover Gemini structured JSON, Flash-Lite fallback, and retryable dual-quota exhaustion.
- Signed v1.1.1 → v1.2.0 database migration and v1.2.0 → v1.2.1 upgrades passed on Android 11 and Android 15 PS16K with no crash.
- Six instrumentation tests passed on each emulator; unit tests and lint pass.
- Final signed v1.2.1 APK passed release lint, R8, signature verification, ARM64/x86-64 16 KB ELF checks, upgrades, and launches on 4 KB and 16 KB emulators.
- Final APK SHA-256: `6B08370E66E9C323EC760CE598ACFF79E0A3BD61BE3FDEFB7426F8D925E0753C`.
- GitHub Release v1.2.0 is public: https://github.com/andyl2020/debrief-app/releases/tag/v1.2.0
- v1.2.1 adds exponential worker retry when both Gemini Flash and Flash-Lite are temporarily quota-limited.
- GitHub Release v1.2.1 is public: https://github.com/andyl2020/debrief-app/releases/tag/v1.2.1
- Its unauthenticated public APK download matched the signed build's 8,858,542-byte size and SHA-256.
- v1.2.2 crash-hardening is implemented: missing transcription/AI keys are blocked with actionable messages; comment writes are isolated from non-critical sidecar/index failures; gap and trailing comments remain visible; Review actions report failures instead of throwing.
- The AI action now sits between transcript reload and add-comment in the review toolbar.
- Unit/lint/debug builds pass, and 10 instrumentation tests pass on both Android 11 and Android 15 PS16K with no crash-buffer entries.
- The signed v1.2.2 APK passed release lint/R8, production signature verification, ARM64/x86-64 16 KB alignment, and signed upgrades from v1.2.1 on 4 KB and 16 KB emulators with no crash-buffer entries.
- Final APK SHA-256: `A57D4F1BB35167738F596D54B8914E5F98B74D13987EDBA623F866C79CF7F672`.
- GitHub Release v1.2.2 is public: https://github.com/andyl2020/debrief-app/releases/tag/v1.2.2
- Its unauthenticated public APK download matched the signed build's 8,871,212-byte size and SHA-256.
- No implementation work remains for the v1.2.2 crash-hardening checkpoint.
- v1.3.0 playback speed ramping is implemented with immediate 1×, 1.2×, 1.5×, 2×, 3×, and 4× Media3 playback changes and a compact player-menu control.
- Playback-speed unit/UI regression tests pass; 11 instrumentation tests pass on both Android 11 and Android 15 PS16K.
- The signed v1.3.0 APK passed release lint/R8, production signature verification, ARM64/x86-64 16 KB alignment, and signed upgrades from v1.2.2 on 4 KB and 16 KB emulators with empty crash buffers.
- Final v1.3.0 APK SHA-256: `504D238D586CEC9B2ABC0EDEE5ED42915B6AEDABD649CAC6916DEF9B0C61B758` (8,890,293 bytes).
- GitHub Release v1.3.0 is public: https://github.com/andyl2020/debrief-app/releases/tag/v1.3.0
- Its unauthenticated public APK download matched the signed build's 8,890,293-byte size and SHA-256.
- No implementation work remains for the v1.3.0 playback-speed checkpoint.
- v1.4.0 Chapters is implemented as a side drawer combining AI-detected sets and comments in chronological order with tap-to-seek, active-set highlighting, merge, and split controls.
- Duplicate set lists were removed from Library cards and the transcript body; compact AI status and controls now live in Chapters.
- `AGENTS.md` makes detailed release documentation mandatory, and `RELEASE_NOTES.md` is the cumulative feature/usage/limitations/history guide.
- Unit tests, lint, and debug builds pass; 12 instrumentation tests pass on both Android 11 and Android 15 PS16K.
- The signed v1.4.0 APK passed release lint/R8, production signature verification, ARM64/x86-64 16 KB alignment, and signed upgrades from v1.3.0 on 4 KB and 16 KB emulators with empty crash buffers.
- Final v1.4.0 APK SHA-256: `F148221E0C65C3270730B735650CCEB1915A3857B9263899BBDE71FB66C577C7` (8,924,056 bytes).
- GitHub Release v1.4.0 is public: https://github.com/andyl2020/debrief-app/releases/tag/v1.4.0
- Its unauthenticated public APK download matched the signed build's 8,924,056-byte size and SHA-256.
- No implementation work remains for the v1.4.0 Chapters checkpoint.
- v1.4.1 restricts in-player FTS to transcript body rows, preventing filename, comment, AI-summary, and detected-set metadata matches while preserving broad Library search.
- Scoped/global regression coverage passes; unit tests, lint, debug builds, and 12 instrumentation tests on both Android 11 and Android 15 PS16K are green.
- The signed v1.4.1 APK passed release lint/R8, production signature verification, ARM64/x86-64 16 KB alignment, and signed upgrades from v1.4.0 on 4 KB and 16 KB emulators with empty crash buffers.
- Final v1.4.1 APK SHA-256: `BDE849E424120677512E1EB6D3D9ECB3256BEF726D400C3447C026F19153CE7B` (8,924,121 bytes).
- GitHub Release v1.4.1 is public: https://github.com/andyl2020/debrief-app/releases/tag/v1.4.1
- Its unauthenticated public APK download matched the signed build's 8,924,121-byte size and SHA-256.
- No implementation work remains for the v1.4.1 transcript-only player-search checkpoint.
- v1.5.0 Phase 1 adds Original/Balanced/Data saver transcription upload modes, defaults to unchanged source streaming, and upgrades Deepgram to `diarize_model=latest`.
- Phase 1 unit tests, lint, and debug builds pass; 14 instrumentation tests pass on both Android 11 and Android 15 PS16K.
- The signed v1.5.0 APK passed release lint/R8, production signature verification, ARM64/x86-64 16 KB alignment, and signed upgrades from v1.4.1 on 4 KB and 16 KB emulators with empty crash buffers.
- Final v1.5.0 APK SHA-256: `1FCD3FDB1ED5867FFEB48711F11AC71BE09AD8A2A26B937C55F2CD19C3D4B815` (8,930,151 bytes).
- GitHub Release v1.5.0 is public: https://github.com/andyl2020/debrief-app/releases/tag/v1.5.0
- Its unauthenticated public APK download matched the signed build's 8,930,151-byte size and SHA-256.
- No implementation work remains for Phase 1; Phase 2 and Phase 3 remain intentionally pending.
- Phase 2 (enhanced-audio playback) and Phase 3 (safe retranscription controls) are intentionally pending separate implementation/verification passes.
- v1.6.0 AI Enhance is implemented: low-confidence span detection, raw-immutable repair runs/diffs, Gemini text repair, optional short-clip audio re-listen, Enhance Selection, progress/resume, Cleaned view, review accept/revert, and 0.75x clip loop playback.
- Auto-run now targets AI Enhance and remains off by default. The old organize pass is dormant under **Organize Recording** in the player overflow menu.
- Verification status: unit tests, debug lint/build, release lint/R8, production signing, ARM64/x86-64 16 KB alignment, Android 11 instrumentation, Android 15 PS16K instrumentation, and signed v1.5.0 -> v1.6.0 upgrades passed.
- Final local v1.6.0 APK SHA-256: `C68C007DFED7B19E0378CFD2C2EE4ADE5BE7B7562EE996F79E84640B4A301548` (9,039,347 bytes).
- GitHub Release v1.6.0 is public: https://github.com/andyl2020/debrief-app/releases/tag/v1.6.0
- Its unauthenticated public APK download matched the signed build's 9,039,347-byte size and SHA-256.
- v1.7.0 provider-first reliability cleanup is implemented: AssemblyAI is the fresh-install default and recommended noisy-field provider, AI Enhance is hidden behind an Advanced/Experimental toggle, Transcript Quality reports are stored in Room v4 and displayed in Library/Review, and stale queued transcriptions are re-enqueued on app start/mobile-data setting changes.
- Archive branch `archive/ai-enhance-v1.6.0` has been pushed from the v1.6.0 tag.
- Verification status: unit tests, debug lint/build, release lint/R8, production signing, ARM64/x86-64 16 KB alignment, Android 11 instrumentation, Android 15 PS16K instrumentation, and signed v1.6.0 -> v1.7.0 upgrades passed.
- Final local v1.7.0 APK SHA-256: `D54F7117E7898440C6851DFB94865D86A1A988176B77BE09F4A2779344F21F98` (9,069,287 bytes).
- GitHub Release v1.7.0 is public: https://github.com/andyl2020/debrief-app/releases/tag/v1.7.0
- Its unauthenticated public APK download matched the signed build's 9,069,287-byte size and SHA-256.

## Resume protocol

1. Read this file and `debrief-ai-features-prd-addendum.md`.
2. Run `git status --short` and `git log -5 --oneline`.
3. Continue the first unchecked phase below.
4. Before stopping, update this file with the exact commit, tests run, and next action; commit and push it.

## Phases

- [x] Bug diagnosis, fixes, full verification, v1.1.1 release.
- [x] Save the source artifact as a Markdown addendum.
- [x] AI-pass persistence model and migration.
- [x] Set detection and provider clients with structured output.
- [x] Settings, automatic/re-run/skip controls, and AI result UI.
- [x] Physical rename with undo and sidecar preservation.
- [x] Full regression testing and signed build.
- [x] Publish and verify GitHub Release v1.2.0.
- [x] Publish and verify final GitHub Release v1.2.1.
- [x] Harden error handling and repair comment visibility.
- [x] Publish and verify GitHub Release v1.2.2.
- [x] Implement and device-test playback speed ramping.
- [x] Sign and upgrade-test v1.3.0 on 4 KB and 16 KB Android emulators.
- [x] Publish and independently verify GitHub Release v1.3.0.
- [x] Implement Chapters and consolidate duplicate set/AI UI.
- [x] Establish permanent cumulative release documentation.
- [x] Fully verify and publish GitHub Release v1.4.0.
- [x] Fix in-player search scope to transcript text only.
- [x] Fully verify and publish GitHub Release v1.4.1.
- [x] Implement Phase 1 upload-quality modes and latest Deepgram diarization.
- [x] Fully verify and publish GitHub Release v1.5.0.
- [x] Implement AI Enhance clarity release v1.6.0.
- [x] Fully verify and publish GitHub Release v1.6.0.
- [x] Fully verify v1.7.0 provider-first reliability cleanup.
- [x] Publish and verify GitHub Release v1.7.0.
- [x] Implement offline foreground-service recording and Recorder tab.
- [x] Add interruption, storage, part rollover, folder-save, and recovery handling.
- [x] Add private gitignored real-audio test fixtures and opt-in Deepgram smoke test.
- [x] Fully verify and publish GitHub Release v1.9.0.
- [x] Implement and device-test editable Recorder and Library filenames.
- [x] Fix and physically test Android 15 recording-notification dismissal.
- [x] Fully verify and publish GitHub Release v1.9.1.
- [ ] Phase 2: optional enhanced-audio playback.
- [ ] Phase 3: safe retranscription and recovery controls.

## Last verified commands

- `./gradlew testDebugUnitTest`
- `./gradlew connectedDebugAndroidTest` on Android 11 and Android 15 PS16K
- `./gradlew lintRelease assembleRelease`
- `python scripts/verify_16kb_elf.py app/build/outputs/apk/release/app-release.apk`

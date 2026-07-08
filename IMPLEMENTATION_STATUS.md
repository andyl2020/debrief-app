# Debrief implementation checkpoint

Last updated: 2026-07-07

## Objective

Implement the P1 features in `debrief-ai-features-prd-addendum.md` after fixing and releasing the transcript-loss and missing-chunk bugs.

## Shipped checkpoint

- Release: v1.1.1
- Commit: `0e29fee`
- Bug fixes: recording rescans preserve transcript children; complete provider word timelines render without missing utterance gaps; review refresh reloads and restores transcript data.
- Verification: JVM unit tests, Android instrumentation tests on Android 11 and Android 15 16 KB, signed upgrade tests, lint, and APK alignment checks passed.

## Current checkpoint

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
- Phase 1 unit and device regression coverage is implemented; full verification and release remain.
- Phase 2 (enhanced-audio playback) and Phase 3 (safe retranscription controls) are intentionally pending separate implementation/verification passes.

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
- [ ] Fully verify and publish GitHub Release v1.5.0.
- [ ] Phase 2: optional enhanced-audio playback.
- [ ] Phase 3: safe retranscription and recovery controls.

## Last verified commands

- `./gradlew testDebugUnitTest`
- `./gradlew connectedDebugAndroidTest` on Android 11 and Android 15 PS16K
- `./gradlew lintRelease assembleRelease`
- `python scripts/verify_16kb_elf.py app/build/outputs/apk/release/app-release.apk`

# Debrief Web/PWA PRD Addendum

Status: implementation and release gate for v1.12.0  
Owner: Andy Luu  
Purpose: make Debrief usable from iPhone, iPad, macOS, Windows, Linux, and Android browsers without coupling Android development to an iOS codebase.

## Product decision

Android remains the primary native app. The web app is a separately implemented, installable PWA that shares Debrief's data contracts, provider behavior, Cloudflare service, sidecar schema, and test cases. It is not a WebView wrapper and does not replace Android.

## Required workflow parity

| Workflow | Web/PWA behavior |
|---|---|
| Capture | Offline MediaRecorder capture, editable name, pause/resume, discard, five-second emission checkpoints, wake lock when available, live input label, and seamless Web Audio input-node switching where device enumeration permits it |
| Library | Import, linked-folder rescan on Chromium desktop, browser-private storage on Safari, rename, delete, per-item and batch transcription |
| Transcription | AssemblyAI and Deepgram, speaker diarization, word timing/confidence, key terms, provider errors, resumable AssemblyAI jobs |
| Review | Synced audio/transcript, saved position, follow mode, transcript-only search, 1/1.2/1.5/2/3/4x speed, configurable 1/3/5-second skip |
| Privacy | Redaction mode on by default, card redaction, per-word reveal, text masking and buffered playback mute; shared clips permanently receive the current redactions |
| Chapters | Manual set start/end, rename/delete, alternating scrubber colors, comments in the same chronological drawer |
| Comments | Create, read, update, delete at a pinned playhead timestamp |
| Sharing | One private link for one or more completed sets; selected audio, redacted transcript, and all in-range comments only; optional PIN; 30/60/90-day expiry; no download control; extend/revoke/list management |
| Cloud | Explicit per-recording sync, account-wide paired-device access, 10 GB reference meter, AES-GCM authenticated chunk encryption, seekable playback, fail-closed corruption handling |
| Interop | Android-compatible sidecar v4 import/export and automatic dual sidecars in linked-folder mode |
| Install/offline | PWA manifest, home-screen installation, cached app shell, local recording/review while offline |

## Deliberate platform constraints

These are operating-system constraints, not unfinished UI:

- iOS may suspend any PWA after the screen locks or when another app owns the microphone. The recorder requests a wake lock and checkpoints every five seconds, but it cannot provide Android's foreground-service guarantee. The UI must say this before a user trusts it with a multi-hour capture.
- Safari does not expose a general linked-folder API. Audio and annotations use origin-private storage; important annotations should be exported or cloud-synced.
- Browser storage lacks Android Keystore and SQLCipher equivalents. API keys are passphrase-encrypted at rest; the vault is memory-only while unlocked.
- High playback rates may be clamped by Safari. The UI reports the effective limitation.
- Share clip preparation decodes source audio locally. Very long sources can require substantial temporary memory even when selected sets are short.

## Security gates

- No AES-CTR or other unauthenticated encryption.
- Audio is split into independently authenticated 8 MiB AES-GCM chunks. IVs combine a random object nonce with the chunk index; recording id, object role, and chunk index are authenticated as additional data.
- The server validates plaintext/ciphertext size relationships and exact stored sizes.
- Metadata is authenticated and bound to its recording id.
- Wrong keys, tampering, truncation, reordering, and cross-recording object swaps must fail closed.
- Share metadata must not contain source filenames, paths, recording ids, redaction source text, or unselected material.
- API keys and owner tokens never enter the repository or build output.

## Release acceptance

1. Web dependency audit has no moderate-or-higher findings.
2. Web typecheck, unit/integration tests, and production build pass on Node 22.
3. Worker typecheck, D1/R2 tests, and deployment dry run pass.
4. Android test/build remains green.
5. Deployed HTTPS PWA loads on desktop and iPhone-sized viewports, survives reload, exposes its manifest, and serves no console-fatal errors.
6. Deployed Worker health, pairing, authenticated cloud range playback, and public share viewer smoke tests pass.
7. Release notes state browser constraints; no native guarantee is implied where Apple prevents it.

## Maintenance rule

For every Android release, review shared contracts (sidecar, providers, playback, redaction, chapters, sharing, cloud, errors) and port applicable changes with matching tests. Native hardware/service code stays platform-specific. A parity matrix change is part of the definition of done for a feature that affects users on both platforms.

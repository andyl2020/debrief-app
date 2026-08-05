# Debrief Web

A local-first web port of [Debrief](https://github.com/andyl2020/debrief-app), so people on iPhone and
iPad — and anyone else the Android app cannot reach — can transcribe and review long field recordings.

Ported from the Android app at tag **v1.10.2**.

There is **no backend**. The browser talks to the transcription provider directly with the user's own
API key, exactly as the Android app does. It deploys as static files.

## What works

- **Transcription** with AssemblyAI (default) or Deepgram Nova-3, including key terms, speaker labels
  and word-level timestamps
- **Resumable jobs** — the AssemblyAI transcript id is persisted, so closing the tab mid-job rejoins
  it instead of re-uploading hours of audio
- **Synced playback** with tap-to-seek, transcript follow and highlight, saved position, ±1/3/5s skip
  and 1× – 4× speed
- **Reversible redaction mode** — `[redacted]` text masking plus proactive playback muting, with the
  750 ms lead / 250 ms trail privacy buffers and per-word undo
- **Chapters** — manual conversation sets and comments merged into one chronological table of contents
- **Transcript Quality reports** — gap, truncation, density, diarization and confidence warnings
- **Search** — library-wide across filenames, transcripts, summaries and comments; transcript-only
  while reviewing
- **Timestamped comments**, speaker aliases, Markdown export
- **Sidecar interoperability** with Android (schema v4) — see below

## Cloud library (optional)

Keep chosen recordings in your own Cloudflare account and reach them from any device — the phone
browser reads what the desktop uploaded. Audio and transcripts are encrypted in the browser before
upload, so Cloudflare stores bytes it cannot read, and seeking still works because AES-CTR allows a
byte range to be decrypted on its own.

Opt-in per recording; nothing uploads unless you press Upload. See
[CLOUD-SETUP.md](CLOUD-SETUP.md) for deployment and pairing.

This is separate from the Android app's **Share Sets**, which publishes selected clips to somebody
else on an expiring link. Both use the same Worker and neither interferes with the other.

## Android interoperability

Debrief on Android writes `<recording>.debrief.json` and `<recording>.debrief.backup.json` beside each
recording. This app reads and writes the same schema v4 files.

On desktop Chrome or Edge you can link the same folder and the sidecars are written automatically.
Everywhere else, use **Export sidecar** / import to move work between devices.

## Storage

Which mode you get depends on the browser, not on a setting. The app always states which one is active.

| | Desktop Chrome / Edge | iOS Safari and everything else |
|---|---|---|
| Audio | File System Access API — a real linked folder, mirroring Android's SAF | File picker import, stored in the origin-private file system |
| Annotations | IndexedDB **+ JSON sidecars written beside the audio** | IndexedDB |
| Sidecars | Automatic, both copies, as on Android | Manual export / import |

Safari has no `showDirectoryPicker`, which is why the second column exists at all.

## Known limitations

These are honest gaps, surfaced in the UI rather than hidden:

- **Recording is Android-only.** A browser tab cannot hold a microphone foreground service with the
  screen off. The Record tab is a Coming Soon screen; AI Enhance, Organize Recording, external
  microphone routing and provider spend tracking are the same.
- **Safari clamps `playbackRate`.** 3× and 4× may not be honoured; the player reports the rate the
  browser actually applied rather than the one requested.
- **iOS suspends background tabs.** A long Deepgram upload will stall if you leave the page, which is
  why AssemblyAI is the default. Reopening the tab resumes an AssemblyAI job.
- **Compressed upload modes need in-browser AAC encoding**, which iOS does not provide. Original
  upload — the Android default, and the most accurate — always works.
- **Browser storage is evictable.** The app requests persistent storage, but iOS often refuses. Export
  sidecars for anything you cannot lose.
- **No SQLCipher.** Android encrypts its database at rest. IndexedDB is protected by the origin and
  your device, and nothing more.
- **Cloud encryption is confidentiality, not integrity.** AES-CTR means your provider cannot read
  your recordings, but it does not detect tampering. Losing the cloud passphrase loses the cloud
  copy — nobody can recover it.
- **API keys are weaker here.** Android seals them with a non-exportable hardware Keystore key. This
  app encrypts them with a passphrase you choose (PBKDF2 + AES-GCM via WebCrypto) and stores only the
  ciphertext — better than plaintext, still weaker than hardware. Settings says so.

## Develop

```bash
cd web
npm install
npm run dev        # http://localhost:5173
npm test           # full suite
npm run typecheck
npm run build      # static output in dist/
```

## Layout

```
web/src/core/       DOM-free ported logic — the "shared contract" the repo's
                    iOS strategy note asks for. Every ported test points here.
web/src/storage/    StorageAdapter interface + the two implementations, key vault
web/src/state/      repository, transcription job runner, settings, app hook
web/src/platform/   runtime capability detection
web/src/ui/         React screens
web/public/sw.js    range-decrypting playback proxy for encrypted cloud audio
web/test/           155 tests, incl. ~35 ported 1:1 from app/src/test
```

`src/core/` deliberately has no DOM or React dependency, so it stays portable and directly
comparable against the Kotlin it was ported from. Each file names the Kotlin source it came from.

## Test parity with Android

These suites are ported case-for-case from `app/src/test/`, keeping the original test names so parity
is auditable:

`DeepgramProviderTest`, `AssemblyAiProviderTest`, `TranscriptQualityAnalyzerTest`, `RedactionsTest`,
`ChapterEntriesTest`, `ErrorAndCommentHandlingTest`, `FormatTimestampTest`, `PlaybackSpeedTest`,
`AudioQualityTest`.

Web-specific suites cover the storage adapters (both implementations against one contract), the key
vault, sidecar v4 round-tripping against an Android-shaped fixture, search semantics, the
transcription retry policy, capability detection, and the Coming Soon gating.

The cloud suites cover AES-CTR range decryption at block boundaries, a 9 MiB push/pull round trip
across an upload part boundary, and the shipped `public/sw.js` itself — evaluated directly and
checked against known plaintext, because duplicated crypto that drifts yields audio that plays as
noise rather than failing.

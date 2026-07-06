# Debrief: Product Requirements Document

| | |
|---|---|
| **Status** | Draft v0.2 (recording-format blocker resolved: audio only) |
| **Date** | July 5, 2026 |
| **Scope** | Android app for post-process transcription and review of long field recordings |
| **Owner** | Andy Luu |

---

## TL;DR

Debrief (working title) is a local-first Android app. Andy points it at the folder where his recordings land, taps transcribe, and gets a Fireflies-style review experience: speaker-labeled transcripts synced to playback, full-text search across everything, and comments pinned to timestamps. All recordings and data live on the phone; audio leaves the device only transiently for transcription processing. Target cost is $0 for roughly the first two years of expected usage by stacking free API credit pools. Recordings are confirmed audio-only, one file per session.

---

## The Ask

Approve the technical decisions table. The recording-format question is resolved (audio files only, one per session), so everything is decided and ready to build.

---

## Context: What & Why

What: Andy records long sets of real conversations (2–7 hours, multiple people, often loud venues). The recordings land on his phone. This app is strictly a post-process step: after a session, he opens the app, transcribes everything in the linked folder, then plays the sessions back with a live-following transcript, searches for moments, and adds timestamped comments.

Why: reviewing a 5-hour recording by scrubbing is not realistic. Search plus speaker labels plus tap-to-jump turns hours of audio into minutes of targeted review. Existing tools (Fireflies, Otter) solve this but push everything into their cloud, with subscriptions and storage limits. Andy wants the same review experience with zero cloud storage, zero subscriptions, and files that never leave his control.

> Out of scope: recording itself (happens outside the app), video files, live/real-time transcription, cloud sync, multi-device access, collaboration/sharing, iOS.

---

## The Product (How)

### User flow

1. Record a session with any device or app; the file ends up in a folder on the phone.
2. Open Debrief. It lists every recording in the linked folder with a status: new, transcribing, or ready.
3. Tap Transcribe (or Transcribe All). On Wi-Fi, the app compresses the audio, sends it to the speech API, and saves the transcript locally with speaker labels and word-level timestamps. The cloud copy is processed and discarded; nothing is stored remotely.
4. Review: playback with the transcript following along, current line highlighted. Tap any line to jump playback to that moment. A Sync button snaps the transcript back to wherever playback currently is (for when you have scrolled off searching).
5. Search within a recording or across all of them. Tap a result to jump straight to that moment.
6. Add comments pinned to the current timestamp. Rename Speaker A/B to real names and it applies through the whole transcript.

### Feature requirements

| Priority | Feature | Notes |
|---|---|---|
| **P0** | Folder link | System folder picker; permission persists across restarts |
| **P0** | Library view | All recordings + status; new files auto-detected |
| **P0** | Transcription | Speaker diarization + word timestamps; queue multiple files |
| **P0** | Playback | Audio files (MP3, M4A, WAV, AAC); scrubbing; keeps position |
| **P0** | Synced transcript | Live highlight, tap-to-jump, Sync button |
| **P0** | Search | Full-text, per recording and global; results jump to the moment |
| **P0** | Comments | Pinned to timestamps; shown inline; searchable; edit/delete |
| **P0** | Speaker rename | Speaker A → real name across the whole transcript |
| **P1** | Export | Transcript + comments as Markdown via the share sheet |
| **P1** | Keyterm list | Andy's names/jargon fed to the API for better accuracy |
| **P1** | Sidecar backup | JSON saved next to each recording; survives app reinstall |
| **P2** | Highlights, AI summaries | Later |

### Technical decisions

All decided; approval requested, not input.

| Area | Decision | Why |
|---|---|---|
| **App type** | Native Android: Kotlin + Jetpack Compose, single sideloaded APK | Best handling of long media; no cross-platform overhead |
| **Min version** | Android 10+ | Covers modern devices; stable folder permissions |
| **Playback** | Media3 ExoPlayer | Rock-solid long-audio playback; precise seeking for tap-to-jump |
| **Data** | SQLite (Room) + FTS5 full-text index, all on device | Instant search across hundreds of hours of transcript |
| **Transcription** | Deepgram Nova-3 batch API with diarization | $200 free signup credit, no card: ≈ 500–775 hours |
| **Fallback provider** | AssemblyAI behind the same pluggable interface | Second free pool ($50 ≈ 185 hours); strong diarization |
| **Upload prep** | Compress audio to mono AAC ~64 kbps on device | A 7-hour set ≈ 200 MB; fits API limits; fast on Wi-Fi |
| **Upload policy** | Wi-Fi only by default (toggle for mobile data); background uploads with auto-retry | Long uploads survive app switching |
| **Keys** | Andy creates a Deepgram account once, pastes the API key into Settings | The only ongoing task on Andy's side |
| **Privacy posture** | Files live on the phone; audio leaves only transiently for processing | No cloud storage account anywhere |

---

## Cost (staying free)

| Stage | Pool | Covers |
|---|---|---|
| **1** | Deepgram $200 signup credit | ≈ 500–775 hours of batch transcription |
| **2** | AssemblyAI $50 signup credit | ≈ 185 more hours |
| **3** | Pay-as-you-go (only after both pools) | ≈ $0.26/hour: a 5-hour session ≈ $1.30 |

At an assumed 20–30 recorded hours per month, stage 1 alone lasts roughly 1.5 to 3 years. The app itself has no other running costs.

---

## Timeline (When)

Built by vibe-coding with Claude Code. Assumes one focused weekend per milestone; dates are placeholders Andy can delete.

| Window | Work | Output |
|---|---|---|
| **Weekend 1** (~Jul 11–12) | Skeleton, folder link, library, playback | App plays recordings from the linked folder |
| **Weekend 2** (~Jul 18–19) | Transcription pipeline: compress → upload → poll → store | First full speaker-labeled transcript |
| **Weekend 3** (~Jul 25–26) | Synced transcript UI + search | Fireflies-style review working |
| **Weekend 4** (~Aug 1–2) | Comments, speaker rename, export, polish | Installable v1 APK |

---

## Risks & Blockers

### Risks

- Diarization in loud venues: speaker labels are strong for 2–4 distinct voices and degrade with heavy crosstalk or music. Keyterm prompting recovers names; expect the messiest segments to need manual reading.
- Deepgram pricing ambiguity: public docs are unclear on whether batch diarization bills as an add-on. Worst case the free pool trends toward the ~500-hour end; the AssemblyAI fallback covers the gap.
- Long uploads on mobile: mitigated by on-device compression, Wi-Fi default, and resumable background uploads.
- Manual updates: a sideloaded APK means updates are installed by hand, not from the Play Store.
- Consent: recordings capture other people. One-party consent applies to participants in Canada, but stay mindful when recording in other jurisdictions.

### Blockers

- B1 (Andy, 5 minutes): create the Deepgram account and paste the API key into Settings.
- B2 (Andy): pick the folder and provide 1–2 real sample recordings for testing.

---

## Terminology

| Term | Meaning |
|---|---|
| **Diarization** | Splitting a transcript by who is speaking (Speaker A, Speaker B) |
| **Word timestamps** | Every word tagged with its exact time; enables tap-to-jump and live highlight |
| **Sidecar file** | A small JSON saved next to each recording holding its transcript and comments |
| **Batch (pre-recorded) API** | Upload a finished file, get the transcript back; cheaper than real-time |

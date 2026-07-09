# Debrief AI Enhance: PRD Addendum (v2)

|  |  |
| --- | --- |
| **Status** | Build-ready v2; replaces v1 of this addendum |
| **Date** | July 9, 2026 |
| **Scope** | Repurposes the v1.1 AI pass button into AI Enhance; extends PRD v0.2; targets Debrief v1.6.0 (Clarity release) |
| **Owner** | Andy Luu |

---

## TL;DR

The AI button becomes AI Enhance, with two modes. Auto mode finds Deepgram's low-confidence spans, repairs what context can justify, and re-listens to the worst clips. Selection mode lets Andy mark any stretch of the recording and have it re-listened in chunks. Full audio files never go to Gemini: that is the bottleneck, by design avoided. Deepgram transcribes whole files; Gemini only ever hears short clips. The old AI pass (sets, speaker names, summaries, rename) moves to an overflow menu, dormant, and Enhance does not depend on it. Raw transcripts stay immutable; every change is a revertable diff. Cost stays $0 at current quotas.

## The Bottleneck, Answered

Gemini should never receive a full long recording. Audio costs roughly 32 tokens per second. Full 2-hour or 7-hour files burn too much per-minute quota and force the Files API path. Debrief treats any future whole-file-to-LLM change as a regression.

| Payload | Audio tokens | Verdict |
| --- | ---: | --- |
| Full 2 hr file | ~230,000 | Rejected |
| Full 7 hr file | ~806,000 | Rejected |
| 10 min marked range, 5 chunks | ~19,000 | Acceptable |
| Auto mode worst case, 40 clips ~25s | ~32,000 | Acceptable |

Gemini also does not replace Deepgram for the app's base transcript because it does not provide the same full-file word-level timestamps, confidence scores, and diarization contract. Deepgram remains the bulk transcriber. Gemini is the targeted second opinion.

## Changes From v1

1. AI button repurposed: it now runs Enhance, not the organize pass.
2. New Selection mode: mark a range, enhance just that range.
3. Low-confidence highlighting promoted to P0.
4. Dependency on the organize pass removed: Stage 1 chunks by silence gaps instead of sets.
5. Manual-first: auto-run after transcription is off by default and lives in Settings.
6. UX states are explicit: progress, partial failure, review, revert, and first-run education.

## Pipeline

transcribe with Deepgram or AssemblyAI whole-file -> Stage 0 local suspect detection -> user taps AI Enhance or marks a range -> Stage 1 text repair -> Stage 2 short-clip re-listen -> Cleaned view updates from versioned repair runs.

## Requirements

| Priority | Feature | Notes |
| --- | --- | --- |
| P0 | Low-confidence highlighting | Amber underlines/cards plus scrubber heat ticks; instant, local |
| P0 | AI Enhance auto mode | Stage 0 to 2; 40-clip cap; validators; raw immutable |
| P0 | Enhance Selection | Mark a range; 120s chunks; 15-minute soft cap |
| P0 | Progress system | Chip, banner, notification, summary, resume |
| P0 | Review sheet | Accept, revert, source, confidence, clip loop where available |
| P0 | Versioned repair runs | `repair_runs` and `repairs`; rerun-safe |
| P0 | Button rewire | AI button triggers Enhance; Organize moves to overflow |
| P1 | Cleaned text in search | Raw stays searchable; cleaned search can be added |
| P1 | Auto-run toggle | Off by default |
| P2 | Enhance stats | Fixes, accepted, reverted |

## Technical Decisions

| Area | Decision |
| --- | --- |
| Payload rule | Short clips only; never full recordings to Gemini |
| Thresholds | Flag 0.60, strong 0.45, merge gap 2.0s, pad 5s, max span 30s |
| Stage 1 chunking | Silence-gap segments merged to 10 to 15 min |
| Selection chunking | 120s clips, 2 per call |
| Output shape | Edit diffs; raw transcript is never rewritten |
| Validation | Deterministic local rejection before storage |
| Generation config | Low temperature, JSON schema, conservative prompts |
| Pacing | At least 6s between calls, retry/backoff via WorkManager, checkpoint per chunk |
| Cleaned timing | Edited spans keep span-level timing |
| Degradation | Non-Gemini providers run text repair only; re-listen requires Gemini |

## Acceptance Targets

1. At least half of contextually recoverable manual spot-check errors are fixed.
2. False-fix rate under 5%, measured by reverts.
3. Cleaned view matches or beats Fireflies on spot checks where the speech is recoverable.
4. A 10-minute Enhance Selection completes in under 2 minutes on Wi-Fi.
5. Zero mutations of raw transcripts across all test runs.

## Risks

- False fixes are worse than honest gaps. Mitigation: diff-only output, conservative prompts, validators, review and revert.
- Clip privacy: short snippets go to Gemini. Mitigation: explicit Settings toggle/skip AI behavior and documentation.
- Quota friction: mitigate with pacing, WorkManager retry, checkpointed runs, and Resume.
- Recovery ceiling: some speech is genuinely unrecoverable. Debrief should mark `[inaudible]` rather than invent text.

## Prompt Skeletons

Stage 1 text repair: conservative ASR transcript repair. Only correct marked spans when context clearly justifies the correction. Return JSON edits only.

Stage 2 audio re-listen: transcribe short marked clips exactly. If unclear, return an inaudible verdict instead of guessing.

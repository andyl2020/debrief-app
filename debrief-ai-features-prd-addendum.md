# Debrief AI Features: PRD Addendum

|  |  |
| --- | --- |
| **Status** | v1.1 final: Gemini confirmed, build-ready |
| **Date** | July 6, 2026 |
| **Scope** | Additions only; extends debrief-prd.md v0.2, which is unchanged |
| **Owner** | Andy Luu |

---

## TL;DR

Three new AI features: intelligent set detection (splitting a long recording into its distinct conversations), intelligent speaker identification, and intelligent rename. All three run on the finished transcript, not the audio, so no custom ML is needed: one LLM handles everything in a single automatic pass after transcription. Confirmed model: Gemini 2.5 Flash on the Google AI Studio free tier ($0, no card). Andy's usage is a rounding error against free quotas (roughly 10 short calls a month against a daily allowance in the hundreds), no vendor is hard-coded into the app, and the absolute worst case (every free tier on the market dies) prices out at under about $15 total over two years.

---

## Model Decision

Primary: Gemini 2.5 Flash via the Google AI Studio free tier.

Why this model:

1. $0 and no credit card. Free-tier limits as of mid-2026 (~15 requests/minute, ~1,500/day) are far beyond the 1–4 calls a recording needs.
2. 1M-token context window: an entire 7-hour transcript (~80k tokens) fits in one call, so no chunking logic has to be built or maintained.
3. Stable model. Preview models (Gemini 3.x) have tighter, frequently changing limits and are avoided.

### Will it stay free long enough?

The requirement is roughly two years of 7-hour transcripts with simple text summarization on top. Three layers of protection:

1. Headroom: the workload is tiny relative to quotas.

| Measure | Debrief's usage | Free-tier allowance |
| --- | --- | --- |
| **Calls** | ~10 per month (4–6 recordings, one combined call each, plus retries) | 250–1,500 per day, depending on quota era |
| **Tokens per call** | ~80k in, ~3k out | 250k+ tokens per minute |
| **Two-year total** | ~250–300 calls, ~25M tokens | Renews daily, forever |

Even on the harshest quota floor Google has ever set for Flash (the December 2025 cuts), this usage sits under 1% of a single day's allowance.

1. Track record: Google has kept a free tier on Flash models continuously since the Gemini API launched, through multiple pricing changes, because it is their developer acquisition funnel. Not a guarantee, which is why layer 3 exists.
2. The hedge: no vendor is load-bearing. The LLM layer is a plain HTTP client, and Settings accepts three shapes of provider: a Gemini key (default), any OpenAI-compatible endpoint (base URL + key + model name, which covers Groq-style free hosts of open models and future free tiers that do not exist yet), and Claude Haiku (~10 cents per 7-hour recording, no training on API data). If every free tier on the market died tomorrow, two years of this workload at paid Flash rates totals roughly $10.

> Privacy caveat: on the Gemini free tier, Google may use inputs and outputs to improve its models. Only transcript text ever leaves the device for this pass, never audio. Mitigations: a per-recording Skip AI Pass toggle for sensitive sessions, and the one-toggle provider switch.

---

## Key Setup (What Andy Does)

Yes: the Gemini key works exactly like the Deepgram key. Get it once, paste it into Settings, done. Both keys live side by side on the same Settings screen.

| Key | Where | Steps |
| --- | --- | --- |
| **Deepgram** (transcription) | console.deepgram.com | Sign up (no card) → Create API Key → copy → paste into Settings → Transcription |
| **Gemini** (AI pass) | aistudio.google.com | Sign in with your Google account → Get API key → Create API key → copy → paste into Settings → AI Pass |

Storage: both keys are stored identically: encrypted on-device with the Android Keystore, displayed masked, never backed up, synced, or exported, and only ever transmitted inside the API calls themselves. If you reinstall the app or change phones, you paste them again (both are free to regenerate in about a minute).

---

## Context: What & Why

What: after transcription completes, an AI pass reads the transcript and (1) splits the recording into sets, (2) names the speakers, (3) writes short summaries, and (4) renames the file to something meaningful.

Why: a 5-hour file named 2026-07-04_193302.m4a full of Speaker A/B labels is still work to navigate. These features finish the job: open the app and see named conversations with named people, then jump straight to the one you want.

---

## How Each Feature Works

### Set detection

1. Heuristic first: the app computes silence gaps between consecutive utterances using the word timestamps it already has. Any gap over the threshold (default 3 minutes, configurable 1–10) marks a candidate boundary. Zero cost, deterministic.
2. LLM refinement: the model reviews the text around each candidate boundary plus the speaker roster. It merges false splits (a quiet stretch mid-conversation) and adds missed ones (a new conversation that started without a long gap: new voices, fresh greetings, topic reset).
3. Sets are chapters within the recording, non-destructive: audio files are never split. Library rows expand to show sets, each with its own name, speakers, and summary. Manual merge/split controls cover corrections.

### Speaker identification

1. Diarization (already in v0.2) yields Speaker A/B/C per set.
2. The LLM scans each set for name evidence: introductions, third-person references, vocatives ("so, Mark...").
3. Names stated explicitly in the set are applied automatically. Inferred or uncertain names appear as suggestions with one-tap confirm. Speakers with no evidence keep a neutral label.
4. Andy is auto-tagged as the voice present across every set in the recording.

### Summaries and intelligent rename

1. The LLM writes a 1–2 line summary per set plus a rollup for the whole recording. Both are stored locally and indexed for search.
2. From the rollup it generates a filename in the pattern date, set count, key people or moments. Example: 2026-07-04 - 3 sets - rooftop w Jess, patio w Mark.
3. The physical file is renamed on disk, with undo. The app tracks recordings by internal ID so renames never break anything, and the original filename is preserved in the sidecar JSON.

### Pipeline order

transcribe → gap heuristic → LLM pass (boundaries, speakers, summaries, filename) → rename file.

The pass runs automatically when transcription completes. A Re-run AI Pass button covers corrections, threshold changes, or a model switch. The whole pass is typically one combined structured-output call per recording.

---

## Additional Feature Requirements

| Priority | Feature | Notes |
| --- | --- | --- |
| **P1** | Set detection | Gap heuristic + LLM refinement; chapters, not file splits; manual merge/split |
| **P1** | Speaker identification | Explicit names auto-apply; uncertain names are one-tap suggestions; Andy auto-tagged |
| **P1** | Summaries | Per set and per recording; stored locally; searchable |
| **P1** | Intelligent rename | Physical rename on disk with undo; original name kept in sidecar |
| **P1** | AI pass controls | Auto-run toggle, Re-run button, per-recording Skip AI Pass |
| **P1** | Provider flexibility | Gemini key, any OpenAI-compatible endpoint, or Claude Haiku, all switchable in Settings |
| **P2** | Custom rename template, per-set export | Later |

---

## Additional Technical Decisions

| Area | Decision | Why |
| --- | --- | --- |
| **LLM provider** | Gemini 2.5 Flash, Google AI Studio free tier | $0; 1M context fits a full transcript in one call |
| **Rate-limit fallback** | Flash-Lite, then queue and retry later | Daily quotas reset; post-processing is never urgent |
| **Provider abstraction** | Plain HTTP client; Settings accepts a Gemini key or any OpenAI-compatible base URL + key + model name | Future free tiers slot in with zero app changes; no vendor is load-bearing |
| **Switch path** | Claude Haiku behind the same interface | ~10 cents per recording; no training on API data |
| **Key storage** | Android Keystore encryption, on-device only, masked in UI, never exported | Identical handling for Deepgram and Gemini keys |
| **Prompting** | One combined call returning structured JSON (boundaries, speaker map, summaries, filename) | Fewer calls, atomic result, trivial retries |
| **Gap threshold** | 3 minutes default, configurable 1–10 | Matches natural breaks between conversations |
| **Confidence rule** | Explicit in-transcript names auto-apply; inferred names need one tap | A wrong name propagating is worse than one tap |
| **Privacy posture** | Transcript text only leaves the device for this pass, never audio | Free-tier data may be used by Google to improve models |

---

## Action Items

| # | Owner | Item | Effort |
| --- | --- | --- | --- |
| **A1** | Andy | Get the Gemini key per the Key Setup section above and paste it into Settings | 5 min |
| **A2** | Andy | Nothing else now; only if switching later, paste an Anthropic key or any OpenAI-compatible endpoint details | 0 |
| **A3** | Build | LLM provider abstraction (Gemini native + OpenAI-compatible), structured-output schema | Weekend 5 |
| **A4** | Build | Gap heuristic, chapter data model, expandable library UI | Weekend 5 |
| **A5** | Build | Speaker apply/suggest flow, rename with undo, Keystore-backed key storage | Weekend 5 |
| **A6** | Andy | Sanity-check the AI pass on 1–2 real recordings; tune the gap threshold | 30 min |

---

## Timeline Addition

| Window | Work | Output |
| --- | --- | --- |
| **Weekend 5** (~Aug 8–9) | AI pass end to end | Recordings auto-split into named sets, speakers named, files renamed |

Placeholder date; follows Weekend 4 from PRD v0.2.

---

## Risks (Additions)

- Free-tier data use: Google may use free-tier inputs and outputs to improve its models. Text only, never audio; the Skip toggle and the provider switch are the outs.
- Wrong speaker names: a hallucinated or misheard name would propagate confusion. The confidence rule keeps uncertain names behind a tap, and renames are always editable.
- Boundary errors: loud venues blur set edges. Manual merge/split plus a tunable threshold keep corrections cheap.
- Free-tier drift: Google cut quotas in December 2025 and removed Pro models from the free tier in April 2026; limits can change again. Debrief's usage sits under 1% of even the harshest historical quota, and the OpenAI-compatible endpoint option means any future free tier slots in without an app update.

Blockers: none beyond action item A1.

---

## Terminology (Additions)

| Term | Meaning |
| --- | --- |
| **AI pass** | The single post-transcription step that detects sets, names speakers, summarizes, and renames |
| **Set** | One distinct conversation within a recording, shown as a chapter |
| **Structured output** | The LLM returns machine-readable JSON instead of prose, so results apply automatically |
| **OpenAI-compatible endpoint** | A common API shape many providers speak; lets the app point at new or free providers by changing Settings, not code |


# Debrief Share Sets: PRD Addendum

|  |  |
| --- | --- |
| **Status** | Approved; implementation in progress |
| **Date** | August 3, 2026 |
| **Scope** | Expiring, privacy-safe sharing of completed manual sets; extends the Debrief PRD; targets Debrief v1.11.0 |
| **Owner** | Andy Luu |

---

## TL;DR

Debrief will let Andy select one or more completed manual sets from one recording and create an unlisted link that expires after 30, 60, or 90 days. A recipient sees only the selected sets' derived audio, transcript, and every comment inside those sets. The original recording, unselected gaps, out-of-set text, out-of-set comments, bookmarks, and reversible source audio never leave the phone.

Sharing is snapshot-based and read-only. Stored redactions are always rendered irreversibly into the temporary share copy: shared text contains `[redacted]` and the corresponding shared audio is silent. The original local recording remains unchanged. There is no recipient download action, no recipient commenting, no analytics, and no cross-recording collection in v1.11.0.

The service uses a private Cloudflare R2 bucket for derived audio, D1 for metadata, a Worker for the API and mobile-first viewer, and a `workers.dev` address initially. Settings shows current share storage against the current 10 GB-month R2 Standard free-tier reference, and a Shared Links page manages active links, exact expiry dates, per-link sizes, extension, copy/share, and immediate revocation.

## Approved Product Decisions

1. One share can contain up to ten completed sets and three hours of combined audio.
2. Every selected set in one share must belong to the same original recording.
3. Multiple sets remain separate sections and audio players on one recipient page; each displayed clip starts at `0:00`.
4. Every existing comment whose timestamp falls inside a selected set is included. There is no individual comment-exclusion control.
5. Recipients cannot add comments, edit content, or upload files in this release.
6. Shares are immutable snapshots. Later local edits do not silently modify an existing link.
7. A user may deliberately replace a share by creating a new snapshot and revoking the old link.
8. Expiry choices are 30, 60, and 90 days. The default is 30 days.
9. Active shares can be extended to a new 30-, 60-, or 90-day period and revoked immediately.
10. Links are long, cryptographically random bearer links. Optional PIN support is available but off by default.
11. The recipient viewer has no Download button. Debrief does not claim that browser media is impossible to save or screen-record.
12. Every stored redaction is always applied to the shared copy, even if the local shield is currently off.
13. The recipient never receives the full source recording or a client-side reversible mute instruction.
14. Cloud storage UI uses the full current 10 GB R2 Standard free-tier reference for consistency; there is no separate 8 GB Debrief cap.
15. No permanent links, public directory, recipient accounts, view counts, or recipient analytics ship in v1.11.0.

## User Story

After transcribing, labelling, commenting, defining manual sets, and optionally adding redactions, Andy can select one or more completed sets and create an expiring link. A coach or community member can open that link on a phone without installing Debrief and play only the shared audio while reading the matching transcript and comments. Private material outside the selected sets and inside redactions is not recoverable from the shared payload.

## Terminology

| Term | Meaning |
| --- | --- |
| **Completed set** | A manual set with valid start and end timestamps where `endMs > startMs` |
| **Share snapshot** | Immutable transcript, comment, speaker, redaction, and clip data captured when Create link is tapped |
| **Derived clip** | Temporary on-device audio containing only one selected set, with stored redactions permanently silenced |
| **Bearer link** | An unguessable URL that grants read access to anyone who possesses it |
| **Owner credential** | Revocable device credential stored in Android Keystore and used only for create/manage APIs |
| **Current storage** | Bytes currently stored for Debrief share objects in R2 |
| **GB-month** | Cloudflare's monthly storage billing unit: average daily peak storage over the billing period |

## Sharing UX

### Entry and selection

1. Open a recording's Chapters drawer.
2. Tap **Select** in the Sets section.
3. Check one or more completed sets from that recording.
4. Open/incomplete sets are disabled with an explanation that a set end must be added first.
5. Tap the contextual **Share** action.

Selection state is local UI state until the review screen saves a draft. Leaving selection mode does not upload anything.

### Full-screen Share Review

The review surface is a full screen, not a bottom sheet. It contains:

- Editable share title, defaulted from the recording/set names without exposing the original filename automatically.
- Set cards in chronological order with title, duration, transcript word count, comment count, and resulting clip size estimate.
- Expandable exact transcript and comment preview.
- A privacy summary: number of redactions that will be permanently applied, confirmation that no source file or unselected gaps will upload, and a blocking warning if redaction rendering cannot be verified.
- Expiry chips: **30 days** (default), **60 days**, **90 days**.
- Optional PIN toggle and PIN entry when enabled.
- Combined duration and estimated upload size.
- **Create link** primary action.

The Create link action is disabled when there are no completed sets, more than ten sets, more than three hours of selected audio, missing transcript data, unreadable source audio, or an unresolved privacy-processing error.

### Preparation and upload

- A WorkManager foreground job creates and uploads one derived clip per selected set.
- The screen and recording row show determinate progress: `Preparing set 2 of 4`, `Applying redactions`, `Uploading 38 MB of 74 MB`, or `Finalizing link`.
- Work checkpoints after each clip and upload part. App switching, process death, network loss, or device restart does not discard completed work.
- Cancel stops future work, invalidates upload URLs, and deletes local/cloud staging objects.
- The public link is not activated until every clip and metadata object is committed and verified.
- Partial failure retains the resumable draft and offers **Resume** or **Delete draft** with a specific error.

### Share created

On success Debrief displays Copy link, Android share, Open preview, Manage link, and the exact expiry date/days remaining.

### Shared Links management

Settings > **Cloud sharing** opens a dedicated Shared Links page.

The summary shows current Debrief share storage in R2, a progress bar against the current 10 GB-month free-tier reference, a plain-language note that current bytes and monthly GB-month consumption are related but not identical, active-link count, and last successful refresh time. If usage cannot refresh, the last known value remains labelled with its timestamp.

Active links are ordered by soonest expiry. Each item shows title, included sets, created date, exact expiry/days remaining, chosen duration, total audio duration, cloud size, and Copy, Android Share, Open, Extend, and Revoke actions.

Revocation requires confirmation, immediately denies access, schedules cloud-object deletion, and updates the meter after deletion is confirmed. Expired/revoked cloud data is not restorable. A minimal local history record may retain title, dates, size, and terminal state without retaining a working token.

## Recipient Web Experience

The link opens a responsive, accessible, read-only webpage with no account requirement.

- Share title and exact expiry date.
- One card per selected set, in selected chronological order.
- One audio player per set beginning at `0:00`.
- Transcript segments and speaker labels relative to that clip.
- All in-set comments placed chronologically at their relative clip timestamps.
- Tapping a transcript segment or comment seeks its player to that point.
- `[redacted]` replaces protected text; corresponding derived audio contains silence.
- No navigation to the original recording timeline, other recordings, unselected gaps, or app data.
- No Download button, recipient edit controls, recipient comments, tracking pixels, third-party analytics, or external fonts/scripts.
- `noindex`, strict Content Security Policy, and `Referrer-Policy: no-referrer` headers.
- Expired/revoked links show a neutral unavailable page and never reveal share metadata.

## Privacy and Non-Leakage Contract

The following is a release-blocking contract:

1. Clip boundaries are derived locally from selected completed-set timestamps.
2. Only transcript segments/words intersecting those boundaries are copied, then clamped to the selected interval.
3. Only comments with `set.startMs <= timestampMs <= set.endMs` are copied.
4. Original recording URI, filename, folder, unrelated metadata, and recording ID are never placed in public payloads.
5. Raw source audio is never uploaded, including as a staging convenience.
6. Stored redactions overlapping a selected set are padded with Debrief's privacy window, clamped, merged, and rendered as silence into the derived audio.
7. Shared transcript content covered by those ranges is replaced by `[redacted]` before upload.
8. A redacted share is not activated unless the encoded output is probed and the expected silent windows are verified.
9. The server rejects metadata that references missing objects, mixed recordings, invalid boundaries, excessive limits, or mismatched byte hashes.
10. Staging objects are private and expire automatically if a client disappears.
11. Logs never contain bearer tokens, PINs, transcript text, comments, or signed upload URLs.

The original local recording, transcript, comments, sets, and redactions remain unchanged.

## Audio Processing

- When no redactions overlap a set, use `MediaExtractor` plus `MediaMuxer` sample copy where the source format supports a safe clip. This avoids quality loss.
- When redactions overlap, use AndroidX Media3 Transformer with a deterministic audio processor that replaces padded ranges with zero-valued PCM, then encode AAC/M4A at source-compatible speech quality.
- Use the established 750 ms leading and 250 ms trailing privacy padding, clamped to clip bounds and merged for overlaps.
- Probe every output for readable duration, an audio track, expected bounds, and nonzero size.
- Delete temporary clear derived files immediately after verified upload or cancellation. Never write them into the user-selected recordings folder.
- Prepare clips sequentially by default to limit temporary disk usage. Upload and delete each verified local clip before preparing the next where retry safety allows.

## Cloud Architecture

| Component | Responsibility |
| --- | --- |
| Android app | Selection, exact snapshot, redaction rendering, clip probing, resumable upload, local management state |
| Cloudflare Worker | Owner API, public viewer/API, token/PIN checks, byte-range audio responses, expiry/revocation enforcement |
| Private R2 Standard bucket | Derived set audio and non-public staged objects only |
| D1 | Shares, selected-set metadata, transcript, comments, object manifests, expiry, revocation, upload checkpoints |
| Cron Trigger | Expiry reconciliation and deletion; R2 lifecycle is a redundant staging/deletion backstop |
| `workers.dev` | Initial public address; custom domain remains optional |

### Authentication

- No Cloudflare credential or reusable deployment secret is embedded in the APK.
- Infrastructure provisioning generates a one-time pairing code.
- The Android app exchanges it for a revocable random owner credential and stores that credential in Android Keystore.
- The server stores only a verifier/hash and supports credential rotation/revocation.
- Reinstall or a new device requires pairing again; public shares remain manageable after re-pairing.

### Public-link security

- Use at least 256 bits of cryptographic randomness for the bearer secret.
- Store a keyed verifier/hash, not the raw bearer secret, in D1.
- Return the raw link exactly once on creation and retain it only in encrypted app storage while active.
- Optional PINs are rate-limited and stored using a slow password hash with a per-share salt.
- Audio is served through the Worker with `Range` support after share validation; the R2 bucket is never public.
- Expiry and revocation checks are fail-closed.

### Upload protocol

1. `POST /v1/owner/share-drafts` validates limits and returns a draft ID plus short-lived direct-upload instructions.
2. Android prepares each clip, computes SHA-256 and byte length, and uploads using short-lived presigned R2 operations or multipart upload for larger objects.
3. `POST /v1/owner/share-drafts/{id}/parts/{setId}/complete` records object integrity and checkpoint state.
4. `POST /v1/owner/share-drafts/{id}/publish` transactionally validates every object and activates the snapshot.
5. Failed/incomplete drafts remain private and are automatically purged.

The Worker receives metadata rather than proxying large upload bodies. This avoids the Workers request-body ceiling and enables reliable multipart resume.

### API contracts

| Method | Route | Purpose |
| --- | --- | --- |
| `POST` | `/v1/pair` | Exchange one-time pairing code for owner credential |
| `GET` | `/v1/owner/usage` | Current storage, free-tier reference, active count, measurement timestamp |
| `GET` | `/v1/owner/shares` | List active/manageable shares |
| `POST` | `/v1/owner/share-drafts` | Create validated private upload draft |
| `POST` | `/v1/owner/share-drafts/{id}/upload` | Obtain/refresh short-lived upload instructions |
| `POST` | `/v1/owner/share-drafts/{id}/parts/{setId}/complete` | Verify and checkpoint an uploaded clip |
| `POST` | `/v1/owner/share-drafts/{id}/publish` | Atomically activate complete share |
| `DELETE` | `/v1/owner/share-drafts/{id}` | Cancel and purge draft |
| `POST` | `/v1/owner/shares/{id}/extend` | Set a new approved expiry period |
| `DELETE` | `/v1/owner/shares/{id}` | Revoke immediately and delete objects |
| `GET` | `/s/{bearer}` | Mobile-first share viewer shell |
| `GET` | `/v1/public/{bearer}` | Validated public snapshot JSON |
| `GET` | `/v1/public/{bearer}/sets/{setPublicId}/audio` | Validated byte-range audio stream |
| `POST` | `/v1/public/{bearer}/unlock` | Optional PIN exchange for short-lived same-site session |

## Cloud Storage Meter

Cloudflare currently grants 10 GB-month of R2 Standard storage per month, calculated from average daily peak storage. The UI uses 10 GB as the consistent visual reference requested by the owner and clearly explains that it is not a fixed disk quota.

The backend reports exact current Debrief object bytes, reconciled R2 bucket bytes/object count, estimated consumed/projected GB-month when available, and measurement source/time/staleness. The app displays current bytes against 10 GB prominently and a secondary monthly estimate.

There is no 8 GB hard cap. Warning states occur at 80%, 90%, and 100%. At or above the current free-tier reference, Create link requires an explicit potential-charge confirmation and never implies that the operation is free.

Provider allowances remain server-configurable. The v1.11.0 visual default is 10,000,000,000 bytes for Standard R2 storage.

## Data Model

### Android Room

- `share_drafts`: recording ID, selected set IDs, title, expiry option, PIN flag, stage, progress, retry/error state, timestamps.
- `shared_links`: server share ID, encrypted bearer URL/token material, title, status, created/expiry/revoked timestamps, set count, duration, size, last sync.
- `share_parts`: draft ID, set ID, source bounds, local temporary state, byte size, SHA-256, upload checkpoints, preparation/upload status.

Cloud share records are keyed independently from recording foreign-key deletion so the management page can still revoke a link if the local recording is removed.

### D1

- `owner_devices`
- `pairing_codes`
- `share_drafts`
- `shares`
- `share_sets`
- `share_segments`
- `share_comments`
- `share_objects`
- PIN rate-limit state

All public IDs are server-generated and unrelated to local Room IDs.

## Expiry and Deletion

- The Worker denies a share at the exact expiry timestamp before returning metadata or bytes.
- Revocation changes state before object deletion begins.
- Scheduled cleanup deletes R2 objects and private D1 content, with R2 lifecycle rules purging abandoned staging prefixes as a backstop.
- Cleanup is idempotent and retries safely.
- Unknown, malformed, expired, and revoked tokens use the same neutral response.

## Error and Interruption Handling

| Failure | Required behavior |
| --- | --- |
| No paired cloud service | Explain setup; never crash or begin preparation |
| Open/invalid set | Disable selection and identify the missing end marker |
| Missing transcript | Block share and offer transcription navigation |
| Source file unavailable | Keep draft metadata; request file-access repair |
| Insufficient local space | Do not start next clip; preserve uploaded checkpoints |
| Audio transformation failure | Never fall back to raw audio; fail closed with retry/delete |
| Redaction verification failure | Never publish; preserve source and report privacy failure |
| Network loss | WorkManager retries with backoff; completed parts remain checkpointed |
| Presigned URL expired | Refresh only that upload instruction and resume |
| App/process/device restart | Reconstruct work from Room and WorkManager unique work |
| Server publish conflict | Treat idempotent success as success; otherwise reconcile manifest |
| Usage unavailable | Show last-known/stale usage; never invent zero usage |
| Link expired/revoked | Deny metadata and audio identically |

## Current Provider Reference

Planning reference as of August 3, 2026:

- R2 Standard: 10 GB-month/month, 1 million Class A operations/month, 10 million Class B operations/month, free direct R2 egress.
- Workers Free: 100,000 requests/day.
- D1 Free: 5 GB storage, 5 million rows read/day, 100,000 rows written/day.
- R2 activation requires a Cloudflare account and R2 subscription checkout even when usage remains within included allowances.

Authoritative references:

- https://developers.cloudflare.com/r2/pricing/
- https://developers.cloudflare.com/r2/get-started/
- https://developers.cloudflare.com/workers/platform/pricing/
- https://developers.cloudflare.com/r2/platform/metrics-analytics/
- https://developers.cloudflare.com/r2/buckets/object-lifecycles/
- https://developers.cloudflare.com/r2/api/s3/presigned-urls/

Free allowances can change. Release notes must document the current observed setup.

## Testing Requirements

### Local and Android

- Set eligibility, same-recording enforcement, ten-set/three-hour limits.
- Exact transcript/word/comment boundary filtering and relative timestamps.
- Redaction intersection, clamp, merge, privacy padding, text substitution, and silent audio output.
- Snapshot immutability, size aggregation, expiry, meter formatting, and idempotent resume.
- Chapters selection, Review, process recreation, interrupted/cancelled work, Settings meter, Shared Links actions, and database migration.
- Android 11 and Android 15/16 KB compatibility, including the OnePlus 13 target.

### Backend

- D1 migrations and transactional publish.
- Pairing, credential rotation/revocation, token verification, optional PIN throttling.
- Mixed-recording/boundary/duration/count/size/hash/incomplete-manifest rejection.
- Private drafts cannot be read publicly.
- Exact expiry/revoke denial for HTML, JSON, and every audio byte-range request.
- Range correctness, idempotent completion/publish/revoke/cleanup, and security headers.

### Privacy leakage suite

Use synthetic audio with identifiable tones/phrases before, inside, and after selected sets plus tones inside redaction windows. Assert:

1. Uploaded clips contain no material before or after each selected set.
2. Redacted windows are silent for the complete padded interval.
3. Public data contains no unselected text/comments, local IDs, filenames, paths, or raw redaction text.
4. Public endpoints cannot enumerate shares or derive adjacent object keys.
5. Browser-playback bytes contain only the permanently redacted derived clip.

### End-to-end acceptance

1. Create a multi-set share from a real local fixture recording.
2. Interrupt preparation, upload, and finalization separately; resume each.
3. Open without authentication on Android and desktop browsers.
4. Verify separate players, transcript seek, all in-set comments, and redacted audio/text.
5. Verify original local data is unchanged.
6. Extend expiry, then revoke and verify immediate denial of metadata and range requests.
7. Confirm cleanup and storage-meter decrease.
8. Confirm no Download control exists.

## Acceptance Criteria

1. A valid one-to-ten-set snapshot from one recording creates a usable link through resumable background processing.
2. The recipient sees and hears only selected sets, their transcript, and every in-range comment.
3. Zero raw source recordings or unselected audio/text upload in success or failure paths.
4. Stored redactions are irreversible in shared payloads and local source remains unchanged.
5. Expiry and revoke protect HTML, JSON, and range media requests.
6. No partial share becomes public.
7. Interrupted work resumes without repeating completed clip work or losing context.
8. Settings accurately labels current/stale storage, shows the 10 GB reference, and lists per-link sizes/dates.
9. Upgrade preserves all existing recordings and annotations.
10. Backend, JVM, Android, privacy, release, signing, upgrade, and public-artifact checks pass or incomplete external verification is documented honestly.

## Out of Scope for v1.11.0

- Sets from multiple recordings in one link.
- Recipient comments, reactions, edits, accounts, uploads, or notifications.
- Permanent links, discovery, indexing, view counts, analytics, or tracking.
- A promise that browser media cannot technically be saved or recorded.
- Original-recording upload or full database synchronization.
- Live mutation of an existing share from local edits.
- iOS share creation; the web viewer remains usable from iOS browsers.

## Implementation Checkpoints

This checklist is the authoritative resume point if work is interrupted. Update it with commit hashes and verification results before every push.

- [x] Product questions resolved and PRD addendum written.
- [x] Stage 1: Cloudflare skeleton, D1 schema, API contracts, security middleware, and backend tests. Commit: `fc9a232`; `npm run check` passes with 7 Worker integration tests.
- [x] Stage 2: Private R2 upload/publish, range streaming, expiry/revoke/cleanup, storage metrics, and viewer. Wrangler dry-run passes at 50.26 KiB raw/14.04 KiB gzip.
- [x] Stage 3: Android Room share state/migration, pairing client, repository, and resumable upload worker. Room schema 6 is exported; Android JVM tests and Kotlin compilation pass. Commit: this checkpoint.
- [x] Stage 4: Set-package builder, exact filtering, clip extraction, permanent redaction rendering, and leakage tests. Boundary, fail-closed untimed text, redacted-word leakage, and PCM silence tests pass.
- [x] Stage 5: Chapters set selection, full-screen review, progress/resume, success actions, and errors. Only completed manual sets are selectable; one link retains all selected sets.
- [x] Stage 6: Settings storage card and Shared Links management page. The full 10 GB reference, monthly GB-month explanation, active/resumable state, copy/share/open, extend, revoke, and history are implemented.
- [ ] Stage 7: Full backend/Android/device/privacy regression and recovery tests.
- [ ] Stage 8: Release notes/status/version, signed build, Cloudflare deployment, tag, GitHub release, and public verification.

## Resume Protocol

1. Read this file and `IMPLEMENTATION_STATUS.md`.
2. Run `git status --short --branch` and `git log -8 --oneline --decorate`.
3. Continue the first unchecked implementation checkpoint. Stages 7 and 8 require deployed Cloudflare resources before end-to-end public-link verification.
4. Never skip the privacy leakage contract to make a share appear functional.
5. Before stopping, update this checklist and `IMPLEMENTATION_STATUS.md` with exact work, tests, commit, blocker, and next action.
6. Commit and push each coherent stage. Do not tag or describe v1.11.0 as released until the public APK and deployed service are independently verified.

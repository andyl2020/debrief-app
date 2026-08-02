# Recording annotation durability

Status: implemented for Debrief v1.10.0  
Updated: August 1, 2026

This document is the persistence contract for manual sets/chapters,
comments/bookmarks, reversible redactions, and speaker names.

## Identity and authoritative storage

Room/SQLCipher is authoritative while Debrief is installed. Every annotation
row contains the stable `recordingId`; manual sets, comments, and redactions also
have timestamp positions on that recording's audio timeline. Retranscription's
transaction deletes and inserts only `transcript_segments` and
`transcript_words`. It does not replace recording or annotation rows.

Normal app upgrades keep the database and recording IDs. Physical rename keeps
the same database ID and refreshes the source URI/name. A folder scan upserts an
existing recording rather than replacing it.

## Redundant copies

Every annotation mutation checkpoints three layers in this order:

1. SQLCipher transaction/update: the user's requested change becomes
   authoritative immediately.
2. Encrypted app-private snapshot: a compact annotation-only JSON document is
   encrypted with AES-256-GCM using a random key protected by Android Keystore.
   It is written to a temporary file, decrypted/decoded for verification, then
   atomically moved into place. The previous valid file is retained.
3. Recording-folder sidecars: the full transcript and annotations are written
   to both `<audio>.debrief.backup.json` and `<audio>.debrief.json`; each copy is
   parsed and checked against recording size/duration after writing.

Sidecar writes are serialized to prevent concurrent comment, set, AI, or worker
updates from interleaving. Sidecar schema v4 preserves word confidence as well
as transcript text, comments, redactions, aliases, sets, and AI metadata.

## Retranscription and rescan safety

Before transcription uploads audio or replaces transcript text, Debrief creates
and verifies the encrypted annotation snapshot. If that local checkpoint fails,
the worker stops before replacement. A folder-sidecar failure is recorded as a
backup-health warning but does not turn an otherwise complete transcription
into Failed.

Folder scan checks readability before pruning. For audio that appears missing,
Debrief checkpoints annotations to app-private storage before allowing Room's
foreign-key cascade. If that checkpoint fails, the recording row is retained.
When the same recording is rediscovered, Debrief reads the primary sidecar, then
the backup sidecar, then the encrypted app-private snapshot.

## UX contract

No success banner is shown during normal use. If a recording has user-authored
markers and recovery copies are stale, Review shows one actionable card:

- Local snapshot failure: data remains in SQLCipher; avoid Clear data and retry.
- Folder copy failure: SQLCipher and the encrypted local snapshot are current;
  re-link the folder or tap **Retry backup**.

## Limits

- Uninstall or **Clear data** removes SQLCipher, the Keystore key, and encrypted
  app-private snapshots. Recording-folder sidecars are the reinstall-safe copy.
- Sidecars are ordinary JSON and are not independently encrypted. Device/folder
  storage protection is responsible for their confidentiality.
- A file replaced with different audio under the same URI can leave marker
  timestamps semantically wrong. Retranscribing the same audio is safe because
  the audio timeline does not change.
- No local redundancy can survive simultaneous loss of app data and the linked
  recording folder.

## Regression coverage

- Recording upsert/rescan retains transcript, comments, redactions, manual sets,
  and speaker aliases.
- The snapshot-at-rest test confirms private marker phrases are absent from the
  encrypted payload.
- A recovery test deletes the recording row and cascaded annotations, recreates
  the same recording, and restores its bookmark, redaction, set, and speaker
  name from the encrypted snapshot.
- The complete Android instrumentation suite exercises set/comment CRUD,
  redactions, search, SQLCipher, recorder/service behavior, and launch state.

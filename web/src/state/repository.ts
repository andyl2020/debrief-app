import { IoError } from '../core/errors'
import type {
  Comment,
  ConversationSet,
  Recording,
  Redaction,
  ReviewBundle,
  SpeakerAlias,
  TranscriptQualityReport,
  TranscriptSegment,
  TranscriptWord,
} from '../core/models'
import { SearchIndex, type IndexedRow } from '../core/search'
import {
  buildSidecar,
  parseSidecar,
  serializeSidecar,
  sidecarToEntities,
} from '../core/sidecar'
import {
  STORES,
  deleteRecordingCascade,
  get,
  getAll,
  getAllByRecording,
  put,
  replaceForRecording,
} from '../storage/db'
import type { AudioSource, StorageAdapter } from '../storage/adapter'

/**
 * The web counterpart of `DebriefDao` + `SearchRepository` + `SidecarStore`.
 *
 * The important behaviour carried over from Android is that bookkeeping
 * failures must never destroy real work. Writing a comment succeeds even if the
 * search index or the sidecar write then fails; those are reported separately
 * and retried, rather than being allowed to fail the user's edit.
 */

export interface AnnotationBackupStatus {
  /** True when the sidecar beside the recording is up to date. */
  sidecarCurrent: boolean
  /** Populated when the last sidecar write failed, for the "Retry backup" card. */
  error: string | null
  protectedItemCount: number
}

export class Repository {
  readonly search = new SearchIndex()

  constructor(private storage: StorageAdapter) {}

  setStorage(storage: StorageAdapter): void {
    this.storage = storage
  }

  get adapter(): StorageAdapter {
    return this.storage
  }

  // --- recordings ---------------------------------------------------------

  async listRecordings(): Promise<Recording[]> {
    const recordings = await getAll<Recording>(STORES.recordings)
    return recordings.sort((a, b) => b.lastModified - a.lastModified)
  }

  async getRecording(id: string): Promise<Recording | null> {
    return (await get<Recording>(STORES.recordings, id)) ?? null
  }

  async saveRecording(recording: Recording): Promise<void> {
    await put(STORES.recordings, recording)
  }

  async updateRecording(id: string, changes: Partial<Recording>): Promise<Recording | null> {
    const current = await this.getRecording(id)
    if (!current) return null
    const updated = { ...current, ...changes }
    await put(STORES.recordings, updated)
    return updated
  }

  /** Mirrors `dao.updateStatus(recordingId, status, message)`. */
  async updateStatus(
    id: string,
    status: Recording['status'],
    errorMessage: string | null = null,
  ): Promise<void> {
    await this.updateRecording(id, { status, errorMessage })
  }

  async deleteRecording(id: string): Promise<void> {
    const recording = await this.getRecording(id)
    await deleteRecordingCascade(id)
    this.search.removeRecording(id)
    if (recording) {
      // Only browser storage owns the audio bytes; a linked folder is the
      // user's own directory and we never delete from it implicitly.
      if (this.storage.mode === 'browser-storage') {
        await this.storage.remove(recording.sourceKey).catch(() => undefined)
      }
    }
  }

  /**
   * Reconciles the storage adapter's file list with the database. New files
   * become NEW recordings; files that vanished are dropped, but only after
   * their annotations have been secured in a sidecar - the same ordering
   * Android uses so a rescan can never silently discard user-authored markers.
   */
  async rescan(): Promise<{ added: Recording[]; removed: string[] }> {
    const sources = await this.storage.list()
    const existing = await this.listRecordings()
    const byKey = new Map(existing.map((recording) => [recording.sourceKey, recording]))

    const added: Recording[] = []
    for (const source of sources) {
      if (byKey.has(source.key)) continue
      const recording = await this.importSource(source)
      added.push(recording)
    }

    const liveKeys = new Set(sources.map((source) => source.key))
    const removed: string[] = []
    for (const recording of existing) {
      if (liveKeys.has(recording.sourceKey)) continue
      await this.checkpointSidecar(recording.id).catch(() => undefined)
      await this.deleteRecording(recording.id)
      removed.push(recording.id)
    }

    return { added, removed }
  }

  /** Registers one audio source, adopting any sidecar sitting beside it. */
  async importSource(source: AudioSource): Promise<Recording> {
    const recording: Recording = {
      id: crypto.randomUUID(),
      sourceKey: source.key,
      displayName: source.name,
      mimeType: source.mimeType,
      sizeBytes: source.sizeBytes,
      lastModified: source.lastModified,
      durationMs: 0,
      status: 'NEW',
      errorMessage: null,
      playbackPositionMs: 0,
      discoveredAt: Date.now(),
      providerJobId: null,
      provider: null,
    }
    await this.saveRecording(recording)

    // An Android-written sidecar next to the file means this recording already
    // has a transcript and the user's markers. Adopting it is the whole point
    // of keeping the schema compatible.
    try {
      const raw = await this.storage.readSidecar(source.name)
      if (raw) await this.applySidecar(recording, raw)
    } catch {
      // A malformed or unreadable sidecar must not block importing the audio.
    }

    return recording
  }

  async applySidecar(recording: Recording, raw: string): Promise<void> {
    const document = parseSidecar(raw)
    const entities = sidecarToEntities(recording.id, document)
    await replaceForRecording(STORES.segments, recording.id, entities.segments)
    await replaceForRecording(STORES.words, recording.id, entities.words)
    await replaceForRecording(STORES.comments, recording.id, entities.comments)
    await replaceForRecording(STORES.redactions, recording.id, entities.redactions)
    await replaceForRecording(STORES.aliases, recording.id, entities.aliases)
    await replaceForRecording(STORES.sets, recording.id, entities.sets)
    if (entities.segments.length > 0) {
      await this.updateRecording(recording.id, {
        status: 'READY',
        durationMs: recording.durationMs || document.recordingDurationMs,
      })
    }
    await this.rebuildSearch(recording.id)
  }

  // --- transcript ---------------------------------------------------------

  async replaceTranscript(
    recordingId: string,
    segments: TranscriptSegment[],
    words: TranscriptWord[],
  ): Promise<void> {
    await replaceForRecording(STORES.segments, recordingId, segments)
    await replaceForRecording(STORES.words, recordingId, words)
  }

  async saveQualityReport(report: TranscriptQualityReport): Promise<void> {
    await put(STORES.quality, report)
  }

  async loadReview(recordingId: string): Promise<ReviewBundle | null> {
    const recording = await this.getRecording(recordingId)
    if (!recording) return null
    const [segments, words, comments, redactions, aliases, sets, qualityReport] = await Promise.all([
      getAllByRecording<TranscriptSegment>(STORES.segments, recordingId),
      getAllByRecording<TranscriptWord>(STORES.words, recordingId),
      getAllByRecording<Comment>(STORES.comments, recordingId),
      getAllByRecording<Redaction>(STORES.redactions, recordingId),
      getAllByRecording<SpeakerAlias>(STORES.aliases, recordingId),
      getAllByRecording<ConversationSet>(STORES.sets, recordingId),
      get<TranscriptQualityReport>(STORES.quality, recordingId),
    ])

    return {
      recording,
      segments: segments.sort((a, b) => a.startMs - b.startMs),
      words: words.sort((a, b) => a.startMs - b.startMs),
      comments: comments.sort((a, b) => a.timestampMs - b.timestampMs),
      redactions: redactions.sort((a, b) => a.startMs - b.startMs),
      aliases,
      sets: sets.sort((a, b) => a.orderIndex - b.orderIndex || a.startMs - b.startMs),
      qualityReport: qualityReport ?? null,
    }
  }

  // --- annotations --------------------------------------------------------

  async setComments(recordingId: string, comments: Comment[]): Promise<void> {
    await replaceForRecording(STORES.comments, recordingId, comments)
  }

  async setRedactions(recordingId: string, redactions: Redaction[]): Promise<void> {
    await replaceForRecording(STORES.redactions, recordingId, redactions)
  }

  async setAliases(recordingId: string, aliases: SpeakerAlias[]): Promise<void> {
    await replaceForRecording(STORES.aliases, recordingId, aliases)
  }

  async setSets(recordingId: string, sets: ConversationSet[]): Promise<void> {
    await replaceForRecording(STORES.sets, recordingId, sets)
  }

  // --- derived data -------------------------------------------------------

  /** Mirrors `SearchRepository.rebuild` - drop and re-add every row for one recording. */
  async rebuildSearch(recordingId: string): Promise<void> {
    const bundle = await this.loadReview(recordingId)
    if (!bundle) return
    const { recording } = bundle

    const rows: IndexedRow[] = [
      {
        recordingId,
        recordingName: recording.displayName,
        speakerId: '',
        timestampMs: 0,
        body: recording.displayName,
        kind: 'filename',
      },
      ...bundle.segments.map((segment): IndexedRow => ({
        recordingId,
        recordingName: recording.displayName,
        speakerId: segment.speakerId,
        timestampMs: segment.startMs,
        body: segment.text,
        kind: 'transcript',
      })),
      ...bundle.comments.map((comment): IndexedRow => ({
        recordingId,
        recordingName: recording.displayName,
        speakerId: '',
        timestampMs: comment.timestampMs,
        body: comment.text,
        kind: 'comment',
      })),
      ...bundle.sets
        .map((set) => ({ set, body: [set.title, set.summary].filter((p) => p.trim()).join('. ') }))
        .filter(({ body }) => body.length > 0)
        .map(({ set, body }): IndexedRow => ({
          recordingId,
          recordingName: recording.displayName,
          speakerId: '',
          timestampMs: set.startMs,
          body,
          kind: 'summary',
        })),
    ]

    this.search.replaceRecording(recordingId, rows)
  }

  async rebuildAllSearch(): Promise<void> {
    this.search.clear()
    for (const recording of await this.listRecordings()) {
      await this.rebuildSearch(recording.id)
    }
  }

  /**
   * Writes the sidecar beside the recording. Reports failure instead of
   * throwing at the caller's expense, because a sidecar problem must never turn
   * a completed transcription or a saved comment into a failure.
   */
  async checkpointSidecar(recordingId: string): Promise<AnnotationBackupStatus> {
    const bundle = await this.loadReview(recordingId)
    if (!bundle) {
      return { sidecarCurrent: false, error: 'Recording is no longer available.', protectedItemCount: 0 }
    }

    const protectedItemCount =
      bundle.comments.length + bundle.redactions.length + bundle.sets.length + bundle.aliases.length

    try {
      const document = buildSidecar({
        recording: bundle.recording,
        segments: bundle.segments,
        words: bundle.words,
        comments: bundle.comments,
        redactions: bundle.redactions,
        aliases: bundle.aliases,
        sets: bundle.sets,
      })
      await this.storage.writeSidecar(bundle.recording.displayName, serializeSidecar(document))
      return { sidecarCurrent: true, error: null, protectedItemCount }
    } catch (error) {
      return {
        sidecarCurrent: false,
        error:
          error instanceof IoError
            ? error.message
            : 'Saved on this device, but the recording-folder backup could not update. Tap Retry backup.',
        protectedItemCount,
      }
    }
  }

  /** The sidecar JSON for a manual download, used where automatic writes are impossible. */
  async exportSidecar(recordingId: string): Promise<string | null> {
    const bundle = await this.loadReview(recordingId)
    if (!bundle) return null
    return serializeSidecar(
      buildSidecar({
        recording: bundle.recording,
        segments: bundle.segments,
        words: bundle.words,
        comments: bundle.comments,
        redactions: bundle.redactions,
        aliases: bundle.aliases,
        sets: bundle.sets,
      }),
    )
  }
}

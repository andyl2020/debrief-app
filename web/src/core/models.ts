/**
 * Mirrors `app/src/main/java/com/andyluu/debrief/data/Models.kt` from the
 * Android app at tag v1.11.3.
 *
 * Field names and semantics are kept identical so the JSON sidecars written by
 * either platform stay interchangeable. Where Android uses `Long` milliseconds
 * we use `number`; where it uses a nullable `Double` confidence we use
 * `number | null`.
 */

export type RecordingStatus = 'NEW' | 'QUEUED' | 'TRANSCRIBING' | 'READY' | 'FAILED'
export type AiPassStatus = 'NOT_RUN' | 'RUNNING' | 'READY' | 'FAILED' | 'SKIPPED'
export type TranscriptQualityStatus = 'GOOD' | 'CHECK' | 'ISSUE'

/** A recording in the library. `sourceKey` locates the audio in the active storage adapter. */
export interface Recording {
  id: string
  sourceKey: string
  displayName: string
  mimeType: string | null
  sizeBytes: number
  lastModified: number
  durationMs: number
  status: RecordingStatus
  errorMessage: string | null
  playbackPositionMs: number
  discoveredAt: number
  /** Set while an AssemblyAI job is in flight so a reopened tab can resume polling. */
  providerJobId?: string | null
  provider?: string | null
}

export interface TranscriptSegment {
  id: number
  recordingId: string
  speakerId: string
  startMs: number
  endMs: number
  text: string
}

export interface TranscriptWord {
  id: number
  recordingId: string
  speakerId: string
  startMs: number
  endMs: number
  text: string
  confidence: number | null
}

export interface Comment {
  id: string
  recordingId: string
  timestampMs: number
  text: string
  createdAt: number
  updatedAt: number
}

export interface Redaction {
  id: string
  recordingId: string
  startMs: number
  endMs: number
  text: string
  createdAt: number
}

export interface SpeakerAlias {
  recordingId: string
  speakerId: string
  displayName: string
}

/**
 * A manual conversation set. Mirrors `ConversationSetEntity`. A set with
 * `endMs <= startMs` is "open" - started but not yet closed by the user.
 */
export interface ConversationSet {
  id: string
  recordingId: string
  orderIndex: number
  startMs: number
  endMs: number
  title: string
  summary: string
  speakerIds: string
}

export interface TranscriptQualityReport {
  recordingId: string
  status: TranscriptQualityStatus
  provider: string
  uploadMode: string
  audioDurationMs: number
  transcriptStartMs: number | null
  transcriptEndMs: number | null
  segmentCount: number
  wordCount: number
  speakerCount: number
  wordsPerMinute: number
  warningCount: number
  warningsText: string
  recommendation: string
  createdAt: number
}

export interface SearchHit {
  recordingId: string
  recordingName: string
  timestampMs: number
  speakerId: string | null
  snippet: string
  isComment: boolean
}

/** Everything the Review screen needs for one recording. */
export interface ReviewBundle {
  recording: Recording
  segments: TranscriptSegment[]
  words: TranscriptWord[]
  comments: Comment[]
  redactions: Redaction[]
  aliases: SpeakerAlias[]
  sets: ConversationSet[]
  qualityReport: TranscriptQualityReport | null
}

/** Creates a segment without the auto-generated id, for provider parsing. */
export function makeSegment(
  fields: Omit<TranscriptSegment, 'id'>,
): TranscriptSegment {
  return { id: 0, ...fields }
}

export function makeWord(fields: Omit<TranscriptWord, 'id'>): TranscriptWord {
  return { id: 0, ...fields }
}

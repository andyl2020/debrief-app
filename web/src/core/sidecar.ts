import {
  makeSegment,
  makeWord,
  type Comment,
  type ConversationSet,
  type Recording,
  type Redaction,
  type SpeakerAlias,
  type TranscriptSegment,
  type TranscriptWord,
} from './models'

/**
 * Ported from the `Sidecar*` types in
 * `app/src/main/java/com/andyluu/debrief/data/Models.kt:309-381` and the file
 * naming in `SidecarStore.kt:243-244`.
 *
 * This is the interoperability contract, and the reason it is worth getting
 * exactly right: an Android user can copy their recordings folder, open it in
 * this web app, and their comments, sets, redactions and speaker names come
 * across - and go back again. Field names, defaults and `schemaVersion` must
 * therefore match Android byte-for-byte in meaning.
 *
 * Android writes two copies next to each recording: `<name>.debrief.json` and
 * `<name>.debrief.backup.json`. We write and read the same pair.
 */

export const SIDECAR_SCHEMA_VERSION = 4

export function sidecarName(recordingName: string): string {
  return `${recordingName}.debrief.json`
}

export function backupSidecarName(recordingName: string): string {
  return `${recordingName}.debrief.backup.json`
}

export interface SidecarSegment {
  speakerId: string
  startMs: number
  endMs: number
  text: string
}

export interface SidecarWord extends SidecarSegment {
  confidence?: number | null
}

export interface SidecarComment {
  id: string
  timestampMs: number
  text: string
  createdAt: number
  updatedAt: number
}

export interface SidecarRedaction {
  id: string
  startMs: number
  endMs: number
  text: string
  createdAt: number
}

export interface SidecarSet {
  id: string
  orderIndex: number
  startMs: number
  endMs: number
  title: string
  summary: string
  speakerIds: string
}

export interface SidecarSpeakerSuggestion {
  speakerId: string
  suggestedName: string
  confidence: string
  evidence: string
}

export interface SidecarDocument {
  schemaVersion: number
  recordingId?: string | null
  recordingName: string
  recordingSizeBytes: number
  recordingDurationMs: number
  writtenAtEpochMs: number
  transcript: SidecarSegment[]
  words: SidecarWord[]
  comments: SidecarComment[]
  redactions: SidecarRedaction[]
  speakerAliases: Record<string, string>
  originalRecordingName?: string | null
  aiSummary: string
  skipAiPass: boolean
  sets: SidecarSet[]
  speakerSuggestions: SidecarSpeakerSuggestion[]
}

export interface SidecarInput {
  recording: Recording
  segments: TranscriptSegment[]
  words: TranscriptWord[]
  comments: Comment[]
  redactions: Redaction[]
  aliases: SpeakerAlias[]
  sets: ConversationSet[]
  writtenAtEpochMs?: number
}

export function buildSidecar(input: SidecarInput): SidecarDocument {
  const { recording } = input
  return {
    schemaVersion: SIDECAR_SCHEMA_VERSION,
    recordingId: recording.id,
    recordingName: recording.displayName,
    recordingSizeBytes: recording.sizeBytes,
    recordingDurationMs: recording.durationMs,
    writtenAtEpochMs: input.writtenAtEpochMs ?? Date.now(),
    transcript: input.segments.map((segment) => ({
      speakerId: segment.speakerId,
      startMs: segment.startMs,
      endMs: segment.endMs,
      text: segment.text,
    })),
    words: input.words.map((word) => ({
      speakerId: word.speakerId,
      startMs: word.startMs,
      endMs: word.endMs,
      text: word.text,
      confidence: word.confidence,
    })),
    comments: input.comments.map((comment) => ({
      id: comment.id,
      timestampMs: comment.timestampMs,
      text: comment.text,
      createdAt: comment.createdAt,
      updatedAt: comment.updatedAt,
    })),
    redactions: input.redactions.map((redaction) => ({
      id: redaction.id,
      startMs: redaction.startMs,
      endMs: redaction.endMs,
      text: redaction.text,
      createdAt: redaction.createdAt,
    })),
    speakerAliases: Object.fromEntries(
      input.aliases.map((alias) => [alias.speakerId, alias.displayName]),
    ),
    // The web app has no AI pass, so these carry through untouched rather than
    // being invented - a round-trip through the web must not erase Android's data.
    originalRecordingName: null,
    aiSummary: '',
    skipAiPass: false,
    sets: input.sets.map((set) => ({
      id: set.id,
      orderIndex: set.orderIndex,
      startMs: set.startMs,
      endMs: set.endMs,
      title: set.title,
      summary: set.summary,
      speakerIds: set.speakerIds,
    })),
    speakerSuggestions: [],
  }
}

export function serializeSidecar(document: SidecarDocument): string {
  return JSON.stringify(document, null, 2)
}

export class SidecarFormatError extends Error {
  override readonly name = 'SidecarFormatError'
}

/**
 * Parses a sidecar written by either platform. Unknown newer schema versions
 * are rejected rather than half-read; older ones are accepted because every
 * added field has a default, exactly as Android's `ignoreUnknownKeys` + default
 * arguments behave.
 */
export function parseSidecar(raw: string): SidecarDocument {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch (error) {
    throw new SidecarFormatError('This file is not a readable Debrief sidecar.', { cause: error })
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new SidecarFormatError('This file is not a readable Debrief sidecar.')
  }

  const source = parsed as Record<string, unknown>
  const schemaVersion = asNumber(source['schemaVersion']) ?? 1
  if (schemaVersion > SIDECAR_SCHEMA_VERSION) {
    throw new SidecarFormatError(
      `This sidecar was written by a newer version of Debrief (schema ${schemaVersion}). Update the web app before opening it.`,
    )
  }
  const recordingName = asString(source['recordingName'])
  if (!recordingName) {
    throw new SidecarFormatError('This sidecar is missing its recording name.')
  }

  return {
    schemaVersion,
    recordingId: asString(source['recordingId']),
    recordingName,
    recordingSizeBytes: asNumber(source['recordingSizeBytes']) ?? 0,
    recordingDurationMs: asNumber(source['recordingDurationMs']) ?? 0,
    writtenAtEpochMs: asNumber(source['writtenAtEpochMs']) ?? 0,
    transcript: asArray(source['transcript']).map(readSegment),
    words: asArray(source['words']).map(readWord),
    comments: asArray(source['comments']).map(readComment),
    redactions: asArray(source['redactions']).map(readRedaction),
    speakerAliases: readAliases(source['speakerAliases']),
    originalRecordingName: asString(source['originalRecordingName']),
    aiSummary: asString(source['aiSummary']) ?? '',
    skipAiPass: source['skipAiPass'] === true,
    sets: asArray(source['sets']).map(readSet),
    speakerSuggestions: asArray(source['speakerSuggestions']).map(readSuggestion),
  }
}

/** Turns a parsed sidecar into the entities the app stores, bound to `recordingId`. */
export function sidecarToEntities(
  recordingId: string,
  document: SidecarDocument,
): {
  segments: TranscriptSegment[]
  words: TranscriptWord[]
  comments: Comment[]
  redactions: Redaction[]
  aliases: SpeakerAlias[]
  sets: ConversationSet[]
} {
  return {
    segments: document.transcript.map((segment) => makeSegment({ recordingId, ...segment })),
    words: document.words.map((word) =>
      makeWord({
        recordingId,
        speakerId: word.speakerId,
        startMs: word.startMs,
        endMs: word.endMs,
        text: word.text,
        confidence: word.confidence ?? null,
      }),
    ),
    comments: document.comments.map((comment) => ({ recordingId, ...comment })),
    redactions: document.redactions.map((redaction) => ({ recordingId, ...redaction })),
    aliases: Object.entries(document.speakerAliases).map(([speakerId, displayName]) => ({
      recordingId,
      speakerId,
      displayName,
    })),
    sets: document.sets.map((set) => ({ recordingId, ...set })),
  }
}

function readSegment(value: unknown): SidecarSegment {
  const item = asObject(value)
  return {
    speakerId: asString(item['speakerId']) ?? 'Speaker A',
    startMs: asNumber(item['startMs']) ?? 0,
    endMs: asNumber(item['endMs']) ?? 0,
    text: asString(item['text']) ?? '',
  }
}

function readWord(value: unknown): SidecarWord {
  const item = asObject(value)
  return { ...readSegment(value), confidence: asNumber(item['confidence']) }
}

function readComment(value: unknown): SidecarComment {
  const item = asObject(value)
  const createdAt = asNumber(item['createdAt']) ?? 0
  return {
    id: asString(item['id']) ?? crypto.randomUUID(),
    timestampMs: asNumber(item['timestampMs']) ?? 0,
    text: asString(item['text']) ?? '',
    createdAt,
    updatedAt: asNumber(item['updatedAt']) ?? createdAt,
  }
}

function readRedaction(value: unknown): SidecarRedaction {
  const item = asObject(value)
  return {
    id: asString(item['id']) ?? crypto.randomUUID(),
    startMs: asNumber(item['startMs']) ?? 0,
    endMs: asNumber(item['endMs']) ?? 0,
    text: asString(item['text']) ?? '',
    createdAt: asNumber(item['createdAt']) ?? 0,
  }
}

function readSet(value: unknown): SidecarSet {
  const item = asObject(value)
  return {
    id: asString(item['id']) ?? crypto.randomUUID(),
    orderIndex: asNumber(item['orderIndex']) ?? 0,
    startMs: asNumber(item['startMs']) ?? 0,
    endMs: asNumber(item['endMs']) ?? 0,
    title: asString(item['title']) ?? '',
    summary: asString(item['summary']) ?? '',
    speakerIds: asString(item['speakerIds']) ?? '',
  }
}

function readSuggestion(value: unknown): SidecarSpeakerSuggestion {
  const item = asObject(value)
  return {
    speakerId: asString(item['speakerId']) ?? '',
    suggestedName: asString(item['suggestedName']) ?? '',
    confidence: asString(item['confidence']) ?? '',
    evidence: asString(item['evidence']) ?? '',
  }
}

function readAliases(value: unknown): Record<string, string> {
  const source = typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {}
  const aliases: Record<string, string> = {}
  for (const [speakerId, displayName] of Object.entries(source)) {
    if (typeof displayName === 'string') aliases[speakerId] = displayName
  }
  return aliases
}

function asObject(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {}
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

function asString(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

function asNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

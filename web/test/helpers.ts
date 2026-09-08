import {
  makeSegment,
  makeWord,
  type Comment,
  type ConversationSet,
  type Recording,
  type Redaction,
  type TranscriptSegment,
  type TranscriptWord,
} from '../src/core/models'

/**
 * Builders mirroring the private helpers at the bottom of each Kotlin test
 * class, so the ported test bodies read the same as the originals.
 */

export const RECORDING_ID = 'recording'

export function segment(
  startMs: number,
  endMs: number,
  speakerId = 'Speaker A',
  text = 'Transcript',
  id = 0,
): TranscriptSegment {
  return { ...makeSegment({ recordingId: RECORDING_ID, speakerId, startMs, endMs, text }), id }
}

export function word(
  text: string,
  startMs: number,
  endMs: number,
  speakerId = 'Speaker A',
  confidence: number | null = null,
): TranscriptWord {
  return makeWord({ recordingId: RECORDING_ID, speakerId, startMs, endMs, text, confidence })
}

export function comment(id: string, timestampMs: number, text = id): Comment {
  return { id, recordingId: RECORDING_ID, timestampMs, text, createdAt: 0, updatedAt: 0 }
}

export function redaction(startMs: number, endMs: number, text = 'private'): Redaction {
  return { id: `${startMs}-${endMs}`, recordingId: RECORDING_ID, startMs, endMs, text, createdAt: 0 }
}

export function conversationSet(
  id: string,
  startMs: number,
  title: string,
  endMs: number = startMs + 1_000,
  orderIndex = 0,
): ConversationSet {
  return {
    id,
    recordingId: RECORDING_ID,
    orderIndex,
    startMs,
    endMs,
    title,
    summary: '',
    speakerIds: '',
  }
}

export function openConversationSet(id: string, startMs: number, title: string): ConversationSet {
  return conversationSet(id, startMs, title, startMs)
}

export function recording(overrides: Partial<Recording> = {}): Recording {
  return {
    id: RECORDING_ID,
    sourceKey: 'source',
    displayName: 'Field recording.m4a',
    mimeType: 'audio/mp4',
    sizeBytes: 1024,
    lastModified: 0,
    durationMs: 600_000,
    status: 'READY',
    errorMessage: null,
    playbackPositionMs: 0,
    discoveredAt: 0,
    providerJobId: null,
    provider: 'assemblyai',
    ...overrides,
  }
}

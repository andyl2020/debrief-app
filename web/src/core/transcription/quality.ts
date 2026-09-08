import type { TranscriptionAudioQuality } from '../audio-quality'
import { AUDIO_QUALITY } from '../audio-quality'
import { formatTimestamp } from '../format'
import type {
  TranscriptQualityReport,
  TranscriptQualityStatus,
  TranscriptSegment,
  TranscriptWord,
} from '../models'

/**
 * Ported verbatim from
 * `app/src/main/java/com/andyluu/debrief/transcription/TranscriptQualityAnalyzer.kt`.
 *
 * This does NOT judge wording. It looks for structural evidence that a chunk of
 * the recording went missing: broken or out-of-order timestamps, large silent
 * gaps, a transcript that starts late or stops early, thin word density, a
 * single speaker on a long multi-person recording, and low provider confidence.
 *
 * Every threshold below is kept identical to Android so a recording gets the
 * same verdict on both platforms.
 */

const LARGE_GAP_MS = 5 * 60 * 1000
const ISSUE_GAP_MS = 10 * 60 * 1000
const EARLY_END_MS = 5 * 60 * 1000
const LATE_START_MS = 2 * 60 * 1000
const MIN_DENSITY_DURATION_MS = 5 * 60 * 1000
const LOW_WORDS_PER_MINUTE = 35
const LOW_CONFIDENCE_MEAN = 0.65
const LOW_CONFIDENCE_SHARE = 0.2

interface Warning {
  severity: TranscriptQualityStatus
  message: string
}

export interface AnalyzeInput {
  recordingId: string
  provider: string
  uploadQuality: TranscriptionAudioQuality
  audioDurationMs: number
  segments: TranscriptSegment[]
  words: TranscriptWord[]
}

export function analyzeTranscriptQuality(input: AnalyzeInput): TranscriptQualityReport {
  const { recordingId, provider, uploadQuality, audioDurationMs, segments, words } = input
  const warnings: Warning[] = []

  const sortedSegments = [...segments].sort((a, b) => a.startMs - b.startMs)
  const sortedWords = [...words].sort((a, b) => a.startMs - b.startMs)

  const timeline = (sortedSegments.length > 0 ? sortedSegments : sortedWords)
    .map((item) => ({ startMs: item.startMs, endMs: item.endMs }))
    .filter((item) => item.startMs >= 0 && item.endMs >= 0)

  const transcriptStartMs = timeline.length > 0 ? Math.min(...timeline.map((i) => i.startMs)) : null
  const transcriptEndMs = timeline.length > 0 ? Math.max(...timeline.map((i) => i.endMs)) : null

  const segmentWordEstimate = sortedSegments.reduce(
    (total, segment) => total + segment.text.split(/\s+/).filter((part) => part.length > 0).length,
    0,
  )
  const wordCount = sortedWords.length > 0 ? sortedWords.length : segmentWordEstimate
  const speakerCount = new Set(
    [...sortedSegments, ...sortedWords]
      .map((item) => item.speakerId.trim())
      .filter((id) => id.length > 0),
  ).size
  const minutes = Math.max(1, audioDurationMs) / 60_000
  const wordsPerMinute = wordCount / minutes

  if (sortedSegments.length === 0 && sortedWords.length === 0) {
    warnings.push({ severity: 'ISSUE', message: 'Transcript is empty.' })
  }

  const invalidSegmentCount = sortedSegments.filter((s) => s.startMs < 0 || s.endMs <= s.startMs).length
  const invalidWordCount = sortedWords.filter((w) => w.startMs < 0 || w.endMs <= w.startMs).length
  if (invalidSegmentCount + invalidWordCount > 0) {
    warnings.push({
      severity: 'ISSUE',
      message: `${invalidSegmentCount + invalidWordCount} transcript items have missing or invalid timestamps.`,
    })
  }

  const nonMonotonic = zipWithNext(sortedSegments).find(
    ([previous, current]) =>
      current.startMs < previous.startMs || current.startMs < previous.endMs - 1_000,
  )
  if (nonMonotonic) {
    warnings.push({
      severity: 'ISSUE',
      message: `Transcript timestamps are out of order near ${formatTimestamp(nonMonotonic[1].startMs)}.`,
    })
  }

  for (const [previous, current] of zipWithNext(
    [...timeline].sort((a, b) => a.startMs - b.startMs),
  )) {
    const gap = current.startMs - previous.endMs
    if (gap >= LARGE_GAP_MS) {
      warnings.push({
        severity: gap >= ISSUE_GAP_MS ? 'ISSUE' : 'CHECK',
        message: `Large timestamp gap: ${formatTimestamp(previous.endMs)}-${formatTimestamp(current.startMs)}. This may be silence, noise, or a missing transcript section.`,
      })
    }
  }

  if (audioDurationMs > 0 && transcriptStartMs !== null && transcriptStartMs >= LATE_START_MS) {
    warnings.push({
      severity: 'CHECK',
      message: `Transcript starts at ${formatTimestamp(transcriptStartMs)}, not near the beginning of the audio.`,
    })
  }

  if (audioDurationMs > 0 && transcriptEndMs !== null) {
    const missingTail = audioDurationMs - transcriptEndMs
    if (missingTail >= EARLY_END_MS && transcriptEndMs < Math.round(audioDurationMs * 0.85)) {
      warnings.push({
        severity: 'ISSUE',
        message: `Transcript ends at ${formatTimestamp(transcriptEndMs)} while audio ends at ${formatTimestamp(audioDurationMs)}.`,
      })
    }
  }

  if (sortedWords.length === 0 && sortedSegments.length > 0) {
    warnings.push({
      severity: 'CHECK',
      message: 'Provider returned transcript segments without word-level timing.',
    })
  } else if (segmentWordEstimate > 0 && sortedWords.length < segmentWordEstimate * 0.5) {
    warnings.push({
      severity: 'CHECK',
      message: 'Word-level timing coverage is lower than expected.',
    })
  }

  if (audioDurationMs >= MIN_DENSITY_DURATION_MS && wordsPerMinute < LOW_WORDS_PER_MINUTE) {
    warnings.push({
      severity: 'CHECK',
      message: `Transcript density is low (${Math.round(wordsPerMinute)} words/min). This may be silence, noise, or missed speech.`,
    })
  }

  if (audioDurationMs >= MIN_DENSITY_DURATION_MS && speakerCount <= 1) {
    warnings.push({
      severity: 'CHECK',
      message:
        'Only one speaker label was detected. Check diarization if this was a multi-person conversation.',
    })
  }

  const confidences = sortedWords
    .map((word) => word.confidence)
    .filter((value): value is number => value !== null && value !== undefined)
  if (confidences.length >= 20) {
    const mean = confidences.reduce((total, value) => total + value, 0) / confidences.length
    const lowShare =
      confidences.filter((value) => value < LOW_CONFIDENCE_MEAN).length / confidences.length
    if (mean < LOW_CONFIDENCE_MEAN || lowShare >= LOW_CONFIDENCE_SHARE) {
      warnings.push({
        severity: 'CHECK',
        message: `Provider confidence is low in ${Math.round(lowShare * 100)}% of timed words.`,
      })
    }
  }

  const status: TranscriptQualityStatus = warnings.some((w) => w.severity === 'ISSUE')
    ? 'ISSUE'
    : warnings.length > 0
      ? 'CHECK'
      : 'GOOD'

  return {
    recordingId,
    status,
    provider,
    uploadMode: AUDIO_QUALITY[uploadQuality].storedValue,
    audioDurationMs,
    transcriptStartMs,
    transcriptEndMs,
    segmentCount: sortedSegments.length,
    wordCount,
    speakerCount,
    wordsPerMinute,
    warningCount: warnings.length,
    warningsText: warnings.map((warning) => warning.message).join('\n'),
    recommendation: recommendation(status),
    createdAt: Date.now(),
  }
}

function recommendation(status: TranscriptQualityStatus): string {
  switch (status) {
    case 'GOOD':
      return 'No integrity issues found. This does not guarantee perfect wording, but no missing chunks, broken timestamps, or suspicious truncation were detected.'
    case 'CHECK':
      return 'Open the warning locations and spot-check the transcript before relying on it.'
    case 'ISSUE':
      return 'Retranscribe with AssemblyAI + Original upload, or inspect the warning locations manually before relying on this transcript.'
  }
}

function zipWithNext<T>(items: T[]): Array<[T, T]> {
  const pairs: Array<[T, T]> = []
  for (let index = 0; index + 1 < items.length; index += 1) {
    pairs.push([items[index]!, items[index + 1]!])
  }
  return pairs
}

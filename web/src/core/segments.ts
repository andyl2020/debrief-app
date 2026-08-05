import { makeSegment, type TranscriptSegment, type TranscriptWord } from './models'

/**
 * Ported from `segmentsFromWords` in
 * `app/src/main/java/com/andyluu/debrief/transcription/TranscriptionProvider.kt:27-61`.
 *
 * Both providers return a convenience "utterances" list, but in practice that
 * list can omit recognised sections. The word stream is the complete timeline,
 * so display segments are rebuilt from words and the provider's own utterances
 * are only used as a fallback.
 *
 * A group is flushed when the speaker changes, a gap exceeds 1.5s, the group
 * reaches 40 words, the group spans more than 30s, or a word ends a sentence.
 */
export function segmentsFromWords(
  recordingId: string,
  words: TranscriptWord[],
): TranscriptSegment[] {
  if (words.length === 0) return []

  const segments: TranscriptSegment[] = []
  let group: TranscriptWord[] = []

  const flush = () => {
    if (group.length === 0) return
    const first = group[0]!
    const last = group[group.length - 1]!
    segments.push(
      makeSegment({
        recordingId,
        speakerId: first.speakerId,
        startMs: first.startMs,
        endMs: last.endMs,
        text: group.map((word) => word.text).join(' ').trim(),
      }),
    )
    group = []
  }

  const sorted = [...words].sort((a, b) => a.startMs - b.startMs)
  for (const word of sorted) {
    const previous = group.length > 0 ? group[group.length - 1]! : null
    const shouldSplit =
      previous !== null &&
      (previous.speakerId !== word.speakerId ||
        word.startMs - previous.endMs > 1_500 ||
        group.length >= 40 ||
        word.endMs - group[0]!.startMs > 30_000)
    if (shouldSplit) flush()
    group.push(word)
    if (/[.?!]$/.test(word.text)) flush()
  }
  flush()

  return segments.filter((segment) => segment.text.trim().length > 0)
}

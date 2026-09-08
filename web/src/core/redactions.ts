import type { Redaction, TranscriptWord } from './models'

/**
 * Ported from `app/src/main/java/com/andyluu/debrief/ui/Redactions.kt`.
 *
 * Redaction is reversible and never touches the source audio. Text is masked as
 * `[redacted]` and playback is muted across the redacted timestamps, so the
 * screen and the speakers can both be shared safely.
 *
 * The asymmetric padding is deliberate and load-bearing. Provider word starts
 * can land slightly after the audible consonant, and at 4x playback the player
 * advances about 300 ms during one 75 ms volume poll. Favour privacy by muting
 * well before the stored start while keeping a smaller tail.
 */
export const REDACTION_AUDIO_LEAD_PAD_MS = 750
export const REDACTION_AUDIO_TRAIL_PAD_MS = 250
export const REDACTION_LABEL = '[redacted]'

export interface RedactionRange {
  startMs: number
  endMs: number
  text: string
}

export interface RedactedWordChoice {
  index: number
  startMs: number
  endMs: number
  text: string
}

export interface RedactionMuteRange {
  startMs: number
  endMs: number
}

/**
 * Pads every redaction, then merges anything that now overlaps, so the player
 * only has to test a small sorted list on each poll.
 */
export function redactionMuteRanges(
  redactions: Redaction[],
  leadPadMs: number = REDACTION_AUDIO_LEAD_PAD_MS,
  trailPadMs: number = REDACTION_AUDIO_TRAIL_PAD_MS,
): RedactionMuteRange[] {
  const padded = redactions
    .map((redaction) => {
      const start = Math.min(redaction.startMs, redaction.endMs)
      const end = Math.max(redaction.startMs, redaction.endMs)
      return {
        startMs: Math.max(0, start - Math.max(0, leadPadMs)),
        endMs: end + Math.max(0, trailPadMs),
      }
    })
    .sort((a, b) => a.startMs - b.startMs)

  if (padded.length === 0) return []

  const merged: RedactionMuteRange[] = [padded[0]!]
  for (const next of padded.slice(1)) {
    const current = merged[merged.length - 1]!
    if (next.startMs <= current.endMs) {
      merged[merged.length - 1] = { ...current, endMs: Math.max(current.endMs, next.endMs) }
    } else {
      merged.push(next)
    }
  }
  return merged
}

export function redactionActiveAt(
  positionMs: number,
  redactions: Redaction[],
  leadPadMs: number = REDACTION_AUDIO_LEAD_PAD_MS,
  trailPadMs: number = REDACTION_AUDIO_TRAIL_PAD_MS,
): boolean {
  return redactionMuteActiveAt(positionMs, redactionMuteRanges(redactions, leadPadMs, trailPadMs))
}

export function redactionPlaybackVolumeAt(positionMs: number, redactions: Redaction[]): number {
  return redactionPlaybackVolumeForRanges(positionMs, redactionMuteRanges(redactions))
}

export function redactionMuteActiveAt(
  positionMs: number,
  muteRanges: RedactionMuteRange[],
): boolean {
  return muteRanges.some((range) => positionMs >= range.startMs && positionMs <= range.endMs)
}

export function redactionPlaybackVolumeForRanges(
  positionMs: number,
  muteRanges: RedactionMuteRange[],
): number {
  return redactionMuteActiveAt(positionMs, muteRanges) ? 0 : 1
}

export function overlappingRedactions(
  redactions: Redaction[],
  startMs: number,
  endMs: number,
): Redaction[] {
  return redactions.filter((r) => r.startMs < endMs && r.endMs > startMs)
}

export function wordsForSegment(
  words: TranscriptWord[],
  segmentStartMs: number,
  segmentEndMs: number,
): TranscriptWord[] {
  return words
    .filter((word) => word.endMs >= segmentStartMs && word.startMs <= segmentEndMs)
    .sort((a, b) => a.startMs - b.startMs)
}

interface TimedTextRange {
  textStart: number
  textEnd: number
  startMs: number
  endMs: number
  text: string
}

/**
 * Masks the redacted words inside one transcript card.
 *
 * Consecutive redacted words collapse into a single `[redacted]` so the card
 * does not leak how many words were hidden. When every word is redacted - or
 * when word timing is missing entirely - the whole card becomes one label.
 */
export function redactedTranscriptText(
  text: string,
  words: TranscriptWord[],
  redactions: Redaction[],
  segmentStartMs: number,
  segmentEndMs: number,
): string {
  const overlapping = overlappingRedactions(redactions, segmentStartMs, segmentEndMs)
  if (overlapping.length === 0) return text
  if (overlapping.some((r) => r.startMs <= segmentStartMs && r.endMs >= segmentEndMs)) {
    return REDACTION_LABEL
  }

  const ranges = wordTextRanges(text, words)
  // No word timing means we cannot mask precisely, so mask everything rather
  // than risk showing something that should have been hidden.
  if (ranges.length === 0) return REDACTION_LABEL

  const redactedRanges = ranges.filter((range) =>
    overlapping.some((r) => r.startMs < range.endMs && r.endMs > range.startMs),
  )
  if (redactedRanges.length === 0) return text
  if (redactedRanges.length === ranges.length) return REDACTION_LABEL

  let builder = ''
  let cursor = 0
  let lastWasRedacted = false

  for (const range of ranges) {
    const shouldRedact = redactedRanges.some(
      (r) => r.textStart === range.textStart && r.textEnd === range.textEnd,
    )
    if (shouldRedact) {
      if (!lastWasRedacted) {
        builder += trimEnd(text.slice(cursor, range.textStart))
        if (builder.length > 0 && !/\s/.test(builder[builder.length - 1]!)) builder += ' '
        builder += REDACTION_LABEL
      }
      cursor = range.textEnd
      lastWasRedacted = true
    } else {
      const between = text.slice(cursor, range.textStart)
      builder += lastWasRedacted ? afterRedaction(between) : between
      builder += text.slice(range.textStart, range.textEnd)
      cursor = range.textEnd
      lastWasRedacted = false
    }
  }

  const suffix = text.slice(cursor)
  builder += lastWasRedacted ? afterRedaction(suffix) : suffix
  return builder.replace(/\s{2,}/g, ' ').trim()
}

/** Every word of a card, for the "redact this whole card" action. */
export function redactionRangesForWholeSegment(
  words: TranscriptWord[],
  segmentStartMs: number,
  segmentEndMs: number,
  fallbackText: string,
): RedactionRange[] {
  const segmentWords = wordsForSegment(words, segmentStartMs, segmentEndMs)
  if (segmentWords.length === 0) {
    return [{ startMs: segmentStartMs, endMs: segmentEndMs, text: fallbackText }]
  }
  return segmentWords.map((word) => ({ startMs: word.startMs, endMs: word.endMs, text: word.text }))
}

/**
 * The undo menu: which words in this card are currently hidden, numbered from
 * 1, with their real text so the user can pick the one to reveal.
 */
export function redactedWordChoices(
  words: TranscriptWord[],
  redactions: Redaction[],
  segmentStartMs: number,
  segmentEndMs: number,
): RedactedWordChoice[] {
  const overlapping = overlappingRedactions(redactions, segmentStartMs, segmentEndMs)
  if (overlapping.length === 0) return []
  const segmentWords = wordsForSegment(words, segmentStartMs, segmentEndMs)
  if (segmentWords.length === 0) return []

  const wholeSegmentRedacted = overlapping.some(
    (r) => r.startMs <= segmentStartMs && r.endMs >= segmentEndMs,
  )
  const choices: RedactedWordChoice[] = []
  segmentWords.forEach((word, index) => {
    const wordRedacted =
      wholeSegmentRedacted ||
      overlapping.some((r) => r.startMs < word.endMs && r.endMs > word.startMs)
    if (wordRedacted) {
      choices.push({ index: index + 1, startMs: word.startMs, endMs: word.endMs, text: word.text })
    }
  })
  return choices
}

/**
 * Rebuilds a card's redactions with one word revealed. A whole-card redaction
 * is expanded into per-word ranges first, so revealing one word does not
 * un-redact the rest.
 */
export function redactionRangesAfterRemovingWord(
  words: TranscriptWord[],
  redactions: Redaction[],
  segmentStartMs: number,
  segmentEndMs: number,
  removedWord: RedactedWordChoice,
): RedactionRange[] {
  const overlapping = overlappingRedactions(redactions, segmentStartMs, segmentEndMs)
  if (overlapping.length === 0) return []
  const segmentWords = wordsForSegment(words, segmentStartMs, segmentEndMs)
  if (segmentWords.length === 0) return []

  const wholeSegmentRedacted = overlapping.some(
    (r) => r.startMs <= segmentStartMs && r.endMs >= segmentEndMs,
  )
  return segmentWords
    .filter((word) => !(word.startMs === removedWord.startMs && word.endMs === removedWord.endMs))
    .filter(
      (word) =>
        wholeSegmentRedacted ||
        overlapping.some((r) => r.startMs < word.endMs && r.endMs > word.startMs),
    )
    .map((word) => ({ startMs: word.startMs, endMs: word.endMs, text: word.text }))
}

/**
 * Pairs whitespace-delimited tokens in the rendered text with the provider's
 * word list positionally. `zip` semantics: if the two lists disagree in length,
 * the shorter one wins, exactly as Kotlin's `zip` does.
 */
function wordTextRanges(text: string, words: TranscriptWord[]): TimedTextRange[] {
  if (text.trim().length === 0 || words.length === 0) return []
  const tokens = [...text.matchAll(/\S+/g)]
  if (tokens.length === 0) return []

  const pairCount = Math.min(tokens.length, words.length)
  const ranges: TimedTextRange[] = []
  for (let index = 0; index < pairCount; index += 1) {
    const match = tokens[index]!
    const word = words[index]!
    const textStart = match.index
    ranges.push({
      textStart,
      textEnd: textStart + match[0].length,
      startMs: word.startMs,
      endMs: word.endMs,
      text: match[0],
    })
  }
  return ranges
}

function trimEnd(value: string): string {
  return value.replace(/\s+$/, '')
}

/**
 * Rejoins text after a `[redacted]` label, collapsing the whitespace that the
 * removed words left behind into exactly one separating space.
 */
function afterRedaction(value: string): string {
  const stripped = value.replace(/^\s+/, '')
  if (value.length > 0 && /\s/.test(value[0]!)) return ` ${stripped}`
  return prependIfNeeded(stripped)
}

function prependIfNeeded(value: string): string {
  if (value.length > 0 && /\s/.test(value[0]!)) return value
  if (value.length > 0) return ` ${value}`
  return value
}

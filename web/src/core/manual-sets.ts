import type { ConversationSet } from './models'

/**
 * Ported from `app/src/main/java/com/andyluu/debrief/ui/ManualSets.kt`.
 *
 * A manual conversation set marks a stretch of a recording. The user taps
 * "start", and later "end". Between those taps the set is *open*: it has a
 * start but no meaningful end, encoded as `endMs <= startMs`.
 *
 * An open set must NOT behave as if it covers the rest of the recording -
 * that's what `openSetDoesNotAutofillTranscriptRange` pins down. Otherwise a
 * half-finished set would silently claim every transcript line after it.
 */

export function isOpenManualSet(set: ConversationSet): boolean {
  return set.endMs <= set.startMs
}

export function isClosedManualSet(set: ConversationSet): boolean {
  return !isOpenManualSet(set)
}

export function setContainsPosition(set: ConversationSet, positionMs: number): boolean {
  return isClosedManualSet(set) && positionMs >= set.startMs && positionMs <= set.endMs
}

export function setOverlapsRange(set: ConversationSet, startMs: number, endMs: number): boolean {
  return isClosedManualSet(set) && set.startMs <= endMs && set.endMs >= startMs
}

/**
 * Parses the timestamp formats a user actually types while reviewing:
 * `45` (seconds), `1:23` (m:ss), `1:02:03` (h:mm:ss). Returns null for anything
 * malformed or out of range rather than guessing.
 */
export function parseTimestampInput(value: string): number | null {
  const parts = value.trim().split(':')
  if (parts.length === 0 || parts.length > 3 || parts.some((part) => part.trim().length === 0)) {
    return null
  }

  const numbers: number[] = []
  for (const part of parts) {
    // Kotlin's toLongOrNull rejects anything that is not a plain integer.
    if (!/^\d+$/.test(part)) return null
    numbers.push(Number(part))
  }
  if (numbers.some((number) => number < 0 || !Number.isFinite(number))) return null

  let totalSeconds: number
  if (numbers.length === 1) {
    totalSeconds = numbers[0]!
  } else if (numbers.length === 2) {
    const [minutes, seconds] = numbers as [number, number]
    if (seconds > 59) return null
    totalSeconds = minutes * 60 + seconds
  } else {
    const [hours, minutes, seconds] = numbers as [number, number, number]
    if (minutes > 59 || seconds > 59) return null
    totalSeconds = hours * 3600 + minutes * 60 + seconds
  }
  return totalSeconds * 1000
}

/**
 * Picks the next "Set N" name. Deliberately continues past the highest number
 * ever used rather than filling gaps, so a deleted "Set 2" does not get its
 * name reused by an unrelated section.
 */
export function nextManualSetNumber(sets: ConversationSet[]): number {
  const titleNumbers = sets
    .map((set) => /^Set\s+(\d+)$/i.exec(set.title.trim()))
    .map((match) => (match ? Number(match[1]) : null))
    .filter((value): value is number => value !== null && Number.isFinite(value))

  const highest = titleNumbers.length > 0 ? Math.max(...titleNumbers) : sets.length
  return Math.max(1, highest + 1)
}

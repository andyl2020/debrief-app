import { describe, expect, it } from 'vitest'
import {
  REDACTION_LABEL,
  redactedTranscriptText,
  redactedWordChoices,
  redactionActiveAt,
  redactionMuteRanges,
  redactionPlaybackVolumeAt,
  redactionPlaybackVolumeForRanges,
  redactionRangesAfterRemovingWord,
} from '../src/core/redactions'
import { redaction, word } from './helpers'

/** Ported from `app/src/test/java/com/andyluu/debrief/ui/RedactionsTest.kt`. */
describe('Redactions', () => {
  it('masksOnlyWordsInsideRedactionRange', () => {
    const text = 'My phone number is private today.'
    const words = [
      word('My', 0, 100),
      word('phone', 100, 200),
      word('number', 200, 300),
      word('is', 300, 400),
      word('private', 400, 500),
      word('today.', 500, 600),
    ]

    expect(redactedTranscriptText(text, words, [redaction(100, 300)], 0, 600)).toBe(
      'My [redacted] is private today.',
    )
  })

  it('fullCardRedactionMasksFromTheFirstWord', () => {
    const text = 'First word must disappear.'
    const words = [
      word('First', 0, 100),
      word('word', 100, 200),
      word('must', 200, 300),
      word('disappear.', 300, 400),
    ]

    expect(redactedTranscriptText(text, words, [redaction(0, 400)], 0, 400)).toBe(REDACTION_LABEL)
  })

  it('allWordRedactionsStillLookLikeOneRedactedCard', () => {
    const text = 'One two three four five extra-cleaned-token.'
    const words = [
      word('One', 0, 100),
      word('two', 100, 200),
      word('three', 200, 300),
      word('four', 300, 400),
      word('five.', 400, 500),
    ]
    const redactions = words.map((current) => redaction(current.startMs, current.endMs))

    expect(redactedTranscriptText(text, words, redactions, 0, 500)).toBe(REDACTION_LABEL)
  })

  it('removingFifthWordFromCardRedactionKeepsOtherWordsRedacted', () => {
    const text = 'One two three four five six.'
    const words = [
      word('One', 0, 100),
      word('two', 100, 200),
      word('three', 200, 300),
      word('four', 300, 400),
      word('five', 400, 500),
      word('six.', 500, 600),
    ]
    const fullCardRedaction = [redaction(0, 600)]
    const fifth = redactedWordChoices(words, fullCardRedaction, 0, 600).find(
      (choice) => choice.index === 5,
    )!

    const remaining = redactionRangesAfterRemovingWord(
      words,
      fullCardRedaction,
      0,
      600,
      fifth,
    ).map((range) => redaction(range.startMs, range.endMs))

    // Revealing one word must not reveal its neighbours, and the two runs of
    // hidden words must still collapse to one label each.
    expect(redactedTranscriptText(text, words, remaining, 0, 600)).toBe('[redacted] five [redacted]')
    expect(redactedWordChoices(words, remaining, 0, 600).map((choice) => choice.index)).toEqual([
      1, 2, 3, 4, 6,
    ])
  })

  it('redactedWordChoicesIncludeVisibleWordTextForUndoMenu', () => {
    const words = [word('Andy', 0, 100), word('Vancouver', 100, 200)]

    const choices = redactedWordChoices(words, [redaction(0, 200)], 0, 200)

    expect(choices.map((choice) => choice.text)).toEqual(['Andy', 'Vancouver'])
  })

  it('fullCardRedactionChoicesIncludeTheFirstWord', () => {
    const words = [word('First', 0, 100), word('second', 100, 200), word('third', 200, 300)]

    expect(
      redactedWordChoices(words, [redaction(0, 300)], 0, 300).map((choice) => choice.index),
    ).toEqual([1, 2, 3])
  })

  it('wholeSegmentFallsBackWhenWordTimingIsMissing', () => {
    // Without word timing we cannot mask precisely, so the safe answer is to
    // mask the whole card rather than risk showing something private.
    expect(redactedTranscriptText('Private sentence', [], [redaction(0, 1_000)], 0, 1_000)).toBe(
      REDACTION_LABEL,
    )
  })

  it('audioMuteUsesLargerLeadingPrivacyBuffer', () => {
    const redactions = [redaction(1_000, 2_000)]

    expect(redactionActiveAt(250, redactions)).toBe(true)
    expect(redactionActiveAt(2_250, redactions)).toBe(true)
    expect(redactionActiveAt(249, redactions)).toBe(false)
    expect(redactionActiveAt(2_251, redactions)).toBe(false)
  })

  it('leadingBufferClampsAtRecordingStart', () => {
    const ranges = redactionMuteRanges([redaction(200, 500)])

    expect(ranges).toEqual([{ startMs: 0, endMs: 750 }])
    expect(redactionPlaybackVolumeAt(0, [redaction(200, 500)])).toBe(0)
  })

  it('overlappingPaddedMuteRangesAreMerged', () => {
    const ranges = redactionMuteRanges([redaction(1_000, 1_200), redaction(1_700, 2_000)])

    expect(ranges).toEqual([{ startMs: 250, endMs: 2_250 }])
    expect(redactionPlaybackVolumeForRanges(1_450, ranges)).toBe(0)
  })

  it('playbackVolumeIsFullOutsidePrivacyBuffer', () => {
    const redactions = [redaction(1_000, 2_000)]

    expect(redactionPlaybackVolumeAt(249, redactions)).toBe(1)
    expect(redactionPlaybackVolumeAt(2_251, redactions)).toBe(1)
  })
})

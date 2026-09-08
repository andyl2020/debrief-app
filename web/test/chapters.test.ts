import { describe, expect, it } from 'vitest'
import { buildChapterEntries } from '../src/core/chapters'
import {
  nextManualSetNumber,
  parseTimestampInput,
  setContainsPosition,
  setOverlapsRange,
} from '../src/core/manual-sets'
import { comment, conversationSet, openConversationSet } from './helpers'

/** Ported from `app/src/test/java/com/andyluu/debrief/ui/ChapterEntriesTest.kt`. */
describe('Chapters and manual sets', () => {
  it('entriesCombineSetsAndCommentsChronologically', () => {
    const entries = buildChapterEntries(
      [conversationSet('later', 10_000, ''), conversationSet('first', 1_000, 'Opening')],
      [comment('middle', 5_000), comment('same-time', 1_000)],
    )

    // A set and a comment at the identical timestamp put the set first.
    expect(entries.map((entry) => entry.id)).toEqual([
      'set:first',
      'comment:same-time',
      'comment:middle',
      'set:later',
    ])
    expect(entries.at(-1)!.title).toBe('Set 1')
  })

  it('timestampInputSupportsReviewTimeFormats', () => {
    expect(parseTimestampInput('1:23')).toBe(83_000)
    expect(parseTimestampInput('1:02:03')).toBe(3_723_000)
    expect(parseTimestampInput('45')).toBe(45_000)
    expect(parseTimestampInput('1:99')).toBeNull()
    expect(parseTimestampInput('1:02:99')).toBeNull()
    expect(parseTimestampInput('bad')).toBeNull()
  })

  it('nextSetNumberAvoidsReusingDeletedSetNames', () => {
    const sets = [conversationSet('one', 1_000, 'Set 1'), conversationSet('three', 3_000, 'Set 3')]

    // "Set 2" was deleted; its name must not be handed to an unrelated section.
    expect(nextManualSetNumber(sets)).toBe(4)
  })

  it('openSetDoesNotAutofillTranscriptRange', () => {
    const open = openConversationSet('open', 10_000, 'Set 1')

    expect(setOverlapsRange(open, 10_000, 20_000)).toBe(false)
    expect(setContainsPosition(open, 15_000)).toBe(false)
  })

  it('closedSetCoversOnlyExplicitStartToEndRange', () => {
    const closed = conversationSet('closed', 10_000, 'Set 1', 20_000)

    expect(setOverlapsRange(closed, 1_000, 9_999)).toBe(false)
    expect(setOverlapsRange(closed, 12_000, 14_000)).toBe(true)
    expect(setOverlapsRange(closed, 20_001, 30_000)).toBe(false)
    expect(setContainsPosition(closed, 15_000)).toBe(true)
    expect(setContainsPosition(closed, 25_000)).toBe(false)
  })
})

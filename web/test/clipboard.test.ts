import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyTextToClipboard, formatTranscriptForClipboard } from '../src/core/clipboard'
import { segment } from './helpers'

describe('transcript clipboard', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('copies every segment with timestamps and resolved speaker labels', () => {
    expect(
      formatTranscriptForClipboard({
        segments: [
          segment(1_000, 5_000, 'Speaker A', 'Hey good to meet you.'),
          segment(3_726_000, 3_730_000, 'Speaker B', 'Likewise.'),
        ],
        aliases: [
          { recordingId: 'recording', speakerId: 'Speaker A', displayName: 'Andy' },
        ],
      }),
    ).toBe('[0:01] Andy: Hey good to meet you.\n\n[1:02:06] Speaker B: Likewise.')
  })

  it('uses the text currently presented by the review screen', () => {
    expect(
      formatTranscriptForClipboard({
        segments: [segment(0, 1_000, 'Speaker A', 'private words')],
        aliases: [],
        textForSegment: () => '[redacted]',
      }),
    ).toBe('[0:00] Speaker A: [redacted]')
  })

  it('writes the complete transcript in one clipboard operation', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })

    await copyTextToClipboard('whole transcript')

    expect(writeText).toHaveBeenCalledOnce()
    expect(writeText).toHaveBeenCalledWith('whole transcript')
  })
})

import { describe, expect, it } from 'vitest'
import { exportMarkdown } from '../src/core/markdown'
import { comment, segment } from './helpers'

/** Web-specific, pinning the format produced by `ViewModels.kt:exportMarkdown`. */
describe('exportMarkdown', () => {
  it('renders speaker aliases, timestamps and inline comments', () => {
    const markdown = exportMarkdown({
      displayName: 'Run club.m4a',
      segments: [
        segment(1_000, 5_000, 'Speaker A', 'Hey good to meet you.'),
        segment(6_000, 10_000, 'Speaker B', 'The seawall route was solid.'),
      ],
      comments: [comment('c1', 7_000, 'Follow up on the route')],
      aliases: [{ recordingId: 'recording', speakerId: 'Speaker A', displayName: 'Andy' }],
    })

    expect(markdown).toBe(
      [
        '# Run club.m4a',
        '',
        '**[0:01] Andy:** Hey good to meet you.',
        '',
        '**[0:06] Speaker B:** The seawall route was solid.',
        '',
        '> **Comment [0:07]:** Follow up on the route',
        '',
        '',
      ].join('\n'),
    )
  })

  it('collects gap comments into a trailing section instead of dropping them', () => {
    // The counterpart of the gap-comment visibility fix: a comment that falls
    // between transcript lines must still reach the export.
    const markdown = exportMarkdown({
      displayName: 'Run club.m4a',
      segments: [segment(1_000, 5_000, 'Speaker A', 'Only line.')],
      comments: [comment('c1', 40_000, 'Left during silence')],
      aliases: [],
    })

    expect(markdown).toContain('## Comments')
    expect(markdown).toContain('- [0:40] Left during silence')
  })

  it('returns a title-only document when there is no transcript yet', () => {
    const markdown = exportMarkdown({
      displayName: 'Empty.m4a',
      segments: [],
      comments: [],
      aliases: [],
    })

    expect(markdown).toBe('# Empty.m4a\n\n')
  })
})

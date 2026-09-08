import { beforeEach, describe, expect, it } from 'vitest'
import { SearchIndex, type IndexedRow } from '../src/core/search'

/**
 * Web-specific. There is no FTS5 in the browser, so these pin the semantics the
 * Android `SearchRepository` relied on - especially the transcript-only scoping
 * that was a deliberate fix in v1.4.1.
 */
describe('SearchIndex', () => {
  let index: SearchIndex

  const row = (overrides: Partial<IndexedRow> = {}): IndexedRow => ({
    recordingId: 'rec-1',
    recordingName: 'Run club.m4a',
    speakerId: 'Speaker A',
    timestampMs: 1_000,
    body: 'the route around the seawall was solid',
    kind: 'transcript',
    ...overrides,
  })

  beforeEach(() => {
    index = new SearchIndex()
  })

  it('matches every term, as a prefix', () => {
    index.replaceRecording('rec-1', [row()])

    expect(index.search('seaw')).toHaveLength(1)
    expect(index.search('route seawall')).toHaveLength(1)
    // AND semantics: a term that is absent eliminates the row.
    expect(index.search('route mountain')).toHaveLength(0)
  })

  it('returns nothing for a blank query rather than everything', () => {
    index.replaceRecording('rec-1', [row()])

    expect(index.search('')).toEqual([])
    expect(index.search('   ')).toEqual([])
  })

  it('is case and punctuation insensitive', () => {
    index.replaceRecording('rec-1', [row({ body: 'Hello there, Andy!' })])

    expect(index.search('andy')).toHaveLength(1)
    expect(index.search('HELLO')).toHaveLength(1)
  })

  it('scopes in-player search to transcript rows of one recording', () => {
    index.replaceRecording('rec-1', [
      row({ body: 'seawall in the transcript', kind: 'transcript' }),
      row({ body: 'seawall in my comment', kind: 'comment', speakerId: '' }),
      row({ body: 'seawall in the summary', kind: 'summary', speakerId: '' }),
      row({ body: 'seawall.m4a', kind: 'filename', speakerId: '' }),
    ])
    index.replaceRecording('rec-2', [
      row({ recordingId: 'rec-2', body: 'seawall in another recording' }),
    ])

    // Library search sees everything...
    expect(index.search('seawall')).toHaveLength(5)
    // ...but searching while reviewing must only jump to transcript lines of
    // the recording being reviewed.
    const scoped = index.search('seawall', 'rec-1')
    expect(scoped).toHaveLength(1)
    expect(scoped[0]!.snippet).toContain('transcript')
  })

  it('brackets matches in the snippet and marks truncation', () => {
    index.replaceRecording('rec-1', [
      row({ body: `${'filler '.repeat(30)}seawall ${'tail '.repeat(30)}`.trim() }),
    ])

    const snippet = index.search('seawall')[0]!.snippet
    expect(snippet).toContain('[seawall]')
    expect(snippet.startsWith('…')).toBe(true)
    expect(snippet.endsWith('…')).toBe(true)
  })

  it('flags comment hits so the library can label them', () => {
    index.replaceRecording('rec-1', [
      row({ body: 'a note to self', kind: 'comment', speakerId: '' }),
    ])

    const hit = index.search('note')[0]!
    expect(hit.isComment).toBe(true)
    expect(hit.speakerId).toBeNull()
  })

  it('caps results at 100', () => {
    index.replaceRecording(
      'rec-1',
      Array.from({ length: 150 }, (_, position) =>
        row({ body: `seawall ${position}`, timestampMs: position * 1_000 }),
      ),
    )

    expect(index.search('seawall')).toHaveLength(100)
  })

  it('replaces rather than duplicates a recording on rebuild', () => {
    index.replaceRecording('rec-1', [row({ body: 'first version' })])
    index.replaceRecording('rec-1', [row({ body: 'second version' })])

    expect(index.size).toBe(1)
    expect(index.search('first')).toHaveLength(0)
    expect(index.search('second')).toHaveLength(1)
  })

  it('drops a removed recording from results', () => {
    index.replaceRecording('rec-1', [row()])
    index.removeRecording('rec-1')

    expect(index.search('seawall')).toEqual([])
  })
})

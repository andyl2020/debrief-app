import type { SearchHit } from './models'

/**
 * Replaces `app/src/main/java/com/andyluu/debrief/data/SearchRepository.kt`.
 *
 * Android uses SQLite FTS5. There is no FTS5 in the browser, so this is a small
 * inverted index that reproduces the *semantics* the Android app relies on:
 *
 *  - the query is split on whitespace and every term must match (AND)
 *  - each term is a PREFIX match, so "seaw" finds "seawall"
 *  - matches are wrapped in `[` `]` with `…` marking truncation, like FTS5's
 *    `snippet(transcript_fts, 4, '[', ']', '…', 16)`
 *  - at most 100 results
 *
 * The scoping rule is the important one and was a deliberate fix in v1.4.1: the
 * in-player search must only ever match transcript BODY rows, so searching
 * while reviewing never jumps you to a filename or your own comment. Library
 * search deliberately spans everything.
 */

export type IndexedRowKind = 'transcript' | 'comment' | 'summary' | 'filename'

export interface IndexedRow {
  recordingId: string
  recordingName: string
  speakerId: string
  timestampMs: number
  body: string
  kind: IndexedRowKind
}

const SNIPPET_TOKEN_WINDOW = 16
const RESULT_LIMIT = 100

interface Posting {
  row: number
  positions: number[]
}

export class SearchIndex {
  private rows: IndexedRow[] = []
  private rowTokens: string[][] = []
  /** Sorted unique tokens, so a prefix query is a binary-searched contiguous range. */
  private tokens: string[] = []
  private postings = new Map<string, Posting[]>()
  private dirty = false

  /** Drops and re-adds every row for one recording. Mirrors `SearchRepository.rebuild`. */
  replaceRecording(recordingId: string, rows: IndexedRow[]): void {
    this.rows = this.rows.filter((row) => row.recordingId !== recordingId)
    this.rows.push(...rows)
    this.dirty = true
  }

  removeRecording(recordingId: string): void {
    this.rows = this.rows.filter((row) => row.recordingId !== recordingId)
    this.dirty = true
  }

  clear(): void {
    this.rows = []
    this.dirty = true
  }

  get size(): number {
    return this.rows.length
  }

  private rebuildIfNeeded(): void {
    if (!this.dirty) return
    this.rowTokens = this.rows.map((row) => tokenize(row.body))
    this.postings = new Map()
    this.rowTokens.forEach((tokens, rowIndex) => {
      tokens.forEach((token, position) => {
        let list = this.postings.get(token)
        if (!list) {
          list = []
          this.postings.set(token, list)
        }
        const last = list[list.length - 1]
        if (last && last.row === rowIndex) last.positions.push(position)
        else list.push({ row: rowIndex, positions: [position] })
      })
    })
    this.tokens = [...this.postings.keys()].sort()
    this.dirty = false
  }

  /**
   * @param recordingId when provided, restricts to that recording AND to
   * transcript rows only - the in-player search scope.
   */
  search(query: string, recordingId?: string | null): SearchHit[] {
    this.rebuildIfNeeded()

    const terms = query.trim().split(/\s+/).filter((term) => term.length > 0)
    if (terms.length === 0) return []

    const scoped = recordingId !== null && recordingId !== undefined

    // AND across terms: intersect the row sets, keeping per-row hit counts for scoring.
    let candidates: Map<number, number> | null = null
    for (const term of terms) {
      const matches = this.rowsMatchingPrefix(normalize(term))
      if (matches.size === 0) return []
      if (candidates === null) {
        candidates = matches
      } else {
        const intersection = new Map<number, number>()
        for (const [row, count] of matches) {
          const existing = candidates.get(row)
          if (existing !== undefined) intersection.set(row, existing + count)
        }
        candidates = intersection
      }
      if (candidates.size === 0) return []
    }
    if (candidates === null) return []

    const normalizedTerms = terms.map(normalize)
    const scored: Array<{ hit: SearchHit; score: number; timestampMs: number }> = []

    for (const [rowIndex, hitCount] of candidates) {
      const row = this.rows[rowIndex]!
      if (scoped && (row.recordingId !== recordingId || row.kind !== 'transcript')) continue

      const tokens = this.rowTokens[rowIndex]!
      // Shorter rows containing the same number of hits are more relevant, which
      // is the same intuition BM25 encodes with its length normalisation.
      const score = hitCount / Math.sqrt(Math.max(1, tokens.length))
      scored.push({
        score,
        timestampMs: row.timestampMs,
        hit: {
          recordingId: row.recordingId,
          recordingName: row.recordingName,
          timestampMs: row.timestampMs,
          speakerId: row.speakerId.trim().length > 0 ? row.speakerId : null,
          snippet: buildSnippet(row.body, normalizedTerms),
          isComment: row.kind === 'comment',
        },
      })
    }

    return scored
      .sort((a, b) => b.score - a.score || a.timestampMs - b.timestampMs)
      .slice(0, RESULT_LIMIT)
      .map((entry) => entry.hit)
  }

  /** Rows containing at least one token starting with `prefix`, with hit counts. */
  private rowsMatchingPrefix(prefix: string): Map<number, number> {
    const result = new Map<number, number>()
    if (prefix.length === 0) return result

    let low = lowerBound(this.tokens, prefix)
    while (low < this.tokens.length) {
      const token = this.tokens[low]!
      if (!token.startsWith(prefix)) break
      for (const posting of this.postings.get(token) ?? []) {
        result.set(posting.row, (result.get(posting.row) ?? 0) + posting.positions.length)
      }
      low += 1
    }
    return result
  }
}

/**
 * Mirrors FTS5's `snippet(..., '[', ']', '…', 16)`: a window of about 16 tokens
 * centred on the first match, with matched tokens bracketed.
 */
export function buildSnippet(body: string, normalizedTerms: string[]): string {
  const matches = [...body.matchAll(/\S+/g)]
  if (matches.length === 0) return body

  const isMatch = (raw: string) =>
    normalizedTerms.some((term) => term.length > 0 && normalize(raw).startsWith(term))

  const firstMatch = matches.findIndex((match) => isMatch(match[0]))
  if (firstMatch < 0) {
    return matches.length <= SNIPPET_TOKEN_WINDOW
      ? body
      : `${matches.slice(0, SNIPPET_TOKEN_WINDOW).map((m) => m[0]).join(' ')} …`
  }

  const half = Math.floor(SNIPPET_TOKEN_WINDOW / 2)
  const start = Math.max(0, firstMatch - half)
  const end = Math.min(matches.length, start + SNIPPET_TOKEN_WINDOW)

  const rendered = matches
    .slice(start, end)
    .map((match) => (isMatch(match[0]) ? `[${match[0]}]` : match[0]))
    .join(' ')

  return `${start > 0 ? '…' : ''}${rendered}${end < matches.length ? ' …' : ''}`
}

/**
 * Lowercase and strip surrounding punctuation so "seawall," indexes as
 * "seawall". Kept intentionally simple - FTS5's unicode61 tokenizer does
 * effectively this for the Latin text these transcripts contain.
 */
export function normalize(token: string): string {
  return token.toLowerCase().replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, '')
}

export function tokenize(text: string): string[] {
  return text
    .split(/\s+/)
    .map(normalize)
    .filter((token) => token.length > 0)
}

function lowerBound(sorted: string[], target: string): number {
  let low = 0
  let high = sorted.length
  while (low < high) {
    const mid = (low + high) >>> 1
    if (sorted[mid]! < target) low = mid + 1
    else high = mid
  }
  return low
}

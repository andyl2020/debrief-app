import { formatTimestamp } from './format'
import { isOpenManualSet } from './manual-sets'
import type { Comment, ConversationSet } from './models'

/**
 * Ported from `buildChapterEntries` in
 * `app/src/main/java/com/andyluu/debrief/ui/ChaptersDrawer.kt:57-86`.
 *
 * Chapters is one chronological table of contents over two different things:
 * the sections the user marked, and the comments they left. Merging them is the
 * whole point - it is how you skim a six-hour recording.
 */

export type ChapterEntryType = 'SET' | 'COMMENT'

/** SET sorts before COMMENT at an identical timestamp, matching Kotlin's enum ordinal. */
const TYPE_ORDER: Record<ChapterEntryType, number> = { SET: 0, COMMENT: 1 }

export interface ChapterEntry {
  id: string
  timestampMs: number
  type: ChapterEntryType
  title: string
  detail: string
  endMs: number | null
  colorIndex: number | null
}

export function buildChapterEntries(
  sets: ConversationSet[],
  comments: Comment[],
): ChapterEntry[] {
  const setEntries: ChapterEntry[] = sets.map((set) => ({
    id: `set:${set.id}`,
    timestampMs: set.startMs,
    type: 'SET',
    title: set.title.trim().length > 0 ? set.title : `Set ${set.orderIndex + 1}`,
    detail: isOpenManualSet(set)
      ? 'Open set. Mark an end point when this section finishes.'
      : [`${formatTimestamp(set.startMs)} to ${formatTimestamp(set.endMs)}`, set.summary]
          .filter((part) => part.trim().length > 0)
          .join(' · '),
    endMs: set.endMs,
    colorIndex: set.orderIndex,
  }))

  const commentEntries: ChapterEntry[] = comments.map((comment) => ({
    id: `comment:${comment.id}`,
    timestampMs: comment.timestampMs,
    type: 'COMMENT',
    title: comment.text,
    detail: '',
    endMs: null,
    colorIndex: null,
  }))

  // Array.prototype.sort is stable, so entries that tie on both keys keep their
  // insertion order - same as Kotlin's sortedWith.
  return [...setEntries, ...commentEntries].sort(
    (a, b) => a.timestampMs - b.timestampMs || TYPE_ORDER[a.type] - TYPE_ORDER[b.type],
  )
}

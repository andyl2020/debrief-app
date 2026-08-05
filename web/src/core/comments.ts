import type { Comment, TranscriptSegment } from './models'

/**
 * Ported from `leadingComments` / `commentsForSegment` in
 * `app/src/main/java/com/andyluu/debrief/ui/Screens.kt:1634-1650`.
 *
 * These exist because of a real bug fixed in v1.2.2: comments were attached to
 * the segment whose time range contained them, so a comment left during silence
 * - in a gap between transcript lines, or after the last line - belonged to no
 * segment and silently vanished from the review screen.
 *
 * The fix is that each segment owns the window from its own start up to just
 * before the next segment starts, and the final segment's window extends to the
 * end of the audio.
 */

/** Comments made before the first transcript line. Shown above the transcript. */
export function leadingComments(
  comments: Comment[],
  segments: TranscriptSegment[],
): Comment[] {
  const first = segments[0]
  if (!first) return comments
  return comments.filter((comment) => comment.timestampMs < first.startMs)
}

/** Comments belonging to the segment at `index`, including any following gap. */
export function commentsForSegment(
  comments: Comment[],
  segments: TranscriptSegment[],
  index: number,
  durationMs: number,
): Comment[] {
  const segment = segments[index]
  if (!segment) return []
  const next = segments[index + 1]
  const windowEnd = next ? next.startMs - 1 : Math.max(segment.endMs, durationMs)
  return comments.filter(
    (comment) => comment.timestampMs >= segment.startMs && comment.timestampMs <= windowEnd,
  )
}

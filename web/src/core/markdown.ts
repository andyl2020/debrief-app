import { formatTimestamp } from './format'
import type { Comment, SpeakerAlias, TranscriptSegment } from './models'

/**
 * Ported from `exportMarkdown` in
 * `app/src/main/java/com/andyluu/debrief/ui/ViewModels.kt:814-836`.
 *
 * Comments are interleaved as blockquotes under the transcript line they fall
 * inside. Any comment that lands in a gap - and so matches no line - is
 * collected into a trailing `## Comments` section rather than being dropped,
 * which is the export-side counterpart of the gap-comment fix in `comments.ts`.
 */
export interface MarkdownExportInput {
  displayName: string
  segments: TranscriptSegment[]
  comments: Comment[]
  aliases: SpeakerAlias[]
}

export function exportMarkdown(input: MarkdownExportInput): string {
  const { displayName, segments, comments, aliases } = input
  const aliasBySpeaker = new Map(aliases.map((alias) => [alias.speakerId, alias.displayName]))

  const lines: string[] = []
  lines.push(`# ${displayName}`)
  lines.push('')

  for (const segment of segments) {
    const time = formatTimestamp(segment.startMs)
    const speaker = aliasBySpeaker.get(segment.speakerId) ?? segment.speakerId
    lines.push(`**[${time}] ${speaker}:** ${segment.text}`)

    for (const comment of comments) {
      if (comment.timestampMs >= segment.startMs && comment.timestampMs <= segment.endMs) {
        lines.push('')
        lines.push(`> **Comment [${formatTimestamp(comment.timestampMs)}]:** ${comment.text}`)
      }
    }
    lines.push('')
  }

  const unmatched = comments.filter(
    (comment) =>
      !segments.some(
        (segment) =>
          comment.timestampMs >= segment.startMs && comment.timestampMs <= segment.endMs,
      ),
  )
  if (unmatched.length > 0) {
    lines.push('## Comments')
    for (const comment of unmatched) {
      lines.push(`- [${formatTimestamp(comment.timestampMs)}] ${comment.text}`)
    }
  }

  return lines.length > 0 ? `${lines.join('\n')}\n` : ''
}

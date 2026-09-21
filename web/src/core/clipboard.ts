import { formatTimestamp } from './format'
import type { SpeakerAlias, TranscriptSegment } from './models'

export interface TranscriptClipboardInput {
  segments: TranscriptSegment[]
  aliases: SpeakerAlias[]
  textForSegment?: (segment: TranscriptSegment) => string
}

/**
 * Plain text is intentionally used here instead of Markdown. It pastes cleanly
 * into messages, email, notes and coaching tools while retaining the complete
 * timeline and every resolved speaker label.
 */
export function formatTranscriptForClipboard(input: TranscriptClipboardInput): string {
  const aliasBySpeaker = new Map(
    input.aliases.map((alias) => [alias.speakerId, alias.displayName]),
  )

  return input.segments
    .map((segment) => {
      const speaker = aliasBySpeaker.get(segment.speakerId) ?? segment.speakerId
      const text = input.textForSegment?.(segment) ?? segment.text
      return `[${formatTimestamp(segment.startMs)}] ${speaker}: ${text.trim()}`
    })
    .join('\n\n')
}

/**
 * Async Clipboard is the standards path and works on the production HTTPS
 * origins. The selection fallback keeps copy usable in older embedded/Desktop
 * browsers that still expose execCommand but not navigator.clipboard.
 */
export async function copyTextToClipboard(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return
    } catch {
      // A browser can expose the API but reject it because of a permissions
      // policy. Try the synchronous user-gesture fallback before reporting it.
    }
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.readOnly = true
  textarea.setAttribute('aria-hidden', 'true')
  textarea.style.position = 'fixed'
  textarea.style.left = '-9999px'
  textarea.style.top = '0'
  document.body.append(textarea)
  textarea.select()
  textarea.setSelectionRange(0, textarea.value.length)
  const copied = document.execCommand?.('copy') === true
  textarea.remove()
  if (!copied) throw new Error('Clipboard access was rejected by this browser.')
}

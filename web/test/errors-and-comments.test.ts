import { describe, expect, it } from 'vitest'
import { IoError, SecurityError, TranscriptionError, isTerminal, userMessage } from '../src/core/errors'
import { commentsForSegment, leadingComments } from '../src/core/comments'
import { comment, segment } from './helpers'

/** Ported from `app/src/test/java/com/andyluu/debrief/ui/ErrorAndCommentHandlingTest.kt`. */
describe('Error and comment handling', () => {
  it('securityErrorsGiveRecoveryInstructionsWithoutInternalDetails', () => {
    const message = userMessage("Couldn't save.", new SecurityError('content://private/path'))

    expect(message).toContain('Re-link')
    expect(message).not.toContain('content://')
  })

  it('ioErrorsKeepTheActionContext', () => {
    const message = userMessage("Couldn't add the comment.", new IoError('disk failed'))

    expect(message.startsWith("Couldn't add the comment.")).toBe(true)
    expect(message).toContain('try again')
  })

  it('commentsInTranscriptGapsRemainVisibleWithPreviousSegment', () => {
    const segments = [segment(1_000, 5_000), segment(20_000, 25_000)]
    const gapComment = comment('Between transcript lines', 12_000)
    const leading = comment('Before speech', 500)

    expect(leadingComments([leading, gapComment], segments)).toEqual([leading])
    expect(commentsForSegment([leading, gapComment], segments, 0, 30_000)).toEqual([gapComment])
  })

  it('commentsAfterFinalTranscriptLineRemainVisible', () => {
    const segments = [segment(1_000, 5_000)]
    const trailing = comment('After the final line', 18_000)

    expect(commentsForSegment([trailing], segments, 0, 20_000)).toEqual([trailing])
  })
})

/**
 * Web-specific additions. The browser throws DOMExceptions where the JVM throws
 * SecurityException/IOException, so those must land in the same buckets.
 */
describe('userMessage on browser errors', () => {
  it('maps a denied file-system permission to the re-link instruction', () => {
    const message = userMessage("Couldn't open the folder.", new DOMException('denied', 'NotAllowedError'))

    expect(message).toContain('Re-link')
  })

  it('maps a full storage quota to the storage advice', () => {
    const message = userMessage("Couldn't save the transcript.", new DOMException('full', 'QuotaExceededError'))

    expect(message.startsWith("Couldn't save the transcript.")).toBe(true)
    expect(message).toContain('Check storage')
  })

  it('maps a failed fetch to the storage and connection advice', () => {
    const message = userMessage('Upload failed.', new TypeError('Failed to fetch'))

    expect(message).toContain('try again')
  })

  it('falls back to the caller message for an unrecognised throw', () => {
    expect(userMessage('Something went wrong.', 'a bare string')).toBe('Something went wrong.')
  })
})

describe('retry classification', () => {
  it('treats a provider rejection as terminal so it is not retried', () => {
    // Mirrors TranscriptionWorker.kt:105 - a bad key or "no speech" does not
    // get better by trying again, but a dropped connection might.
    expect(isTerminal(new TranscriptionError('No speech was detected'))).toBe(true)
    expect(isTerminal(new TypeError('Failed to fetch'))).toBe(false)
    expect(isTerminal(new IoError('disk failed'))).toBe(false)
  })
})

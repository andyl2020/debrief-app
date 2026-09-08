/**
 * Ported from `userMessage` in
 * `app/src/main/java/com/andyluu/debrief/ui/ViewModels.kt:80-85`.
 *
 * The Android version switches on JVM exception types. The browser has no
 * SecurityException/IOException, so we declare the equivalent error classes and
 * additionally map the DOMException names browsers actually throw for the same
 * situations (a denied file-system permission, a failed write, a full disk).
 *
 * The rule the Android tests pin down, and the reason this function exists at
 * all: a permission failure must tell the user how to recover WITHOUT echoing
 * the internal path or URI back at them.
 */

/** A permission/authorisation failure. Analogue of Kotlin's `SecurityException`. */
export class SecurityError extends Error {
  override readonly name = 'SecurityError'
}

/** A storage or transport failure. Analogue of Kotlin's `IOException`. */
export class IoError extends Error {
  override readonly name = 'IoError'
}

/** A caller-supplied value was invalid. Analogue of Kotlin's `IllegalArgumentException`. */
export class ValidationError extends Error {
  override readonly name = 'ValidationError'
}

/**
 * A terminal transcription failure. Analogue of
 * `TranscriptionException` in `TranscriptionProvider.kt:25`.
 *
 * The distinction matters: `TranscriptionWorker.kt:105` retries anything EXCEPT
 * this type, because these represent a bad key, a rejected file or a provider
 * saying "no speech" - none of which get better by trying again.
 */
export class TranscriptionError extends Error {
  override readonly name = 'TranscriptionError'
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options)
  }
}

/** DOMException names that mean "the user or the platform denied us access". */
const PERMISSION_ERROR_NAMES = new Set([
  'NotAllowedError',
  'SecurityError',
  'NotReadableError',
])

/** DOMException names that mean "the storage layer could not complete the write". */
const IO_ERROR_NAMES = new Set([
  'QuotaExceededError',
  'NoModificationAllowedError',
  'InvalidStateError',
  'AbortError',
  'NotFoundError',
])

const MAX_VALIDATION_MESSAGE_LENGTH = 180

/**
 * Turns any thrown value into something worth showing a user, without leaking
 * internal paths, URIs or stack detail.
 */
export function userMessage(fallback: string, error: unknown): string {
  if (error instanceof SecurityError || isDomExceptionNamed(error, PERMISSION_ERROR_NAMES)) {
    return 'Permission was denied. Re-link the recordings folder and try again.'
  }
  if (error instanceof IoError || isDomExceptionNamed(error, IO_ERROR_NAMES) || isNetworkError(error)) {
    return `${fallback} Check storage and your connection, then try again.`
  }
  if (error instanceof ValidationError) {
    const message = error.message?.trim()
    return message ? message.slice(0, MAX_VALIDATION_MESSAGE_LENGTH) : fallback
  }
  if (error instanceof TranscriptionError) {
    const message = error.message?.trim()
    return message ? message.slice(0, MAX_VALIDATION_MESSAGE_LENGTH) : fallback
  }
  return fallback
}

function isDomExceptionNamed(error: unknown, names: Set<string>): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'name' in error &&
    typeof (error as { name: unknown }).name === 'string' &&
    names.has((error as { name: string }).name)
  )
}

/**
 * `fetch` rejects with a bare `TypeError` for DNS failures, offline states and
 * CORS rejections. Those are transport problems, so they get the IO treatment.
 */
function isNetworkError(error: unknown): boolean {
  return error instanceof TypeError && /fetch|network|load failed/i.test(error.message)
}

/** True when the error should NOT be retried. Mirrors `TranscriptionWorker.kt:105`. */
export function isTerminal(error: unknown): boolean {
  return error instanceof TranscriptionError
}

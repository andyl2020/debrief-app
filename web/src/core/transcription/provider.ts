import type { TranscriptSegment, TranscriptWord } from '../models'
import type { TranscriptionAudioQuality } from '../audio-quality'

/** Ported from `TranscriptionResult` in `TranscriptionProvider.kt:9-12`. */
export interface TranscriptionResult {
  segments: TranscriptSegment[]
  words: TranscriptWord[]
}

/**
 * What the browser hands a provider. `body` is a `Blob`/`File` passed straight
 * to `fetch` so the browser streams it - a four-hour recording must never be
 * read into memory first.
 */
export interface TranscriptionRequest {
  recordingId: string
  body: Blob
  mimeType: string
  apiKey: string
  keyterms: string[]
  /** Called when a resumable provider assigns a job id, so a reopened tab can rejoin it. */
  onJobId?: (jobId: string) => void | Promise<void>
  /** An already-known job id to rejoin instead of uploading again. */
  resumeJobId?: string | null
  signal?: AbortSignal
  /** Coarse progress for the library card, 0..1, or null when indeterminate. */
  onProgress?: (stage: string, fraction: number | null) => void
}

export interface TranscriptionProvider {
  readonly id: string
  readonly label: string
  /** True when a dropped connection can rejoin the job instead of re-uploading. */
  readonly resumable: boolean
  transcribe(request: TranscriptionRequest): Promise<TranscriptionResult>
}

export type ProviderId = 'assemblyai' | 'deepgram'

/** Ported from `TranscriptionWorker.kt:75` - keyterms are newline or comma separated. */
export function parseKeyterms(raw: string): string[] {
  return raw
    .split(/\r?\n/)
    .flatMap((line) => line.split(','))
    .map((term) => term.trim())
    .filter((term) => term.length > 0)
}

export interface QualitySettings {
  quality: TranscriptionAudioQuality
}

import { TranscriptionError } from '../errors'
import { makeSegment, makeWord, type TranscriptSegment, type TranscriptWord } from '../models'
import { segmentsFromWords } from '../segments'
import type {
  TranscriptionProvider,
  TranscriptionRequest,
  TranscriptionResult,
} from './provider'

/**
 * Ported from
 * `app/src/main/java/com/andyluu/debrief/transcription/DeepgramProvider.kt`.
 *
 * Deepgram is a single synchronous request: the whole recording is POSTed and
 * the transcript comes back in the response. That is fine on Android in a
 * foreground service, but a browser tab - especially on iOS - can be suspended
 * mid-request, which loses the upload. It is kept as the fallback provider it
 * is on Android, and the UI warns about this on mobile.
 */
export class DeepgramProvider implements TranscriptionProvider {
  readonly id = 'deepgram'
  readonly label = 'Deepgram Nova-3'
  readonly resumable = false

  /** Ported from `requestUrl` (DeepgramProvider.kt:68-79). */
  requestUrl(keyterms: string[]): URL {
    const url = new URL('https://api.deepgram.com/v1/listen')
    url.searchParams.set('model', 'nova-3')
    url.searchParams.set('language', 'en')
    // `diarize_model=latest` supersedes the deprecated `diarize` flag; the
    // Android test pins this so the old flag never creeps back in.
    url.searchParams.set('diarize_model', 'latest')
    url.searchParams.set('utterances', 'true')
    url.searchParams.set('punctuate', 'true')
    url.searchParams.set('smart_format', 'true')
    for (const term of keyterms.slice(0, 100)) {
      if (term.trim().length > 0) url.searchParams.append('keyterm', term.trim())
    }
    return url
  }

  async transcribe(request: TranscriptionRequest): Promise<TranscriptionResult> {
    const { body, mimeType, apiKey, keyterms, signal, onProgress } = request
    onProgress?.('Uploading to Deepgram', null)

    let response: Response
    try {
      response = await fetch(this.requestUrl(keyterms), {
        method: 'POST',
        headers: {
          Authorization: `Token ${apiKey}`,
          Accept: 'application/json',
          ...(mimeType ? { 'Content-Type': mimeType } : {}),
        },
        body,
        signal: signal ?? null,
      })
    } catch (error) {
      // Leave transport failures un-wrapped so the worker retries them.
      throw error
    }

    const payload = await response.text()
    if (!response.ok) {
      // Deepgram puts a human-readable reason in `err_msg`; fall back to the
      // status code when the body is not the JSON we expect.
      let message: string | null = null
      try {
        const parsed = JSON.parse(payload) as { err_msg?: unknown }
        if (typeof parsed.err_msg === 'string') message = parsed.err_msg
      } catch {
        message = null
      }
      throw new TranscriptionError(message ?? `Deepgram request failed (${response.status})`)
    }

    onProgress?.('Parsing transcript', null)
    return this.parse(request.recordingId, payload)
  }

  /** Ported from `parse` (DeepgramProvider.kt:81-119). */
  parse(recordingId: string, payload: string): TranscriptionResult {
    let root: Record<string, unknown>
    try {
      root = JSON.parse(payload) as Record<string, unknown>
    } catch (error) {
      throw new TranscriptionError('Deepgram returned a response Debrief could not read', {
        cause: error,
      })
    }

    const results = asObject(root['results'])
    if (!results) throw new TranscriptionError('Deepgram returned no results')

    const utterances = asArray(results['utterances']) ?? []
    const segments: TranscriptSegment[] = []
    for (const element of utterances) {
      const item = asObject(element)
      if (!item) continue
      const text = asString(item['transcript'])?.trim() ?? ''
      if (text.length === 0) continue
      segments.push(
        makeSegment({
          recordingId,
          speakerId: speakerLabel(asInt(item['speaker']) ?? 0),
          startMs: seconds(item['start']),
          endMs: seconds(item['end']),
          text,
        }),
      )
    }

    // The channel-level word stream is Deepgram's complete recognized timeline.
    // In some responses the convenience utterance list omits words, so deriving
    // display segments from all words prevents recognized sections from
    // disappearing between utterances.
    const firstChannel = asObject(asArray(results['channels'])?.[0])
    const firstAlternative = asObject(asArray(firstChannel?.['alternatives'])?.[0])
    const channelWords = asArray(firstAlternative?.['words'])
    const utteranceWords = utterances.flatMap(
      (element) => asArray(asObject(element)?.['words']) ?? [],
    )
    const wordsJson = channelWords ?? utteranceWords

    const words: TranscriptWord[] = []
    for (const element of wordsJson) {
      const item = asObject(element)
      if (!item) continue
      const text = (asString(item['punctuated_word']) ?? asString(item['word']))?.trim() ?? ''
      if (text.length === 0) continue
      words.push(
        makeWord({
          recordingId,
          speakerId: speakerLabel(asInt(item['speaker']) ?? 0),
          startMs: seconds(item['start']),
          endMs: seconds(item['end']),
          text,
          confidence: asNumber(item['confidence']),
        }),
      )
    }

    if (segments.length === 0 && words.length === 0) {
      throw new TranscriptionError('No speech was detected')
    }

    const derived = segmentsFromWords(recordingId, words)
    return { segments: derived.length > 0 ? derived : segments, words }
  }
}

/** `Speaker A`, `Speaker B`, ... from Deepgram's integer speaker index. */
function speakerLabel(index: number): string {
  return `Speaker ${String.fromCharCode('A'.charCodeAt(0) + index)}`
}

/** Deepgram reports times in fractional seconds; the app stores milliseconds. */
function seconds(value: unknown): number {
  return Math.trunc((asNumber(value) ?? 0) * 1000)
}

function asObject(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null
}

function asArray(value: unknown): unknown[] | null {
  return Array.isArray(value) ? value : null
}

function asString(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

function asNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function asInt(value: unknown): number | null {
  const parsed = asNumber(value)
  return parsed === null ? null : Math.trunc(parsed)
}

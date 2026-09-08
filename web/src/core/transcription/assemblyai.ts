import { TranscriptionError } from '../errors'
import { makeSegment, makeWord, type TranscriptSegment, type TranscriptWord } from '../models'
import { segmentsFromWords } from '../segments'
import type {
  TranscriptionProvider,
  TranscriptionRequest,
  TranscriptionResult,
} from './provider'

const POLL_INTERVAL_MS = 10_000
const MAX_POLLS = 360 // 360 x 10s = 60 minutes, matching AssemblyAiProvider.kt:67

/**
 * Ported from
 * `app/src/main/java/com/andyluu/debrief/transcription/AssemblyAiProvider.kt`.
 *
 * AssemblyAI is upload -> create job -> poll. That shape is what makes it the
 * right default in a browser: the recording is uploaded once, and if the tab is
 * backgrounded or closed mid-job the transcript id is enough to rejoin the same
 * job later rather than re-uploading hours of audio. On Android it is already
 * the recommended provider for noisy field recordings, so this matches rather
 * than diverges from the app.
 */
export class AssemblyAiProvider implements TranscriptionProvider {
  readonly id = 'assemblyai'
  readonly label = 'AssemblyAI'
  readonly resumable = true

  async transcribe(request: TranscriptionRequest): Promise<TranscriptionResult> {
    const { apiKey, keyterms, signal, onProgress, onJobId } = request

    let transcriptId = request.resumeJobId ?? null

    if (!transcriptId) {
      onProgress?.('Uploading to AssemblyAI', null)
      const upload = await this.call(
        'https://api.assemblyai.com/v2/upload',
        {
          method: 'POST',
          headers: { authorization: apiKey },
          body: request.body,
          signal: signal ?? null,
        },
      )
      const uploadUrl = asString(upload['upload_url'])
      if (!uploadUrl) throw new TranscriptionError('AssemblyAI upload did not return a URL')

      onProgress?.('Queued with AssemblyAI', null)
      const requestBody: Record<string, unknown> = {
        audio_url: uploadUrl,
        speaker_labels: true,
      }
      if (keyterms.length > 0) {
        requestBody['word_boost'] = keyterms.slice(0, 100)
        requestBody['boost_param'] = 'high'
      }

      const created = await this.call('https://api.assemblyai.com/v2/transcript', {
        method: 'POST',
        headers: { authorization: apiKey, 'content-type': 'application/json' },
        body: JSON.stringify(requestBody),
        signal: signal ?? null,
      })
      transcriptId = asString(created['id'])
      if (!transcriptId) throw new TranscriptionError('AssemblyAI did not return a transcript ID')
      // Persist before the first poll so a tab closed one second later can still resume.
      await onJobId?.(transcriptId)
    }

    for (let attempt = 0; attempt < MAX_POLLS; attempt += 1) {
      await delay(POLL_INTERVAL_MS, signal)
      const result = await this.call(
        `https://api.assemblyai.com/v2/transcript/${transcriptId}`,
        { method: 'GET', headers: { authorization: apiKey }, signal: signal ?? null },
      )
      const status = asString(result['status'])
      if (status === 'completed') {
        onProgress?.('Parsing transcript', null)
        return this.parse(request.recordingId, result)
      }
      if (status === 'error') {
        throw new TranscriptionError(asString(result['error']) ?? 'AssemblyAI failed')
      }
      onProgress?.(
        status === 'processing' ? 'Transcribing' : 'Waiting for AssemblyAI',
        (attempt + 1) / MAX_POLLS,
      )
    }

    throw new TranscriptionError('AssemblyAI timed out while processing the recording')
  }

  private async call(url: string, init: RequestInit): Promise<Record<string, unknown>> {
    const response = await fetch(url, init)
    const body = await response.text()
    if (!response.ok) {
      throw new TranscriptionError(`AssemblyAI request failed (${response.status})`)
    }
    try {
      return JSON.parse(body) as Record<string, unknown>
    } catch (error) {
      throw new TranscriptionError('AssemblyAI returned a response Debrief could not read', {
        cause: error,
      })
    }
  }

  /** Ported from `parse` (AssemblyAiProvider.kt:87-115). */
  parse(recordingId: string, root: Record<string, unknown>): TranscriptionResult {
    const words: TranscriptWord[] = []
    for (const element of asArray(root['words']) ?? []) {
      const item = asObject(element)
      if (!item) continue
      const text = asString(item['text'])?.trim() ?? ''
      if (text.length === 0) continue
      words.push(
        makeWord({
          recordingId,
          speakerId: `Speaker ${asSpeaker(item['speaker']) ?? 'A'}`,
          startMs: Math.trunc(asNumber(item['start']) ?? 0),
          endMs: Math.trunc(asNumber(item['end']) ?? 0),
          text,
          confidence: asNumber(item['confidence']),
        }),
      )
    }

    const segments: TranscriptSegment[] = []
    for (const element of asArray(root['utterances']) ?? []) {
      const item = asObject(element)
      if (!item) continue
      const text = asString(item['text'])?.trim() ?? ''
      if (text.length === 0) continue
      segments.push(
        makeSegment({
          recordingId,
          speakerId: `Speaker ${asSpeaker(item['speaker']) ?? 'A'}`,
          startMs: Math.trunc(asNumber(item['start']) ?? 0),
          endMs: Math.trunc(asNumber(item['end']) ?? 0),
          text,
        }),
      )
    }

    if (segments.length === 0 && words.length === 0) {
      throw new TranscriptionError('AssemblyAI returned no speech')
    }

    const derived = segmentsFromWords(recordingId, words)
    return { segments: derived.length > 0 ? derived : segments, words }
  }
}

function delay(milliseconds: number, signal?: AbortSignal | null): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('Transcription was cancelled', 'AbortError'))
      return
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }, milliseconds)
    function onAbort() {
      clearTimeout(timer)
      reject(new DOMException('Transcription was cancelled', 'AbortError'))
    }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

/** AssemblyAI speaker labels are already letters ("A"), but tolerate numbers. */
function asSpeaker(value: unknown): string | null {
  if (typeof value === 'string' && value.length > 0) return value
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  return null
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

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Repository } from '../src/state/repository'
import { runTranscription } from '../src/state/transcription-job'
import { freshDatabase } from './fresh-db'
import type { AudioSource, StorageAdapter } from '../src/storage/adapter'
import { recording as makeRecording } from './helpers'

/**
 * Web-specific, pinning the retry and failure rules carried over verbatim from
 * `TranscriptionWorker.kt`. These are the behaviours that decide whether a
 * six-hour upload is retried pointlessly, abandoned too early, or allowed to
 * destroy a transcript that actually succeeded.
 */

class StubAdapter implements StorageAdapter {
  readonly mode = 'browser-storage' as const
  readonly label = 'Stub'
  supportsAutomaticSidecars = false
  sidecars = new Map<string, string>()
  sidecarWritesFail = false

  async list(): Promise<AudioSource[]> {
    return []
  }
  async open(): Promise<Blob> {
    return new Blob(['audio'])
  }
  async add(): Promise<AudioSource[]> {
    return []
  }
  async remove(): Promise<void> {}
  async writeSidecar(name: string, contents: string): Promise<void> {
    if (this.sidecarWritesFail) throw new Error('folder unavailable')
    this.sidecars.set(name, contents)
  }
  async readSidecar(name: string): Promise<string | null> {
    return this.sidecars.get(name) ?? null
  }
}

const DEEPGRAM_OK = JSON.stringify({
  results: {
    channels: [
      {
        alternatives: [
          {
            words: [
              { word: 'hello', punctuated_word: 'Hello', start: 0.1, end: 0.5, speaker: 0, confidence: 0.9 },
              { word: 'there', punctuated_word: 'there.', start: 0.6, end: 1.0, speaker: 0, confidence: 0.9 },
            ],
          },
        ],
      },
    ],
    utterances: [{ start: 0.1, end: 1.0, speaker: 0, transcript: 'Hello there.' }],
  },
})

describe('runTranscription', () => {
  let adapter: StubAdapter
  let repository: Repository
  const recordingId = 'rec-1'

  const settings = {
    provider: 'deepgram' as const,
    keyterms: '',
    audioQuality: 'ORIGINAL' as const,
  }

  beforeEach(async () => {
    freshDatabase()

    adapter = new StubAdapter()
    repository = new Repository(adapter)
    await repository.saveRecording(
      makeRecording({ id: recordingId, sourceKey: 'audio', status: 'QUEUED', durationMs: 12_000 }),
    )
  })

  it('stores the transcript, a quality report and a READY status on success', async () => {
    const fetchMock = vi.fn(async () => new Response(DEEPGRAM_OK, { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await runTranscription(recordingId, {
      repository,
      settings,
      resolveApiKey: async () => 'dg-key',
    })

    expect(result.ok).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    const bundle = await repository.loadReview(recordingId)
    expect(bundle!.recording.status).toBe('READY')
    expect(bundle!.recording.errorMessage).toBeNull()
    expect(bundle!.segments.map((segment) => segment.text)).toEqual(['Hello there.'])
    expect(bundle!.words).toHaveLength(2)
    expect(bundle!.qualityReport).not.toBeNull()
    // The transcript should be searchable straight away.
    expect(repository.search.search('there', recordingId)).toHaveLength(1)
  })

  it('does not retry a provider rejection, and keeps the reason on the recording', async () => {
    // A rejected key or an unusable file will not fix itself, so burning two
    // more multi-hour uploads on it would be actively harmful.
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ err_msg: 'Invalid credentials' }), { status: 401 }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await runTranscription(recordingId, {
      repository,
      settings,
      resolveApiKey: async () => 'bad-key',
    })

    expect(result.ok).toBe(false)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    const stored = await repository.getRecording(recordingId)
    expect(stored!.status).toBe('FAILED')
    expect(stored!.errorMessage).toBe('Invalid credentials')
  })

  it('retries a dropped connection up to three times', async () => {
    const fetchMock = vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    })
    vi.stubGlobal('fetch', fetchMock)

    const result = await runTranscription(recordingId, {
      repository,
      settings,
      resolveApiKey: async () => 'dg-key',
    })

    expect(result.ok).toBe(false)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect((await repository.getRecording(recordingId))!.status).toBe('FAILED')
  })

  it('recovers when a retry succeeds', async () => {
    let attempt = 0
    const fetchMock = vi.fn(async () => {
      attempt += 1
      if (attempt === 1) throw new TypeError('Failed to fetch')
      return new Response(DEEPGRAM_OK, { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)

    const result = await runTranscription(recordingId, {
      repository,
      settings,
      resolveApiKey: async () => 'dg-key',
    })

    expect(result.ok).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect((await repository.getRecording(recordingId))!.status).toBe('READY')
  })

  it('refuses to upload at all when no API key is saved', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    const result = await runTranscription(recordingId, {
      repository,
      settings,
      resolveApiKey: async () => null,
    })

    expect(result.ok).toBe(false)
    expect(result.message).toBe('Add the Deepgram API key in Settings')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('truncates a very long provider message rather than storing it whole', async () => {
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ err_msg: 'x'.repeat(500) }), { status: 400 }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await runTranscription(recordingId, { repository, settings, resolveApiKey: async () => 'k' })

    expect((await repository.getRecording(recordingId))!.errorMessage).toHaveLength(300)
  })

  it('does NOT fail a completed transcription when the sidecar write fails afterwards', async () => {
    // The rule from TranscriptionWorker.kt:95-97 - a folder-sidecar failure is
    // reported, but it must never turn a successful transcription into FAILED.
    adapter.supportsAutomaticSidecars = true
    adapter.sidecarWritesFail = true
    vi.stubGlobal('fetch', vi.fn(async () => new Response(DEEPGRAM_OK, { status: 200 })))
    const messages: string[] = []

    const result = await runTranscription(recordingId, {
      repository,
      settings,
      resolveApiKey: async () => 'dg-key',
      onMessage: (message) => messages.push(message),
    })

    expect(result.ok).toBe(true)
    expect((await repository.getRecording(recordingId))!.status).toBe('READY')
    expect(messages.some((message) => message.includes('backup'))).toBe(true)
  })

  it('cancels cleanly without marking the recording failed', async () => {
    const controller = new AbortController()
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        controller.abort()
        throw new DOMException('aborted', 'AbortError')
      }),
    )

    const result = await runTranscription(recordingId, {
      repository,
      settings,
      resolveApiKey: async () => 'dg-key',
      signal: controller.signal,
    })

    expect(result.ok).toBe(false)
    expect((await repository.getRecording(recordingId))!.status).toBe('NEW')
  })
})

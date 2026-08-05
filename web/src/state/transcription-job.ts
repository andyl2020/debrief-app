import { TranscriptionError, isTerminal, userMessage } from '../core/errors'
import type { TranscriptionAudioQuality } from '../core/audio-quality'
import { AssemblyAiProvider } from '../core/transcription/assemblyai'
import { DeepgramProvider } from '../core/transcription/deepgram'
import { parseKeyterms, type ProviderId, type TranscriptionProvider } from '../core/transcription/provider'
import { analyzeTranscriptQuality } from '../core/transcription/quality'
import type { Repository } from './repository'

/**
 * The web counterpart of
 * `app/src/main/java/com/andyluu/debrief/transcription/TranscriptionWorker.kt`.
 *
 * WorkManager does not exist here, so the retry policy is implemented directly.
 * The rules are carried over unchanged because they encode real judgement:
 *
 *  - back up the user's markers BEFORE replacing the transcript, and refuse to
 *    proceed if that backup fails (TranscriptionWorker.kt:45-51)
 *  - a `TranscriptionError` is terminal; anything else gets up to 3 attempts
 *    (TranscriptionWorker.kt:105)
 *  - the failure message is truncated to 300 characters and stored on the
 *    recording so the library card can show it
 *  - a sidecar failure AFTER a successful transcription must NOT turn that
 *    transcription into FAILED (TranscriptionWorker.kt:95-97)
 */

const MAX_ATTEMPTS = 3
const ERROR_MESSAGE_LIMIT = 300

export interface TranscriptionJobSettings {
  provider: ProviderId
  keyterms: string
  audioQuality: TranscriptionAudioQuality
}

export interface TranscriptionJobDeps {
  repository: Repository
  settings: TranscriptionJobSettings
  /** Resolves the API key for a provider, or null when none is saved. */
  resolveApiKey: (provider: ProviderId) => Promise<string | null>
  onProgress?: (recordingId: string, stage: string, fraction: number | null) => void
  onMessage?: (message: string) => void
  signal?: AbortSignal
}

export function providerFor(id: ProviderId): TranscriptionProvider {
  return id === 'assemblyai' ? new AssemblyAiProvider() : new DeepgramProvider()
}

export function providerDisplayName(id: ProviderId): string {
  return id === 'assemblyai' ? 'AssemblyAI' : 'Deepgram'
}

export async function runTranscription(
  recordingId: string,
  deps: TranscriptionJobDeps,
): Promise<{ ok: boolean; message?: string }> {
  const { repository, settings, resolveApiKey, onProgress, onMessage, signal } = deps

  const recording = await repository.getRecording(recordingId)
  if (!recording) return { ok: false, message: 'That recording is no longer in the library.' }

  let attempt = 0
  let lastMessage = 'Transcription failed'

  while (attempt < MAX_ATTEMPTS) {
    attempt += 1
    try {
      // Secure the user's markers first. If this cannot be done, stop before
      // touching the transcript rather than risk replacing data we cannot restore.
      const preflight = await repository.checkpointSidecar(recordingId)
      if (!preflight.sidecarCurrent && preflight.protectedItemCount > 0 && repository.adapter.supportsAutomaticSidecars) {
        throw new TranscriptionError(
          'Debrief could not create a recovery copy of this recording’s chapters and bookmarks. ' +
            'Nothing was replaced; free storage and try again.',
        )
      }

      await repository.updateStatus(recordingId, 'TRANSCRIBING')

      const apiKey = await resolveApiKey(settings.provider)
      if (!apiKey) {
        throw new TranscriptionError(
          `Add the ${providerDisplayName(settings.provider)} API key in Settings`,
        )
      }

      const audio = await repository.adapter.open(recording.sourceKey)
      const provider = providerFor(settings.provider)
      const current = await repository.getRecording(recordingId)

      const result = await provider.transcribe({
        recordingId,
        body: audio,
        mimeType: recording.mimeType ?? 'application/octet-stream',
        apiKey,
        keyterms: parseKeyterms(settings.keyterms),
        signal,
        // Only rejoin a job that belongs to the provider currently selected.
        resumeJobId:
          current?.provider === settings.provider ? (current?.providerJobId ?? null) : null,
        onJobId: async (jobId) => {
          await repository.updateRecording(recordingId, {
            providerJobId: jobId,
            provider: settings.provider,
          })
        },
        onProgress: (stage, fraction) => onProgress?.(recordingId, stage, fraction),
      })

      await repository.replaceTranscript(recordingId, result.segments, result.words)
      await repository.saveQualityReport(
        analyzeTranscriptQuality({
          recordingId,
          provider: settings.provider,
          uploadQuality: settings.audioQuality,
          audioDurationMs: recording.durationMs,
          segments: result.segments,
          words: result.words,
        }),
      )
      await repository.updateRecording(recordingId, {
        status: 'READY',
        errorMessage: null,
        providerJobId: null,
        provider: settings.provider,
      })

      // Everything past this point is bookkeeping. It is reported but never
      // allowed to turn a completed transcription into a failure.
      try {
        await repository.rebuildSearch(recordingId)
      } catch {
        onMessage?.('Saved on device, but the search index couldn’t update yet.')
      }
      const backup = await repository.checkpointSidecar(recordingId)
      if (!backup.sidecarCurrent && repository.adapter.supportsAutomaticSidecars) {
        onMessage?.(
          'Transcribed and saved, but the recording-folder backup couldn’t update. Tap Retry backup.',
        )
      }

      return { ok: true }
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        await repository.updateStatus(recordingId, 'NEW', null)
        return { ok: false, message: 'Transcription cancelled.' }
      }

      lastMessage = messageFor(error)
      await repository.updateStatus(recordingId, 'FAILED', lastMessage)

      // A provider rejection will not resolve itself; a dropped connection might.
      if (isTerminal(error) || attempt >= MAX_ATTEMPTS) break
    }
  }

  return { ok: false, message: lastMessage }
}

function messageFor(error: unknown): string {
  const raw =
    error instanceof Error && error.message.trim().length > 0
      ? error.message
      : userMessage('Transcription failed', error)
  return raw.slice(0, ERROR_MESSAGE_LIMIT)
}

import { audioQualityFromStoredValue, type TranscriptionAudioQuality } from '../core/audio-quality'
import type { ProviderId } from '../core/transcription/provider'
import { STORES, get, put } from '../storage/db'

/**
 * The web subset of `AppSettings` in
 * `app/src/main/java/com/andyluu/debrief/data/SettingsStore.kt:34-48`.
 *
 * AI-pass and AI-Enhance settings are deliberately absent: those features are
 * Coming Soon here, and carrying dead settings would imply otherwise.
 */
export interface AppSettings {
  provider: ProviderId
  keyterms: string
  transcriptionAudioQuality: TranscriptionAudioQuality
  /** Warn before uploading hours of audio over a metered connection. */
  warnOnMeteredUpload: boolean
  redactionMode: boolean
  usage: Record<ProviderId, { jobs: number; audioMs: number; bytes: number }>
}

const SETTINGS_KEY = 'app-settings'

export const DEFAULT_SETTINGS: AppSettings = {
  // AssemblyAI is the Android default and the recommended provider for noisy
  // field recordings; it is also the only resumable one, which matters more in
  // a browser than it does on Android.
  provider: 'assemblyai',
  keyterms: '',
  transcriptionAudioQuality: 'ORIGINAL',
  warnOnMeteredUpload: true,
  redactionMode: true,
  usage: {
    assemblyai: { jobs: 0, audioMs: 0, bytes: 0 },
    deepgram: { jobs: 0, audioMs: 0, bytes: 0 },
  },
}

export async function loadSettings(): Promise<AppSettings> {
  const stored = await get<Partial<AppSettings> & { transcriptionAudioQuality?: string }>(
    STORES.settings,
    SETTINGS_KEY,
  )
  if (!stored) return { ...DEFAULT_SETTINGS }
  return {
    provider: stored.provider === 'deepgram' ? 'deepgram' : 'assemblyai',
    keyterms: typeof stored.keyterms === 'string' ? stored.keyterms : '',
    transcriptionAudioQuality: audioQualityFromStoredValue(
      typeof stored.transcriptionAudioQuality === 'string'
        ? storedValueFor(stored.transcriptionAudioQuality)
        : null,
    ),
    warnOnMeteredUpload: stored.warnOnMeteredUpload !== false,
    redactionMode: stored.redactionMode !== false,
    usage: {
      assemblyai: validUsage(stored.usage?.assemblyai),
      deepgram: validUsage(stored.usage?.deepgram),
    },
  }
}

function validUsage(value: unknown): { jobs: number; audioMs: number; bytes: number } {
  if (!value || typeof value !== 'object') return { jobs: 0, audioMs: 0, bytes: 0 }
  const item = value as { jobs?: unknown; audioMs?: unknown; bytes?: unknown }
  return {
    jobs: safe(item.jobs), audioMs: safe(item.audioMs), bytes: safe(item.bytes),
  }
}

function safe(value: unknown): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : 0
}

export async function saveSettings(settings: AppSettings): Promise<void> {
  await put(STORES.settings, settings, SETTINGS_KEY)
}

/** Accepts either the enum name or Android's stored value, so either round-trips. */
function storedValueFor(value: string): string {
  const lower = value.toLowerCase()
  return lower === 'data_saver' || lower === 'balanced' || lower === 'original' ? lower : value
}

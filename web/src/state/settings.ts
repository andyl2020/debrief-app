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
  redactionMode: false,
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
    redactionMode: stored.redactionMode === true,
  }
}

export async function saveSettings(settings: AppSettings): Promise<void> {
  await put(STORES.settings, settings, SETTINGS_KEY)
}

/** Accepts either the enum name or Android's stored value, so either round-trips. */
function storedValueFor(value: string): string {
  const lower = value.toLowerCase()
  return lower === 'data_saver' || lower === 'balanced' || lower === 'original' ? lower : value
}

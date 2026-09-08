/**
 * Ported from `TranscriptionAudioQuality` in
 * `app/src/main/java/com/andyluu/debrief/data/SettingsStore.kt:23-32`.
 *
 * ORIGINAL streams the untouched source file and is the shipped default. The
 * two compressed modes exist to save mobile data on Android, where MediaCodec
 * can re-encode cheaply. Browsers cannot: WebCodecs AAC encoding is absent on
 * iOS Safari entirely. The enum and its bitrates are ported verbatim anyway so
 * settings round-trip with Android, and `src/platform/capabilities.ts` decides
 * whether the compressed options are actually offerable.
 */

export type TranscriptionAudioQuality = 'ORIGINAL' | 'BALANCED' | 'DATA_SAVER'

interface QualitySpec {
  readonly storedValue: string
  readonly bitrate: number | null
  readonly label: string
  readonly description: string
}

export const AUDIO_QUALITY: Record<TranscriptionAudioQuality, QualitySpec> = {
  ORIGINAL: {
    storedValue: 'original',
    bitrate: null,
    label: 'Original',
    description: 'Upload the recording unchanged. Best accuracy, largest upload.',
  },
  BALANCED: {
    storedValue: 'balanced',
    bitrate: 96_000,
    label: 'Balanced',
    description: '96 kbps mono AAC. Smaller upload with little accuracy cost.',
  },
  DATA_SAVER: {
    storedValue: 'data_saver',
    bitrate: 64_000,
    label: 'Data saver',
    description: '64 kbps mono AAC. Smallest upload, some accuracy cost.',
  },
}

export const AUDIO_QUALITY_ORDER: TranscriptionAudioQuality[] = [
  'ORIGINAL',
  'BALANCED',
  'DATA_SAVER',
]

/** Ported from `TranscriptionAudioQuality.fromStoredValue`. Unknown or missing falls back to ORIGINAL. */
export function audioQualityFromStoredValue(
  value: string | null | undefined,
): TranscriptionAudioQuality {
  const match = AUDIO_QUALITY_ORDER.find((key) => AUDIO_QUALITY[key].storedValue === value)
  return match ?? 'ORIGINAL'
}

export function audioQualityBitrate(quality: TranscriptionAudioQuality): number | null {
  return AUDIO_QUALITY[quality].bitrate
}

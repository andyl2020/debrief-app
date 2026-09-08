import { describe, expect, it } from 'vitest'
import {
  DEFAULT_PLAYBACK_SKIP_MS,
  PLAYBACK_SKIP_INTERVALS_MS,
  PLAYBACK_SPEED_OPTIONS,
  applyPlaybackRate,
  formatPlaybackSkipInterval,
  formatPlaybackSpeed,
  nextPlaybackSkipInterval,
} from '../src/core/playback'
import { formatTimestamp } from '../src/core/format'
import {
  AUDIO_QUALITY,
  audioQualityBitrate,
  audioQualityFromStoredValue,
} from '../src/core/audio-quality'

/** Ported from `app/src/test/java/com/andyluu/debrief/ui/PlaybackSpeedTest.kt`. */
describe('PlaybackSpeed', () => {
  it('speedOptionsIncludeNormalAndEveryRequestedRate', () => {
    expect([...PLAYBACK_SPEED_OPTIONS]).toEqual([1, 1.2, 1.5, 2, 3, 4])
  })

  it('speedLabelsAreCompactAndUnambiguous', () => {
    expect(PLAYBACK_SPEED_OPTIONS.map(formatPlaybackSpeed)).toEqual([
      '1×',
      '1.2×',
      '1.5×',
      '2×',
      '3×',
      '4×',
    ])
  })

  it('skipIntervalsDefaultToThreeAndCycleThroughRequestedOptions', () => {
    expect(DEFAULT_PLAYBACK_SKIP_MS).toBe(3_000)
    expect([...PLAYBACK_SKIP_INTERVALS_MS]).toEqual([3_000, 1_000, 5_000])
    expect(nextPlaybackSkipInterval(3_000)).toBe(1_000)
    expect(nextPlaybackSkipInterval(1_000)).toBe(5_000)
    expect(nextPlaybackSkipInterval(5_000)).toBe(3_000)
  })

  it('skipIntervalLabelsUseSeconds', () => {
    expect(formatPlaybackSkipInterval(1_000)).toBe('1 second')
    expect(formatPlaybackSkipInterval(3_000)).toBe('3 seconds')
    expect(formatPlaybackSkipInterval(5_000)).toBe('5 seconds')
  })
})

/**
 * Web-specific. Safari clamps playbackRate, so the player must report what the
 * browser actually applied rather than what was asked for.
 */
describe('applyPlaybackRate', () => {
  it('reports the effective rate when the browser clamps it', () => {
    const clampingElement = {
      _rate: 1,
      get playbackRate() {
        return this._rate
      },
      set playbackRate(value: number) {
        this._rate = Math.min(2, value)
      },
    }

    expect(applyPlaybackRate(clampingElement, 4)).toEqual({
      requested: 4,
      effective: 2,
      clamped: true,
    })
    expect(applyPlaybackRate(clampingElement, 1.5)).toEqual({
      requested: 1.5,
      effective: 1.5,
      clamped: false,
    })
  })
})

/** Ported from `app/src/test/java/com/andyluu/debrief/ui/FormatTimestampTest.kt`. */
describe('formatTimestamp', () => {
  it('formatsShortAndLongDurations', () => {
    expect(formatTimestamp(0)).toBe('0:00')
    expect(formatTimestamp(547_000)).toBe('9:07')
    expect(formatTimestamp(7_384_000)).toBe('2:03:04')
    // Web-specific: a negative position can arrive from a scrubber drag.
    expect(formatTimestamp(-500)).toBe('0:00')
  })
})

/** Ported from `app/src/test/java/com/andyluu/debrief/transcription/AudioQualityTest.kt`. */
describe('TranscriptionAudioQuality', () => {
  it('unknownOrMissingPreferenceDefaultsToOriginal', () => {
    expect(audioQualityFromStoredValue(null)).toBe('ORIGINAL')
    expect(audioQualityFromStoredValue(undefined)).toBe('ORIGINAL')
    expect(audioQualityFromStoredValue('future-value')).toBe('ORIGINAL')
    expect(audioQualityFromStoredValue('balanced')).toBe('BALANCED')
  })

  it('compressedModesUseDocumentedBitrates', () => {
    expect(audioQualityBitrate('ORIGINAL')).toBeNull()
    expect(audioQualityBitrate('BALANCED')).toBe(96_000)
    expect(audioQualityBitrate('DATA_SAVER')).toBe(64_000)
    // The stored values are the on-disk contract shared with Android settings.
    expect(AUDIO_QUALITY.DATA_SAVER.storedValue).toBe('data_saver')
  })
})

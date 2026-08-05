/**
 * Ported from `app/src/main/java/com/andyluu/debrief/ui/Screens.kt:1403-1428`.
 *
 * Fast review is the point of the app, so the speed ladder goes all the way to
 * 4x. Browsers do not all honour that: Safari in particular clamps
 * `HTMLMediaElement.playbackRate`. The UI reports the rate the browser actually
 * applied rather than the one that was requested - see `applyPlaybackRate`.
 */

export const PLAYBACK_SPEED_OPTIONS = [1, 1.2, 1.5, 2, 3, 4] as const
export const PLAYBACK_SKIP_INTERVALS_MS = [3_000, 1_000, 5_000] as const
export const DEFAULT_PLAYBACK_SKIP_MS = 3_000

export function formatPlaybackSpeed(speed: number): string {
  switch (speed) {
    case 1:
      return '1×'
    case 1.2:
      return '1.2×'
    case 1.5:
      return '1.5×'
    case 2:
      return '2×'
    case 3:
      return '3×'
    case 4:
      return '4×'
    default:
      return `${speed}×`
  }
}

/** Long-pressing the skip button cycles 3s -> 1s -> 5s -> 3s. */
export function nextPlaybackSkipInterval(currentMs: number): number {
  const index = (PLAYBACK_SKIP_INTERVALS_MS as readonly number[]).indexOf(currentMs)
  const nextIndex = floorMod(index + 1, PLAYBACK_SKIP_INTERVALS_MS.length)
  return PLAYBACK_SKIP_INTERVALS_MS[nextIndex]!
}

export function formatPlaybackSkipInterval(milliseconds: number): string {
  const seconds = Math.max(1, Math.trunc(milliseconds / 1000))
  return `${seconds} ${seconds === 1 ? 'second' : 'seconds'}`
}

/**
 * Sets the rate and reports back what the browser settled on. A media element
 * silently clamps out-of-range rates, so reading the property back is the only
 * honest way to know whether 4x actually happened.
 */
export function applyPlaybackRate(
  element: Pick<HTMLMediaElement, 'playbackRate'>,
  requested: number,
): { requested: number; effective: number; clamped: boolean } {
  try {
    element.playbackRate = requested
  } catch {
    // Safari throws NotSupportedError instead of clamping for some rates.
  }
  const effective = element.playbackRate
  return {
    requested,
    effective,
    clamped: Math.abs(effective - requested) > 0.01,
  }
}

function floorMod(value: number, modulus: number): number {
  return ((value % modulus) + modulus) % modulus
}

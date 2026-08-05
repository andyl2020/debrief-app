/**
 * Runtime feature detection.
 *
 * The Android app can assume one platform. A web app cannot, and the gap is not
 * cosmetic: iOS Safari has no File System Access API at all, which is exactly
 * the API that would mirror Android's Storage Access Framework. Rather than
 * silently degrading, the app detects what it has and tells the user which
 * mode it is running in.
 */

export interface Capabilities {
  /** True when the browser can link a real on-disk folder (Chromium desktop). */
  fileSystemAccess: boolean
  /** True when the origin-private file system is available (iOS Safari 16.4+, all modern browsers). */
  opfs: boolean
  /** True when audio can be re-encoded for the compressed upload modes. */
  audioReencode: boolean
  /** True when durable storage can be requested. */
  persistentStorage: boolean
  webCrypto: boolean
  /** iOS suspends background tabs aggressively, which changes the provider default. */
  suspendsBackgroundTabs: boolean
}

export function detectCapabilities(scope: typeof globalThis = globalThis): Capabilities {
  const nav = (scope as { navigator?: Navigator }).navigator
  return {
    fileSystemAccess: typeof (scope as { showDirectoryPicker?: unknown }).showDirectoryPicker === 'function',
    opfs: typeof nav?.storage?.getDirectory === 'function',
    audioReencode: hasAacEncoder(scope),
    persistentStorage: typeof nav?.storage?.persist === 'function',
    webCrypto: typeof scope.crypto?.subtle?.importKey === 'function',
    suspendsBackgroundTabs: isIosLike(nav),
  }
}

/**
 * WebCodecs can *decode* widely but AAC *encoding* is the part the compressed
 * upload modes need, and it is absent on iOS. `isConfigSupported` is async, so
 * this is the cheap synchronous gate; the settings screen confirms properly.
 */
function hasAacEncoder(scope: typeof globalThis): boolean {
  return typeof (scope as { AudioEncoder?: unknown }).AudioEncoder === 'function'
}

export async function confirmAacEncoder(scope: typeof globalThis = globalThis): Promise<boolean> {
  const encoder = (scope as { AudioEncoder?: { isConfigSupported?: (config: unknown) => Promise<{ supported?: boolean }> } })
    .AudioEncoder
  if (typeof encoder?.isConfigSupported !== 'function') return false
  try {
    const support = await encoder.isConfigSupported({
      codec: 'mp4a.40.2',
      sampleRate: 48_000,
      numberOfChannels: 1,
      bitrate: 96_000,
    })
    return support?.supported === true
  } catch {
    return false
  }
}

/**
 * iPhone and iPad, including iPadOS reporting itself as a Mac. Used to pick the
 * resumable provider by default and to warn about long single-request uploads.
 */
function isIosLike(nav: Navigator | undefined): boolean {
  if (!nav) return false
  const platform = `${nav.userAgent} ${(nav as Navigator & { platform?: string }).platform ?? ''}`
  if (/iPhone|iPad|iPod/i.test(platform)) return true
  // iPadOS 13+ masquerades as desktop Safari but still has a touch screen.
  return /Macintosh/i.test(platform) && (nav.maxTouchPoints ?? 0) > 1
}

/**
 * Asks the browser to make stored recordings non-evictable. Returns what the
 * browser actually granted - on iOS this frequently stays false, and the UI
 * says so rather than implying durability it cannot guarantee.
 */
export async function requestPersistentStorage(
  scope: typeof globalThis = globalThis,
): Promise<boolean> {
  const storage = scope.navigator?.storage
  if (typeof storage?.persist !== 'function') return false
  try {
    if (typeof storage.persisted === 'function' && (await storage.persisted())) return true
    return await storage.persist()
  } catch {
    return false
  }
}

export async function storageEstimate(
  scope: typeof globalThis = globalThis,
): Promise<{ usage: number; quota: number } | null> {
  const storage = scope.navigator?.storage
  if (typeof storage?.estimate !== 'function') return null
  try {
    const estimate = await storage.estimate()
    return { usage: estimate.usage ?? 0, quota: estimate.quota ?? 0 }
  } catch {
    return null
  }
}

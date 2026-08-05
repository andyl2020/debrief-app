import { fromBase64 } from '../core/cloud-crypto'
import { IoError } from '../core/errors'
import type { CloudClient } from './cloud-client'
import { fetchDecryptedObject, type CloudState } from './cloud'

/**
 * Getting encrypted cloud audio into an <audio> element.
 *
 * Preferred path is the service worker in `public/sw.js`, which range-decrypts
 * on demand so seeking works without downloading the file.
 *
 * The fallback downloads and decrypts the whole object into a Blob URL. That is
 * fine for a short recording and hopeless for a long one, so it is size-gated
 * rather than allowed to quietly exhaust memory on a phone. Which path is in
 * use is reported to the UI instead of being hidden.
 */

const VIRTUAL_PREFIX = '__cloud-audio/'
/** Above this, a whole-file download is not a reasonable thing to attempt. */
export const FALLBACK_MAX_BYTES = 150 * 1024 * 1024

export type PlaybackMode = 'streaming' | 'whole-file'

export interface CloudAudioHandle {
  url: string
  mode: PlaybackMode
  release: () => void
}

let registration: ServiceWorkerRegistration | null = null
let registrationAttempted = false

export function serviceWorkerSupported(scope: typeof globalThis = globalThis): boolean {
  return typeof scope.navigator?.serviceWorker?.register === 'function' && scope.isSecureContext
}

/** Registers the proxy once. Returns null when unavailable or registration fails. */
export async function ensureServiceWorker(): Promise<ServiceWorkerRegistration | null> {
  if (registration) return registration
  if (registrationAttempted || !serviceWorkerSupported()) return null
  registrationAttempted = true
  try {
    const base = import.meta.env.BASE_URL ?? '/'
    registration = await navigator.serviceWorker.register(`${base}sw.js`, { scope: base })
    await navigator.serviceWorker.ready
    return registration
  } catch {
    // A failed registration is not fatal; the fallback still plays audio.
    return null
  }
}

async function post(message: unknown): Promise<boolean> {
  const controller = navigator.serviceWorker?.controller
  if (!controller) return false
  return new Promise<boolean>((resolve) => {
    const channel = new MessageChannel()
    const timer = setTimeout(() => resolve(false), 2_000)
    channel.port1.onmessage = () => {
      clearTimeout(timer)
      resolve(true)
    }
    controller.postMessage(message, [channel.port2])
  })
}

/** Hands the data key to the proxy. Held in worker memory only, never persisted. */
export async function primeServiceWorkerKey(key: CryptoKey): Promise<boolean> {
  await ensureServiceWorker()
  return post({ type: 'debrief/set-key', key })
}

export async function clearServiceWorkerKey(): Promise<void> {
  await post({ type: 'debrief/clear' })
}

/**
 * Produces a URL an <audio> element can play.
 *
 * Tries the streaming proxy first and falls back to a decrypted Blob, so a
 * browser without service workers still plays a reasonably sized recording.
 */
export async function cloudAudioUrl(
  client: CloudClient,
  key: CryptoKey,
  state: CloudState,
  mimeType: string | null,
): Promise<CloudAudioHandle> {
  const primed = await primeServiceWorkerKey(key)
  if (primed) {
    const ready = await post({
      type: 'debrief/set-item',
      id: state.recordingId,
      nonce: fromBase64(state.audioNonce),
      totalBytes: state.audioBytes,
      url: client.objectUrl(state.recordingId, 'audio'),
      token: tokenFrom(client),
      mimeType: mimeType ?? 'audio/mp4',
    })
    if (ready) {
      const base = import.meta.env.BASE_URL ?? '/'
      return {
        url: `${base}${VIRTUAL_PREFIX}${encodeURIComponent(state.recordingId)}`,
        mode: 'streaming',
        release: () => undefined,
      }
    }
  }

  if (state.audioBytes > FALLBACK_MAX_BYTES) {
    throw new IoError(
      'This browser cannot stream encrypted audio, and this recording is too large to download in one piece. Open it in a browser that supports service workers, or play it on a device that has the local file.',
    )
  }

  const plain = await fetchDecryptedObject(client, key, state.recordingId, 'audio', state.audioNonce)
  const blobUrl = URL.createObjectURL(new Blob([plain as BlobPart], { type: mimeType ?? 'audio/mp4' }))
  return {
    url: blobUrl,
    mode: 'whole-file',
    release: () => URL.revokeObjectURL(blobUrl),
  }
}

/**
 * The owner token, which `CloudClient` keeps private.
 *
 * The proxy runs in a separate context and has to authenticate its own fetches,
 * so it needs the token. Reading it through a narrow accessor keeps that the
 * only place the privacy is bent.
 */
function tokenFrom(client: CloudClient): string {
  return (client as unknown as { token: string }).token
}

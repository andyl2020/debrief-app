/* eslint-disable no-restricted-globals */
/**
 * Same-origin authenticated range-decryption proxy for cloud audio.
 *
 * Each audio chunk is an independent AES-GCM message. The proxy expands a
 * media byte range to complete encrypted chunks, authenticates every chunk,
 * then returns only the requested plaintext. The data key and owner token live
 * in service-worker memory only and are cleared when the user locks the cloud.
 */

const CRYPTO_VERSION = 2
const GCM_TAG_BYTES = 16
const VIRTUAL_PREFIX = '/__cloud-audio/'
const AAD_PREFIX = 'debrief-cloud-v2'
const APP_CACHE = 'debrief-app-v1.12.0'

const items = new Map()
let dataKey = null

self.addEventListener('install', (event) => {
  event.waitUntil(Promise.all([self.skipWaiting(), caches.open(APP_CACHE).then((cache) => cache.addAll(['/', '/manifest.webmanifest', '/icon.svg']))]))
})

self.addEventListener('activate', (event) => {
  event.waitUntil(Promise.all([
    self.clients.claim(),
    caches.keys().then((keys) => Promise.all(keys.filter((key) => key.startsWith('debrief-app-') && key !== APP_CACHE).map((key) => caches.delete(key)))),
  ]))
})

self.addEventListener('message', (event) => {
  const message = event.data
  if (!message || typeof message !== 'object') return

  if (message.type === 'debrief/set-key') {
    dataKey = message.key ?? null
  } else if (message.type === 'debrief/set-item') {
    if (message.cryptoVersion === CRYPTO_VERSION) {
      items.set(message.id, {
        id: message.id,
        nonce: message.nonce,
        totalBytes: message.totalBytes,
        cipherBytes: message.cipherBytes,
        cryptoVersion: message.cryptoVersion,
        chunkBytes: message.chunkBytes,
        url: message.url,
        token: message.token,
        mimeType: message.mimeType,
      })
    }
  } else if (message.type === 'debrief/clear') {
    dataKey = null
    items.clear()
  }
  if (event.ports && event.ports[0]) event.ports[0].postMessage({ ok: true })
})

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url)
  if (url.origin !== self.location.origin) return
  const index = url.pathname.indexOf(VIRTUAL_PREFIX)
  if (index < 0) {
    if (event.request.method === 'GET') event.respondWith(appAsset(event.request))
    return
  }

  const id = decodeURIComponent(url.pathname.slice(index + VIRTUAL_PREFIX.length))
  event.respondWith(handle(event.request, id))
})

async function appAsset(request) {
  const cache = await caches.open(APP_CACHE)
  try {
    const response = await fetch(request)
    if (response.ok && new URL(request.url).origin === self.location.origin) await cache.put(request, response.clone())
    return response
  } catch {
    return (await cache.match(request)) ?? (request.mode === 'navigate' ? await cache.match('/') : undefined) ?? new Response('Offline', { status: 503 })
  }
}

async function handle(request, id) {
  const item = items.get(id)
  if (!item || !dataKey) return new Response('Cloud playback is locked.', { status: 503 })
  if (item.cryptoVersion !== CRYPTO_VERSION) {
    return new Response('Cloud encryption version is unsupported.', { status: 409 })
  }

  const range = parseRange(request.headers.get('Range'), item.totalBytes)
  if (range === 'invalid') {
    return new Response(null, {
      status: 416,
      headers: {
        'Content-Range': `bytes */${item.totalBytes}`,
        'Accept-Ranges': 'bytes',
      },
    })
  }

  if (request.method === 'HEAD') {
    return new Response(null, {
      status: 200,
      headers: mediaHeaders(item, item.totalBytes, null),
    })
  }

  const wanted = range ?? { start: 0, end: item.totalBytes - 1 }
  try {
    const plain = await fetchDecryptedRange(item, wanted.start, wanted.end)
    return new Response(plain, {
      status: range ? 206 : 200,
      headers: mediaHeaders(item, item.totalBytes, range ? wanted : null),
    })
  } catch {
    // Never return unauthenticated bytes or crypto internals to the media
    // element. A corrupt object fails closed and can be re-uploaded.
    return new Response('Cloud audio failed authentication.', { status: 502 })
  }
}

async function fetchDecryptedRange(item, start, end) {
  const layout = authenticatedRange(start, end, item.totalBytes, item.chunkBytes)
  const response = await fetch(item.url, {
    cache: 'no-store',
    headers: {
      Authorization: `Bearer ${item.token}`,
      Range: `bytes=${layout.fetchStart}-${layout.fetchEnd}`,
    },
  })
  if (!response.ok && response.status !== 206) throw new Error('Cloud object request failed.')

  const ciphertext = new Uint8Array(await response.arrayBuffer())
  const plaintextChunks = []
  let cursor = 0
  for (let chunkIndex = layout.firstChunk; chunkIndex <= layout.lastChunk; chunkIndex += 1) {
    const plainStart = chunkIndex * item.chunkBytes
    const plainLength = Math.min(item.chunkBytes, item.totalBytes - plainStart)
    const cipherLength = plainLength + GCM_TAG_BYTES
    const cipherChunk = ciphertext.slice(cursor, cursor + cipherLength)
    if (cipherChunk.length !== cipherLength) throw new Error('Cloud object is truncated.')
    const plain = await crypto.subtle.decrypt(
      {
        name: 'AES-GCM',
        iv: ivForChunk(item.nonce, chunkIndex),
        additionalData: new TextEncoder().encode(
          `${AAD_PREFIX}:${item.id}:audio:${chunkIndex}`,
        ),
        tagLength: 128,
      },
      dataKey,
      cipherChunk,
    )
    plaintextChunks.push(new Uint8Array(plain))
    cursor += cipherLength
  }
  if (cursor !== ciphertext.length) throw new Error('Cloud object layout is invalid.')
  const joined = concat(plaintextChunks)
  return joined.slice(layout.trimStart, layout.trimStart + layout.length)
}

function authenticatedRange(start, end, totalBytes, chunkBytes) {
  const firstChunk = Math.floor(start / chunkBytes)
  const lastChunk = Math.floor(end / chunkBytes)
  const stride = chunkBytes + GCM_TAG_BYTES
  const lastPlainStart = lastChunk * chunkBytes
  const lastPlainLength = Math.min(chunkBytes, totalBytes - lastPlainStart)
  return {
    firstChunk,
    lastChunk,
    fetchStart: firstChunk * stride,
    fetchEnd: lastChunk * stride + lastPlainLength + GCM_TAG_BYTES - 1,
    trimStart: start - firstChunk * chunkBytes,
    length: end - start + 1,
  }
}

function ivForChunk(nonce, chunkIndex) {
  if (!(nonce instanceof Uint8Array) || nonce.length !== 8) throw new Error('Invalid nonce.')
  const iv = new Uint8Array(12)
  iv.set(nonce, 0)
  new DataView(iv.buffer).setUint32(8, chunkIndex)
  return iv
}

function concat(parts) {
  const output = new Uint8Array(parts.reduce((total, part) => total + part.length, 0))
  let offset = 0
  for (const part of parts) {
    output.set(part, offset)
    offset += part.length
  }
  return output
}

function mediaHeaders(item, total, range) {
  const headers = new Headers({
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'private, no-store',
    'Content-Type': item.mimeType || 'audio/mp4',
    'X-Content-Type-Options': 'nosniff',
  })
  if (range) {
    headers.set('Content-Length', String(range.end - range.start + 1))
    headers.set('Content-Range', `bytes ${range.start}-${range.end}/${total}`)
  } else {
    headers.set('Content-Length', String(total))
  }
  return headers
}

function parseRange(value, size) {
  if (!value) return null
  const match = /^bytes=(\d*)-(\d*)$/.exec(value.trim())
  if (!match) return 'invalid'
  const startText = match[1] || ''
  const endText = match[2] || ''
  if (!startText && !endText) return 'invalid'
  let start
  let end
  if (!startText) {
    const suffix = Number(endText)
    if (!Number.isSafeInteger(suffix) || suffix <= 0) return 'invalid'
    start = Math.max(0, size - suffix)
    end = size - 1
  } else {
    start = Number(startText)
    end = endText ? Number(endText) : size - 1
  }
  if (
    !Number.isSafeInteger(start) ||
    !Number.isSafeInteger(end) ||
    start < 0 ||
    start >= size ||
    end < start
  ) {
    return 'invalid'
  }
  return { start, end: Math.min(end, size - 1) }
}

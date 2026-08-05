/* eslint-disable no-restricted-globals */
/**
 * Range-decrypting playback proxy.
 *
 * An <audio> element issues its own Range requests, and page script cannot
 * intercept them. So the element is pointed at a virtual same-origin URL and
 * this worker sits in the middle: it takes the Range the browser asked for,
 * widens it to an AES block boundary, fetches that ciphertext range from the
 * Cloudflare Worker, decrypts it, and hands back exactly the plaintext bytes
 * requested. That is what makes scrubbing an encrypted six-hour recording feel
 * like scrubbing a local file instead of a 350 MB download.
 *
 * The data key arrives by postMessage and is held in memory only. It is never
 * put in IndexedDB or Cache Storage here, so closing every tab forgets it.
 *
 * Classic (non-module) worker on purpose: module service workers are still
 * patchy in Safari, which is the browser this whole feature exists for. The
 * counter arithmetic below is duplicated from src/core/cloud-crypto.ts; the
 * test in test/service-worker.test.ts drives THIS file and checks the two
 * agree, so the duplication cannot silently drift.
 */

const AES_BLOCK_BYTES = 16
const VIRTUAL_PREFIX = '/__cloud-audio/'

/** recordingId -> { nonce: Uint8Array, totalBytes, url, token } */
const items = new Map()
let dataKey = null

self.addEventListener('install', (event) => {
  event.waitUntil(self.skipWaiting())
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})

self.addEventListener('message', (event) => {
  const message = event.data
  if (!message || typeof message !== 'object') return

  if (message.type === 'debrief/set-key') {
    dataKey = message.key ?? null
  } else if (message.type === 'debrief/set-item') {
    items.set(message.id, {
      nonce: message.nonce,
      totalBytes: message.totalBytes,
      url: message.url,
      token: message.token,
    })
  } else if (message.type === 'debrief/clear') {
    dataKey = null
    items.clear()
  }
  // Acknowledge so the page can await readiness before creating the element.
  if (event.ports && event.ports[0]) event.ports[0].postMessage({ ok: true })
})

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url)
  if (url.origin !== self.location.origin) return
  const index = url.pathname.indexOf(VIRTUAL_PREFIX)
  if (index < 0) return

  const id = decodeURIComponent(url.pathname.slice(index + VIRTUAL_PREFIX.length))
  event.respondWith(handle(event.request, id))
})

async function handle(request, id) {
  const item = items.get(id)
  if (!item || !dataKey) {
    return new Response('Cloud playback is locked.', { status: 503 })
  }

  const total = item.totalBytes
  const range = parseRange(request.headers.get('Range'), total)
  if (range === 'invalid') {
    return new Response(null, {
      status: 416,
      headers: { 'Content-Range': `bytes */${total}`, 'Accept-Ranges': 'bytes' },
    })
  }

  // Safari will not start playback without knowing the length up front.
  if (request.method === 'HEAD') {
    return new Response(null, { status: 200, headers: mediaHeaders(item, total, null) })
  }

  const start = range ? range.start : 0
  const end = range ? range.end : total - 1

  try {
    const plain = await fetchDecryptedRange(item, start, end)
    return new Response(plain, {
      status: range ? 206 : 200,
      headers: mediaHeaders(item, total, range ? { start, end } : null),
    })
  } catch (error) {
    return new Response(`Cloud playback failed: ${error && error.message}`, { status: 502 })
  }
}

async function fetchDecryptedRange(item, start, end) {
  const fetchStart = Math.floor(start / AES_BLOCK_BYTES) * AES_BLOCK_BYTES
  const trimStart = start - fetchStart

  const response = await fetch(item.url, {
    headers: {
      Authorization: `Bearer ${item.token}`,
      Range: `bytes=${fetchStart}-${end}`,
    },
  })
  if (!response.ok && response.status !== 206) {
    throw new Error(`upstream ${response.status}`)
  }
  const cipher = new Uint8Array(await response.arrayBuffer())

  const counter = counterForOffset(item.nonce, fetchStart)
  const decrypted = new Uint8Array(
    await crypto.subtle.decrypt({ name: 'AES-CTR', counter, length: 64 }, dataKey, cipher),
  )
  return decrypted.slice(trimStart, trimStart + (end - start + 1))
}

/** 8-byte nonce followed by a 64-bit big-endian block counter. */
function counterForOffset(nonce, offsetBytes) {
  const counter = new Uint8Array(AES_BLOCK_BYTES)
  counter.set(nonce.slice(0, 8), 0)
  new DataView(counter.buffer).setBigUint64(8, BigInt(offsetBytes / AES_BLOCK_BYTES))
  return counter
}

function mediaHeaders(item, total, range) {
  const headers = new Headers({
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'no-store',
    'Content-Type': item.mimeType || 'audio/mp4',
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
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || start >= size || end < start) {
    return 'invalid'
  }
  return { start, end: Math.min(end, size - 1) }
}

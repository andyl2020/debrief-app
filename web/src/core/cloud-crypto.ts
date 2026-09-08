/**
 * Authenticated end-to-end encryption for the private cloud library.
 *
 * Audio is split into independently authenticated AES-GCM chunks. A player can
 * fetch and decrypt only the chunks covering a requested byte range, while a
 * modified, truncated, reordered, or cross-recording chunk fails closed. The
 * Worker stores ciphertext and public layout metadata only; the data key stays
 * in browser memory and is wrapped with a passphrase-derived AES-GCM key.
 */

export const CLOUD_CRYPTO_VERSION = 2
export const AUTH_CHUNK_BYTES = 8 * 1024 * 1024
export const GCM_TAG_BYTES = 16
const NONCE_BYTES = 8
const IV_BYTES = 12
const MAX_CHUNK_INDEX = 0xffff_ffff
const AAD_PREFIX = 'debrief-cloud-v2'
export const PBKDF2_ITERATIONS = 310_000

export class CloudCryptoError extends Error {
  override readonly name = 'CloudCryptoError'
}

/** Generates the random library data key; it is never derived from the passphrase. */
export async function generateLibraryKey(): Promise<CryptoKey> {
  return crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt'])
}

/** Eight random bytes form the per-object nonce prefix; the chunk index completes the IV. */
export function randomNonce(): Uint8Array<ArrayBuffer> {
  return crypto.getRandomValues(new Uint8Array(NONCE_BYTES))
}

export async function wrapLibraryKey(
  key: CryptoKey,
  passphrase: string,
  salt: Uint8Array = crypto.getRandomValues(new Uint8Array(16)),
  iterations: number = PBKDF2_ITERATIONS,
): Promise<{ wrappedKey: string; salt: string; iterations: number }> {
  const wrapping = await deriveWrappingKey(passphrase, salt, iterations)
  const raw = await crypto.subtle.exportKey('raw', key)
  const iv = crypto.getRandomValues(new Uint8Array(IV_BYTES))
  const wrapped = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, wrapping, raw)
  return {
    wrappedKey: toBase64(concat(iv, new Uint8Array(wrapped))),
    salt: toBase64(salt),
    iterations,
  }
}

export async function unwrapLibraryKey(
  wrappedKey: string,
  passphrase: string,
  salt: string,
  iterations: number,
): Promise<CryptoKey> {
  const wrapping = await deriveWrappingKey(passphrase, fromBase64(salt), iterations)
  const payload = fromBase64(wrappedKey)
  try {
    const raw = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: payload.slice(0, IV_BYTES) },
      wrapping,
      payload.slice(IV_BYTES),
    )
    return await crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, true, [
      'encrypt',
      'decrypt',
    ])
  } catch {
    throw new CloudCryptoError('That passphrase does not match this cloud library.')
  }
}

async function deriveWrappingKey(
  passphrase: string,
  salt: Uint8Array,
  iterations: number,
): Promise<CryptoKey> {
  const material = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(passphrase),
    'PBKDF2',
    false,
    ['deriveKey'],
  )
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt: salt as BufferSource, iterations, hash: 'SHA-256' },
    material,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  )
}

/** Builds a unique 96-bit GCM IV from an object nonce and a 32-bit chunk index. */
export function ivForChunk(nonce: Uint8Array, chunkIndex: number): Uint8Array<ArrayBuffer> {
  if (nonce.length !== NONCE_BYTES) throw new CloudCryptoError('Cloud object nonce is invalid.')
  if (!Number.isSafeInteger(chunkIndex) || chunkIndex < 0 || chunkIndex > MAX_CHUNK_INDEX) {
    throw new CloudCryptoError('Cloud chunk index is invalid.')
  }
  const iv = new Uint8Array(IV_BYTES)
  iv.set(nonce, 0)
  new DataView(iv.buffer).setUint32(NONCE_BYTES, chunkIndex)
  return iv
}

function additionalData(context: string, chunkIndex: number): Uint8Array<ArrayBuffer> {
  if (!context.trim()) throw new CloudCryptoError('Cloud encryption context is missing.')
  return new TextEncoder().encode(`${AAD_PREFIX}:${context}:${chunkIndex}`)
}

export async function encryptChunk(
  key: CryptoKey,
  nonce: Uint8Array,
  chunkIndex: number,
  plaintext: Uint8Array,
  context: string,
): Promise<Uint8Array<ArrayBuffer>> {
  const cipher = await crypto.subtle.encrypt(
    {
      name: 'AES-GCM',
      iv: ivForChunk(nonce, chunkIndex),
      additionalData: additionalData(context, chunkIndex),
      tagLength: 128,
    },
    key,
    plaintext as BufferSource,
  )
  return new Uint8Array(cipher)
}

export async function decryptChunk(
  key: CryptoKey,
  nonce: Uint8Array,
  chunkIndex: number,
  ciphertext: Uint8Array,
  context: string,
): Promise<Uint8Array<ArrayBuffer>> {
  try {
    const plain = await crypto.subtle.decrypt(
      {
        name: 'AES-GCM',
        iv: ivForChunk(nonce, chunkIndex),
        additionalData: additionalData(context, chunkIndex),
        tagLength: 128,
      },
      key,
      ciphertext as BufferSource,
    )
    return new Uint8Array(plain)
  } catch {
    throw new CloudCryptoError('Cloud data failed authentication and was not opened.')
  }
}

/** Single authenticated payload, used for the comparatively small sidecar. */
export function encryptWhole(
  key: CryptoKey,
  nonce: Uint8Array,
  plaintext: Uint8Array,
  context = 'metadata',
): Promise<Uint8Array<ArrayBuffer>> {
  return encryptChunk(key, nonce, 0, plaintext, context)
}

export function decryptWhole(
  key: CryptoKey,
  nonce: Uint8Array,
  ciphertext: Uint8Array,
  context = 'metadata',
): Promise<Uint8Array<ArrayBuffer>> {
  return decryptChunk(key, nonce, 0, ciphertext, context)
}

export function encryptedChunkedSize(
  plaintextBytes: number,
  chunkBytes = AUTH_CHUNK_BYTES,
): number {
  if (!Number.isSafeInteger(plaintextBytes) || plaintextBytes < 0 || chunkBytes <= 0) {
    throw new CloudCryptoError('Cloud object size is invalid.')
  }
  if (plaintextBytes === 0) return 0
  return plaintextBytes + Math.ceil(plaintextBytes / chunkBytes) * GCM_TAG_BYTES
}

export interface AlignedRange {
  fetchStart: number
  fetchEnd: number
  trimStart: number
  length: number
  firstChunk: number
  lastChunk: number
}

/** Maps a plaintext media range to the authenticated ciphertext chunks covering it. */
export function alignRange(
  start: number,
  end: number,
  totalBytes: number,
  chunkBytes = AUTH_CHUNK_BYTES,
): AlignedRange {
  if (
    !Number.isSafeInteger(start) ||
    !Number.isSafeInteger(end) ||
    !Number.isSafeInteger(totalBytes) ||
    start < 0 ||
    end < start ||
    start >= totalBytes ||
    chunkBytes <= 0
  ) {
    throw new CloudCryptoError('Invalid byte range.')
  }
  const clampedEnd = Math.min(end, totalBytes - 1)
  const firstChunk = Math.floor(start / chunkBytes)
  const lastChunk = Math.floor(clampedEnd / chunkBytes)
  const stride = chunkBytes + GCM_TAG_BYTES
  const lastPlainStart = lastChunk * chunkBytes
  const lastPlainLength = Math.min(chunkBytes, totalBytes - lastPlainStart)
  return {
    fetchStart: firstChunk * stride,
    fetchEnd: lastChunk * stride + lastPlainLength + GCM_TAG_BYTES - 1,
    trimStart: start - firstChunk * chunkBytes,
    length: clampedEnd - start + 1,
    firstChunk,
    lastChunk,
  }
}

/** Authenticates every fetched chunk before returning the requested plaintext bytes. */
export async function decryptRange(
  key: CryptoKey,
  nonce: Uint8Array,
  aligned: AlignedRange,
  ciphertextSlice: Uint8Array,
  totalBytes: number,
  context: string,
  chunkBytes = AUTH_CHUNK_BYTES,
): Promise<Uint8Array<ArrayBuffer>> {
  const chunks: Uint8Array[] = []
  let cursor = 0
  for (let index = aligned.firstChunk; index <= aligned.lastChunk; index += 1) {
    const plainStart = index * chunkBytes
    const plainLength = Math.min(chunkBytes, totalBytes - plainStart)
    const cipherLength = plainLength + GCM_TAG_BYTES
    const cipher = ciphertextSlice.slice(cursor, cursor + cipherLength)
    if (cipher.length !== cipherLength) {
      throw new CloudCryptoError('Cloud data is truncated and was not opened.')
    }
    chunks.push(await decryptChunk(key, nonce, index, cipher, context))
    cursor += cipherLength
  }
  if (cursor !== ciphertextSlice.length) {
    throw new CloudCryptoError('Cloud data layout is invalid and was not opened.')
  }
  const plain = concatMany(chunks)
  return plain.slice(aligned.trimStart, aligned.trimStart + aligned.length)
}

export async function decryptChunkedObject(
  key: CryptoKey,
  nonce: Uint8Array,
  ciphertext: Uint8Array,
  totalBytes: number,
  context: string,
  chunkBytes = AUTH_CHUNK_BYTES,
): Promise<Uint8Array<ArrayBuffer>> {
  const range = alignRange(0, totalBytes - 1, totalBytes, chunkBytes)
  return decryptRange(key, nonce, range, ciphertext, totalBytes, context, chunkBytes)
}

export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', bytes as BufferSource)
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

export function toBase64(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}

export function fromBase64(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index)
  return bytes
}

function concat(left: Uint8Array, right: Uint8Array): Uint8Array<ArrayBuffer> {
  const merged = new Uint8Array(left.length + right.length)
  merged.set(left, 0)
  merged.set(right, left.length)
  return merged
}

function concatMany(parts: Uint8Array[]): Uint8Array<ArrayBuffer> {
  const merged = new Uint8Array(parts.reduce((total, part) => total + part.length, 0))
  let offset = 0
  for (const part of parts) {
    merged.set(part, offset)
    offset += part.length
  }
  return merged
}

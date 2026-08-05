/**
 * End-to-end encryption for the cloud library.
 *
 * Cloudflare stores bytes it cannot read. That is what makes putting whole
 * recordings in a bucket defensible at all, given this app's premise that
 * originals stay on the device.
 *
 * AES-CTR rather than AES-GCM, and the reason is playback. GCM authenticates
 * the whole message, so you cannot decrypt the middle of a six-hour recording
 * without fetching all of it first. CTR is a keystream: block n depends only on
 * the counter, so any byte range can be decrypted on its own provided the range
 * starts on a 16-byte boundary and the counter is advanced by `offset / 16`.
 * That property is what lets you drag the scrubber to 4:12:00 and hear audio a
 * moment later instead of downloading 350 MB.
 *
 * The trade is that CTR provides no integrity: a tampered byte decrypts to a
 * different byte rather than to an error. What that does and does not buy is
 * worth being precise about. Confidentiality holds — the bucket operator
 * cannot read your recordings. Tamper-detection does not: someone who can
 * WRITE to your R2 bucket could corrupt audio without it failing loudly. The
 * Worker's size check on completion catches truncation but not substitution.
 *
 * The wrapped library key is the exception and uses AES-GCM, because it is
 * small, never range-read, and its authentication is what turns a wrong
 * passphrase into a clear error instead of a garbage key.
 */

const AES_BLOCK_BYTES = 16
/** 8-byte nonce + 8-byte block counter. */
const NONCE_BYTES = 8
const COUNTER_BITS = 64
export const PBKDF2_ITERATIONS = 310_000

export class CloudCryptoError extends Error {
  override readonly name = 'CloudCryptoError'
}

/** Generates the library data key. Random, and never derived from the passphrase directly. */
export async function generateLibraryKey(): Promise<CryptoKey> {
  return crypto.subtle.generateKey({ name: 'AES-CTR', length: 256 }, true, ['encrypt', 'decrypt'])
}

export function randomNonce(): Uint8Array<ArrayBuffer> {
  return crypto.getRandomValues(new Uint8Array(NONCE_BYTES))
}

/**
 * Wraps the data key with a passphrase-derived key.
 *
 * The wrapped form is what gets stored server-side, so a new device needs only
 * the passphrase. The data key itself is random and independent, which means
 * changing the passphrase later could re-wrap the same key without re-uploading
 * anything — whereas deriving the data key from the passphrase directly would
 * make a passphrase change equivalent to losing the library.
 */
export async function wrapLibraryKey(
  key: CryptoKey,
  passphrase: string,
  salt: Uint8Array = crypto.getRandomValues(new Uint8Array(16)),
  iterations: number = PBKDF2_ITERATIONS,
): Promise<{ wrappedKey: string; salt: string; iterations: number }> {
  const wrapping = await deriveWrappingKey(passphrase, salt, iterations)
  const raw = await crypto.subtle.exportKey('raw', key)
  // AES-GCM for the wrapper: this one is small and never range-read, so the
  // authentication it provides is free and detects a wrong passphrase.
  const iv = crypto.getRandomValues(new Uint8Array(12))
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
      { name: 'AES-GCM', iv: payload.slice(0, 12) },
      wrapping,
      payload.slice(12),
    )
    return await crypto.subtle.importKey('raw', raw, { name: 'AES-CTR' }, true, [
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

/** Builds the 16-byte counter block for a given plaintext offset. */
export function counterForOffset(nonce: Uint8Array, offsetBytes: number): Uint8Array<ArrayBuffer> {
  if (offsetBytes % AES_BLOCK_BYTES !== 0) {
    throw new CloudCryptoError('A counter offset must land on a 16-byte block boundary.')
  }
  const counter = new Uint8Array(AES_BLOCK_BYTES)
  counter.set(nonce.slice(0, NONCE_BYTES), 0)
  new DataView(counter.buffer).setBigUint64(NONCE_BYTES, BigInt(offsetBytes / AES_BLOCK_BYTES))
  return counter
}

/** Encrypts a whole object. Ciphertext is the same length as plaintext. */
export async function encryptWhole(
  key: CryptoKey,
  nonce: Uint8Array,
  plaintext: Uint8Array,
): Promise<Uint8Array<ArrayBuffer>> {
  const cipher = await crypto.subtle.encrypt(
    { name: 'AES-CTR', counter: counterForOffset(nonce, 0), length: COUNTER_BITS },
    key,
    plaintext as BufferSource,
  )
  return new Uint8Array(cipher)
}

export async function decryptWhole(
  key: CryptoKey,
  nonce: Uint8Array,
  ciphertext: Uint8Array,
): Promise<Uint8Array<ArrayBuffer>> {
  const plain = await crypto.subtle.decrypt(
    { name: 'AES-CTR', counter: counterForOffset(nonce, 0), length: COUNTER_BITS },
    key,
    ciphertext as BufferSource,
  )
  return new Uint8Array(plain)
}

/**
 * The range a ciphertext fetch must ask for in order to satisfy a plaintext
 * range, together with where the wanted bytes sit inside the result.
 *
 * Callers should not do this arithmetic inline — getting it wrong yields audio
 * that plays as noise rather than an error, which is miserable to debug.
 */
export interface AlignedRange {
  /** First ciphertext byte to fetch; always a multiple of 16. */
  fetchStart: number
  /** Last ciphertext byte to fetch, inclusive. */
  fetchEnd: number
  /** Offset of the wanted bytes within the decrypted fetched block. */
  trimStart: number
  /** Number of wanted bytes. */
  length: number
}

export function alignRange(start: number, end: number, totalBytes: number): AlignedRange {
  if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end < start) {
    throw new CloudCryptoError('Invalid byte range.')
  }
  const clampedEnd = Math.min(end, Math.max(0, totalBytes - 1))
  const fetchStart = Math.floor(start / AES_BLOCK_BYTES) * AES_BLOCK_BYTES
  return {
    fetchStart,
    fetchEnd: clampedEnd,
    trimStart: start - fetchStart,
    length: clampedEnd - start + 1,
  }
}

/** Decrypts a ciphertext slice that began at `fetchStart`, returning the wanted bytes. */
export async function decryptRange(
  key: CryptoKey,
  nonce: Uint8Array,
  aligned: AlignedRange,
  ciphertextSlice: Uint8Array,
): Promise<Uint8Array<ArrayBuffer>> {
  const plain = new Uint8Array(
    await crypto.subtle.decrypt(
      {
        name: 'AES-CTR',
        counter: counterForOffset(nonce, aligned.fetchStart),
        length: COUNTER_BITS,
      },
      key,
      ciphertextSlice as BufferSource,
    ),
  )
  return plain.slice(aligned.trimStart, aligned.trimStart + aligned.length)
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

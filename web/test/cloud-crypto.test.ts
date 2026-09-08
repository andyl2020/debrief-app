import { describe, expect, it } from 'vitest'
import {
  CloudCryptoError,
  GCM_TAG_BYTES,
  alignRange,
  decryptChunk,
  decryptRange,
  decryptWhole,
  encryptChunk,
  encryptWhole,
  encryptedChunkedSize,
  generateLibraryKey,
  randomNonce,
  sha256Hex,
  unwrapLibraryKey,
  wrapLibraryKey,
} from '../src/core/cloud-crypto'

describe('authenticated cloud crypto', () => {
  const plaintext = new Uint8Array(257).map((_, index) => index % 251)
  const chunkBytes = 32
  const context = 'rec-1:audio'

  async function encryptedChunks(key: CryptoKey, nonce: Uint8Array) {
    const chunks: Uint8Array[] = []
    for (let offset = 0, index = 0; offset < plaintext.length; offset += chunkBytes, index += 1) {
      chunks.push(await encryptChunk(key, nonce, index, plaintext.slice(offset, offset + chunkBytes), context))
    }
    return concat(chunks)
  }

  it('authenticates and round-trips a whole metadata object', async () => {
    const key = await generateLibraryKey()
    const nonce = randomNonce()
    const cipher = await encryptWhole(key, nonce, plaintext, 'rec-1:metadata')
    expect(cipher.length).toBe(plaintext.length + GCM_TAG_BYTES)
    expect(await decryptWhole(key, nonce, cipher, 'rec-1:metadata')).toEqual(plaintext)
  })

  it('decrypts arbitrary ranges while authenticating every covering chunk', async () => {
    const key = await generateLibraryKey()
    const nonce = randomNonce()
    const cipher = await encryptedChunks(key, nonce)
    for (const [start, end] of [[0, 15], [1, 100], [31, 65], [250, 256], [256, 256]] as const) {
      const range = alignRange(start, end, plaintext.length, chunkBytes)
      const got = await decryptRange(key, nonce, range, cipher.slice(range.fetchStart, range.fetchEnd + 1), plaintext.length, context, chunkBytes)
      expect(got).toEqual(plaintext.slice(start, end + 1))
    }
  })

  it('maps plaintext ranges to complete authenticated ciphertext chunks', () => {
    const range = alignRange(40, 70, plaintext.length, chunkBytes)
    expect(range).toMatchObject({ firstChunk: 1, lastChunk: 2, fetchStart: 48, fetchEnd: 143, trimStart: 8, length: 31 })
    expect(encryptedChunkedSize(plaintext.length, chunkBytes)).toBe(plaintext.length + 9 * GCM_TAG_BYTES)
  })

  it('fails closed on tampering, truncation, reordering, or object swapping', async () => {
    const key = await generateLibraryKey()
    const nonce = randomNonce()
    const first = await encryptChunk(key, nonce, 0, plaintext.slice(0, chunkBytes), context)
    const second = await encryptChunk(key, nonce, 1, plaintext.slice(chunkBytes, chunkBytes * 2), context)
    const tampered = first.slice(); tampered[2] = tampered[2]! ^ 1
    await expect(decryptChunk(key, nonce, 0, tampered, context)).rejects.toThrow(CloudCryptoError)
    await expect(decryptChunk(key, nonce, 0, first.slice(0, -1), context)).rejects.toThrow(CloudCryptoError)
    await expect(decryptChunk(key, nonce, 0, second, context)).rejects.toThrow(CloudCryptoError)
    await expect(decryptChunk(key, nonce, 0, first, 'another-recording:audio')).rejects.toThrow(CloudCryptoError)
  })

  it('rejects invalid ranges', () => {
    expect(() => alignRange(-1, 10, 100, chunkBytes)).toThrow(CloudCryptoError)
    expect(() => alignRange(50, 10, 100, chunkBytes)).toThrow(CloudCryptoError)
  })
})

describe('library key wrapping', () => {
  it('unwraps only with the right passphrase', async () => {
    const key = await generateLibraryKey()
    const wrapped = await wrapLibraryKey(key, 'correct horse battery staple')
    const recovered = await unwrapLibraryKey(wrapped.wrappedKey, 'correct horse battery staple', wrapped.salt, wrapped.iterations)
    const nonce = randomNonce()
    const cipher = await encryptWhole(key, nonce, new TextEncoder().encode('hello'), 'test')
    expect(new TextDecoder().decode(await decryptWhole(recovered, nonce, cipher, 'test'))).toBe('hello')
    await expect(unwrapLibraryKey(wrapped.wrappedKey, 'wrong', wrapped.salt, wrapped.iterations)).rejects.toThrow(CloudCryptoError)
  })

  it('never exposes the raw key in the wrapped payload', async () => {
    const key = await generateLibraryKey()
    const raw = new Uint8Array(await crypto.subtle.exportKey('raw', key))
    const wrapped = await wrapLibraryKey(key, 'correct horse battery staple')
    expect(atob(wrapped.wrappedKey)).not.toContain(String.fromCharCode(...raw))
  })
})

it('produces a stable sha256 digest', async () => {
  expect(await sha256Hex(new TextEncoder().encode('abc'))).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
})

function concat(parts: Uint8Array[]): Uint8Array {
  const output = new Uint8Array(parts.reduce((total, part) => total + part.length, 0))
  let offset = 0
  for (const part of parts) { output.set(part, offset); offset += part.length }
  return output
}

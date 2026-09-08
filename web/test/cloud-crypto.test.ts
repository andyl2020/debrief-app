import { describe, expect, it } from 'vitest'
import {
  CloudCryptoError,
  alignRange,
  decryptRange,
  decryptWhole,
  encryptWhole,
  generateLibraryKey,
  randomNonce,
  sha256Hex,
  unwrapLibraryKey,
  wrapLibraryKey,
} from '../src/core/cloud-crypto'

/**
 * The load-bearing property is range decryption. If it is wrong the audio does
 * not error, it plays as noise — so it is worth testing at boundaries rather
 * than once in the middle.
 */
describe('cloud crypto', () => {
  const plaintext = new Uint8Array(4096).map((_, index) => index % 251)

  it('round-trips a whole object', async () => {
    const key = await generateLibraryKey()
    const nonce = randomNonce()

    const cipher = await encryptWhole(key, nonce, plaintext)

    // CTR is a stream cipher: ciphertext is exactly as long as plaintext, which
    // is what lets byte offsets map one to one.
    expect(cipher.length).toBe(plaintext.length)
    expect(Buffer.from(cipher).equals(Buffer.from(plaintext))).toBe(false)
    expect(Buffer.from(await decryptWhole(key, nonce, cipher))).toEqual(Buffer.from(plaintext))
  })

  it('decrypts an arbitrary byte range without the rest of the file', async () => {
    // This is what makes seeking to 4:12:00 in a 350 MB recording possible.
    const key = await generateLibraryKey()
    const nonce = randomNonce()
    const cipher = await encryptWhole(key, nonce, plaintext)

    for (const [start, end] of [
      [0, 15],
      [1, 100],
      [1000, 2000],
      [16, 31],
      [4080, 4095],
      [4095, 4095],
      [17, 17],
    ] as const) {
      const aligned = alignRange(start, end, plaintext.length)
      const slice = cipher.slice(aligned.fetchStart, aligned.fetchEnd + 1)
      const got = await decryptRange(key, nonce, aligned, slice)

      expect(Buffer.from(got)).toEqual(Buffer.from(plaintext.slice(start, end + 1)))
    }
  })

  it('always fetches from a block boundary and reports the trim', async () => {
    const aligned = alignRange(1000, 2000, 4096)

    expect(aligned.fetchStart % 16).toBe(0)
    expect(aligned.fetchStart).toBe(992)
    expect(aligned.trimStart).toBe(8)
    expect(aligned.length).toBe(1001)
  })

  it('clamps a range that runs past the end of the object', async () => {
    const key = await generateLibraryKey()
    const nonce = randomNonce()
    const cipher = await encryptWhole(key, nonce, plaintext)

    const aligned = alignRange(4000, 999_999, plaintext.length)
    const got = await decryptRange(key, nonce, aligned, cipher.slice(aligned.fetchStart, aligned.fetchEnd + 1))

    expect(Buffer.from(got)).toEqual(Buffer.from(plaintext.slice(4000)))
  })

  it('rejects a nonsensical range rather than returning wrong bytes', () => {
    expect(() => alignRange(-1, 10, 100)).toThrow(CloudCryptoError)
    expect(() => alignRange(50, 10, 100)).toThrow(CloudCryptoError)
  })

  it('produces different ciphertext for the same plaintext under different nonces', async () => {
    const key = await generateLibraryKey()

    const first = await encryptWhole(key, randomNonce(), plaintext)
    const second = await encryptWhole(key, randomNonce(), plaintext)

    // Reusing a counter stream across objects would leak plaintext by XOR, so
    // each object must get its own nonce.
    expect(Buffer.from(first).equals(Buffer.from(second))).toBe(false)
  })
})

describe('library key wrapping', () => {
  it('unwraps with the right passphrase on a device that has never seen the key', async () => {
    // The point of storing the wrapped key server-side: a new phone needs only
    // the passphrase, with nothing transferred by hand.
    const key = await generateLibraryKey()
    const wrapped = await wrapLibraryKey(key, 'correct horse battery staple')

    const recovered = await unwrapLibraryKey(
      wrapped.wrappedKey,
      'correct horse battery staple',
      wrapped.salt,
      wrapped.iterations,
    )

    const nonce = randomNonce()
    const cipher = await encryptWhole(key, nonce, new TextEncoder().encode('hello'))
    expect(new TextDecoder().decode(await decryptWhole(recovered, nonce, cipher))).toBe('hello')
  })

  it('refuses the wrong passphrase instead of returning a useless key', async () => {
    const wrapped = await wrapLibraryKey(await generateLibraryKey(), 'correct horse battery staple')

    await expect(
      unwrapLibraryKey(wrapped.wrappedKey, 'wrong passphrase', wrapped.salt, wrapped.iterations),
    ).rejects.toThrow(CloudCryptoError)
  })

  it('never exposes the raw key in what gets uploaded', async () => {
    const key = await generateLibraryKey()
    const raw = new Uint8Array(await crypto.subtle.exportKey('raw', key))
    const wrapped = await wrapLibraryKey(key, 'correct horse battery staple')

    const payload = atob(wrapped.wrappedKey)
    const rawBinary = String.fromCharCode(...raw)
    expect(payload.includes(rawBinary)).toBe(false)
  })
})

describe('sha256Hex', () => {
  it('produces a stable lowercase digest', async () => {
    expect(await sha256Hex(new TextEncoder().encode('abc'))).toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    )
  })
})

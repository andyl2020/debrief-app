import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { encryptWhole, generateLibraryKey, randomNonce } from '../src/core/cloud-crypto'

/**
 * Drives the real `public/sw.js`.
 *
 * The service worker duplicates the AES-CTR counter arithmetic because it is a
 * classic worker and cannot import from src. Duplicated crypto that drifts
 * produces audio that plays as noise rather than an error, so this evaluates
 * the actual shipped file and checks it against the same plaintext the app's
 * own implementation produced.
 */

interface SwHarness {
  fetchHandler: (event: FetchEventLike) => void
  messageHandler: (event: { data: unknown; ports?: Array<{ postMessage: (value: unknown) => void }> }) => void
}

interface FetchEventLike {
  request: Request
  respondWith: (response: Response | Promise<Response>) => void
}

let harness: SwHarness

beforeAll(() => {
  // Read from the project root: under Vitest `import.meta.url` is not a file URL.
  const source = readFileSync(resolve(process.cwd(), 'public/sw.js'), 'utf8')

  const listeners = new Map<string, (event: never) => void>()
  const swSelf = {
    location: { origin: 'https://app.test' },
    addEventListener: (type: string, handler: (event: never) => void) => listeners.set(type, handler),
    skipWaiting: () => Promise.resolve(),
    clients: { claim: () => Promise.resolve() },
  }

  // Evaluate the shipped worker with a stub global, capturing its listeners.
  new Function('self', 'crypto', 'fetch', 'Response', 'Headers', 'URL', source)(
    swSelf,
    globalThis.crypto,
    // Indirect, so `vi.stubGlobal('fetch', ...)` inside a test is what the
    // worker actually calls rather than the binding captured at eval time.
    (...args: Parameters<typeof fetch>) => globalThis.fetch(...args),
    Response,
    Headers,
    URL,
  )

  harness = {
    fetchHandler: listeners.get('fetch') as unknown as SwHarness['fetchHandler'],
    messageHandler: listeners.get('message') as unknown as SwHarness['messageHandler'],
  }
  expect(harness.fetchHandler).toBeTypeOf('function')
  expect(harness.messageHandler).toBeTypeOf('function')
})

describe('service worker playback proxy', () => {
  const plaintext = new Uint8Array(50_000).map((_, index) => index % 251)

  async function setup() {
    const key = await generateLibraryKey()
    const nonce = randomNonce()
    const cipher = await encryptWhole(key, nonce, plaintext)

    // Stand in for the Cloudflare Worker: serve ciphertext byte ranges.
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string, init?: RequestInit) => {
        // Read the plain object rather than constructing Headers: jsdom drops
        // Range, which the Fetch spec treats as a forbidden header name, so a
        // Headers round-trip here would silently lose it.
        const header = (init?.headers as Record<string, string> | undefined)?.['Range'] ?? ''
        const match = /^bytes=(\d+)-(\d+)$/.exec(header)
        if (!match) return new Response(cipher as BodyInit, { status: 200 })
        const start = Number(match[1])
        const end = Number(match[2])
        return new Response(cipher.slice(start, end + 1) as BodyInit, { status: 206 })
      }),
    )

    send({ type: 'debrief/set-key', key })
    send({
      type: 'debrief/set-item',
      id: 'rec-1',
      nonce,
      totalBytes: plaintext.length,
      url: 'https://cloud.test/object',
      token: 'owner-token',
      mimeType: 'audio/mp4',
    })
    return { key, nonce }
  }

  function send(data: unknown): void {
    harness.messageHandler({ data, ports: [{ postMessage: () => undefined }] })
  }

  async function request(headers: Record<string, string> = {}, method = 'GET'): Promise<Response> {
    let captured: Promise<Response> | Response | null = null
    harness.fetchHandler({
      request: new Request('https://app.test/__cloud-audio/rec-1', { method, headers }),
      respondWith: (response) => {
        captured = response
      },
    })
    if (!captured) throw new Error('the worker did not respond to the request')
    return await captured
  }

  it('serves a decrypted byte range with the headers a player needs', async () => {
    await setup()

    const response = await request({ Range: 'bytes=1000-1999' })

    expect(response.status).toBe(206)
    expect(response.headers.get('Content-Range')).toBe(`bytes 1000-1999/${plaintext.length}`)
    expect(response.headers.get('Accept-Ranges')).toBe('bytes')
    expect(response.headers.get('Content-Length')).toBe('1000')

    const body = new Uint8Array(await response.arrayBuffer())
    expect(Buffer.from(body)).toEqual(Buffer.from(plaintext.slice(1000, 2000)))
  })

  it('matches the app implementation at unaligned offsets', async () => {
    // The whole reason this test exists: a wrong counter offset returns bytes
    // rather than an error, so it has to be compared against known plaintext.
    await setup()

    for (const [start, end] of [
      [0, 15],
      [1, 100],
      [17, 17],
      [16_383, 16_400],
      [49_990, 49_999],
    ] as const) {
      const response = await request({ Range: `bytes=${start}-${end}` })
      const body = new Uint8Array(await response.arrayBuffer())
      expect(Buffer.from(body)).toEqual(Buffer.from(plaintext.slice(start, end + 1)))
    }
  })

  it('serves the whole object when no range is requested', async () => {
    await setup()

    const response = await request()

    expect(response.status).toBe(200)
    expect(response.headers.get('Content-Length')).toBe(String(plaintext.length))
    expect(Buffer.from(new Uint8Array(await response.arrayBuffer()))).toEqual(Buffer.from(plaintext))
  })

  it('answers HEAD with the full length, which Safari needs before playing', async () => {
    await setup()

    const response = await request({}, 'HEAD')

    expect(response.status).toBe(200)
    expect(response.headers.get('Content-Length')).toBe(String(plaintext.length))
    expect(response.headers.get('Accept-Ranges')).toBe('bytes')
  })

  it('rejects an unsatisfiable range', async () => {
    await setup()

    const response = await request({ Range: 'bytes=999999-1000000' })

    expect(response.status).toBe(416)
  })

  it('refuses to serve anything once the key is cleared', async () => {
    // Locking must actually lock: after clearing, the proxy has no key and no
    // item, so a stale <audio> element cannot keep pulling plaintext.
    await setup()
    send({ type: 'debrief/clear' })

    const response = await request({ Range: 'bytes=0-99' })

    expect(response.status).toBe(503)
  })

  it('reports a failure upstream rather than returning silent garbage', async () => {
    await setup()
    vi.stubGlobal('fetch', vi.fn(async () => new Response('nope', { status: 500 })))

    const response = await request({ Range: 'bytes=0-99' })

    expect(response.status).toBe(502)
  })
})

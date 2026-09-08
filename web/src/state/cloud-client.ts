import { IoError, SecurityError, ValidationError } from '../core/errors'

/**
 * HTTP client for the Worker's owner API.
 *
 * Kept free of encryption and of app state so it can be tested against a fake
 * fetch, and so the crypto boundary stays obvious: everything this class sends
 * or receives is already ciphertext.
 */

export interface CloudItem {
  id: string
  version: number
  updatedAt: number
  status: 'PENDING' | 'COMPLETE'
  cryptoVersion: number
  chunkBytes: number
  audioBytes: number
  audioCipherBytes: number
  audioNonce: string
  audioReady: boolean
  metadataBytes: number
  metadataNonce: string
  metadataReady: boolean
}

export interface CloudUsage {
  bytes: number
  items: number
  freeReferenceBytes: number
}

export interface WrappedKeyRecord {
  wrappedKey: string
  salt: string
  iterations: number
}

export type ObjectKind = 'audio' | 'metadata'

export class CloudClient {
  constructor(
    readonly baseUrl: string,
    private readonly token: string,
  ) {}

  /**
   * Exchanges a one-time pairing code for a device token.
   *
   * The bootstrap secret stays on the deployment; the code is what the user
   * carries to a new device, which is why it is short-lived and single-use.
   */
  static async pair(baseUrl: string, code: string, label: string): Promise<string> {
    const response = await fetch(`${trimEnd(baseUrl)}/v1/pair`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: code.trim(), label }),
    })
    if (!response.ok) throw await errorFor(response, 'That pairing code was not accepted.')
    const body = (await response.json()) as { ownerToken?: string }
    if (!body.ownerToken) throw new IoError('The share service did not return a device token.')
    return body.ownerToken
  }

  static async health(baseUrl: string): Promise<boolean> {
    try {
      const response = await fetch(`${trimEnd(baseUrl)}/health`)
      return response.ok
    } catch {
      return false
    }
  }

  async listItems(): Promise<CloudItem[]> {
    const body = await this.json<{ items: CloudItem[] }>('GET', '/v1/owner/library')
    return body.items
  }

  async usage(): Promise<CloudUsage> {
    return this.json<CloudUsage>('GET', '/v1/owner/library/usage')
  }

  /** Returns null when the library has not been set up yet. */
  async getWrappedKey(): Promise<WrappedKeyRecord | null> {
    const response = await this.fetch('GET', '/v1/owner/library/key')
    if (response.status === 404) return null
    if (!response.ok) throw await errorFor(response, 'Could not read the cloud library key.')
    return (await response.json()) as WrappedKeyRecord
  }

  async putWrappedKey(record: WrappedKeyRecord, replace = false): Promise<void> {
    const response = await this.fetch('PUT', '/v1/owner/library/key', {
      body: JSON.stringify({ ...record, replace }),
      headers: { 'Content-Type': 'application/json' },
    })
    if (!response.ok) throw await errorFor(response, 'Could not save the cloud library key.')
  }

  async beginItem(input: {
    id: string
    audioBytes: number
    audioCipherBytes: number
    metadataBytes: number
    audioNonce: string
    metadataNonce: string
    cryptoVersion: number
    chunkBytes: number
  }): Promise<{ partBytes: number }> {
    return this.json<{ partBytes: number }>('POST', '/v1/owner/library/items', {
      body: JSON.stringify(input),
      headers: { 'Content-Type': 'application/json' },
    })
  }

  async uploadPart(
    id: string,
    kind: ObjectKind,
    partNumber: number,
    chunk: Uint8Array,
  ): Promise<{ partNumber: number; etag: string }> {
    const response = await this.fetch(
      'PUT',
      `/v1/owner/library/items/${encodeURIComponent(id)}/objects/${kind}/parts/${partNumber}`,
      {
        body: chunk as BodyInit,
        headers: { 'Content-Type': 'application/octet-stream' },
      },
    )
    if (!response.ok) throw await errorFor(response, 'An upload part was rejected.')
    return (await response.json()) as { partNumber: number; etag: string }
  }

  async completeObject(
    id: string,
    kind: ObjectKind,
    parts: Array<{ partNumber: number; etag: string }>,
  ): Promise<void> {
    const response = await this.fetch(
      'POST',
      `/v1/owner/library/items/${encodeURIComponent(id)}/objects/${kind}/complete`,
      { body: JSON.stringify({ parts }), headers: { 'Content-Type': 'application/json' } },
    )
    if (!response.ok) throw await errorFor(response, 'The upload could not be completed.')
  }

  objectUrl(id: string, kind: ObjectKind): string {
    return `${trimEnd(this.baseUrl)}/v1/owner/library/items/${encodeURIComponent(id)}/objects/${kind}`
  }

  /** Fetches an object, optionally a byte range. Returns ciphertext. */
  async fetchObject(id: string, kind: ObjectKind, range?: { start: number; end: number }): Promise<Response> {
    const headers: Record<string, string> = { Authorization: `Bearer ${this.token}` }
    if (range) headers['Range'] = `bytes=${range.start}-${range.end}`
    const response = await fetch(this.objectUrl(id, kind), { headers })
    if (!response.ok && response.status !== 206) {
      throw await errorFor(response, 'Could not download that recording from the cloud.')
    }
    return response
  }

  async deleteItem(id: string): Promise<void> {
    const response = await this.fetch('DELETE', `/v1/owner/library/items/${encodeURIComponent(id)}`)
    if (!response.ok) throw await errorFor(response, 'Could not remove that recording from the cloud.')
  }

  private async fetch(method: string, path: string, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers)
    headers.set('Authorization', `Bearer ${this.token}`)
    try {
      return await fetch(`${trimEnd(this.baseUrl)}${path}`, { ...init, method, headers })
    } catch (error) {
      // A CORS rejection and an offline device both surface as a bare
      // TypeError, and the fix is very different, so say both.
      throw new IoError(
        'Could not reach the cloud service. Check your connection, and that this site is listed in the Worker’s ALLOWED_ORIGINS.',
        { cause: error },
      )
    }
  }

  private async json<T>(method: string, path: string, init: RequestInit = {}): Promise<T> {
    const response = await this.fetch(method, path, init)
    if (!response.ok) throw await errorFor(response, 'The cloud service rejected that request.')
    return (await response.json()) as T
  }
}

async function errorFor(response: Response, fallback: string): Promise<Error> {
  let code = ''
  let message = ''
  try {
    const body = (await response.json()) as { error?: { code?: string; message?: string } }
    code = body.error?.code ?? ''
    message = body.error?.message ?? ''
  } catch {
    // Non-JSON error body; fall through to the generic message.
  }
  if (response.status === 401) {
    return new SecurityError(message || 'This device is not paired with the cloud service.')
  }
  if (response.status === 409 && code === 'LIBRARY_KEY_EXISTS') {
    return new ValidationError(message)
  }
  if (response.status >= 400 && response.status < 500) {
    return new ValidationError(message || fallback)
  }
  return new IoError(message || fallback)
}

function trimEnd(url: string): string {
  return url.replace(/\/+$/, '')
}

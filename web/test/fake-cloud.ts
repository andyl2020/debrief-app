import type { CloudClient, CloudItem, CloudUsage, ObjectKind, WrappedKeyRecord } from '../src/state/cloud-client'

/**
 * An in-memory stand-in for the Worker's owner API.
 *
 * Mirrors the parts of the real contract that can go wrong on the client side:
 * multipart assembly in order, size checks, byte-range serving, and the refusal
 * to replace a library key once items exist. The Worker's own behaviour is
 * tested separately against real D1 and R2 in `cloudflare/test`.
 */
export class FakeCloud {
  private objects = new Map<string, Uint8Array>()
  private parts = new Map<string, Map<number, Uint8Array>>()
  private items = new Map<string, CloudItem>()
  private wrappedKey: WrappedKeyRecord | null = null

  /** Cast to CloudClient: it implements the surface the sync engine uses. */
  asClient(): CloudClient {
    return this as unknown as CloudClient
  }

  async listItems(): Promise<CloudItem[]> {
    return [...this.items.values()]
  }

  async usage(): Promise<CloudUsage> {
    let bytes = 0
    for (const object of this.objects.values()) bytes += object.length
    return { bytes, items: this.items.size, freeReferenceBytes: 10_000_000_000 }
  }

  async getWrappedKey(): Promise<WrappedKeyRecord | null> {
    return this.wrappedKey
  }

  async putWrappedKey(record: WrappedKeyRecord, replace = false): Promise<void> {
    if (this.wrappedKey && !replace && this.items.size > 0) {
      throw new Error('LIBRARY_KEY_EXISTS')
    }
    this.wrappedKey = record
  }

  async beginItem(input: {
    id: string
    audioBytes: number
    metadataBytes: number
    audioNonce: string
    metadataNonce: string
  }): Promise<{ partBytes: number }> {
    const previous = this.items.get(input.id)
    this.items.set(input.id, {
      id: input.id,
      version: (previous?.version ?? 0) + 1,
      updatedAt: Date.now(),
      status: 'PENDING',
      audioBytes: input.audioBytes,
      audioNonce: input.audioNonce,
      audioReady: false,
      metadataBytes: input.metadataBytes,
      metadataNonce: input.metadataNonce,
      metadataReady: false,
    })
    this.parts.delete(`${input.id}:audio`)
    this.parts.delete(`${input.id}:metadata`)
    return { partBytes: 8 * 1024 * 1024 }
  }

  async uploadPart(
    id: string,
    kind: ObjectKind,
    partNumber: number,
    chunk: Uint8Array,
  ): Promise<{ partNumber: number; etag: string }> {
    const key = `${id}:${kind}`
    const bucket = this.parts.get(key) ?? new Map<number, Uint8Array>()
    bucket.set(partNumber, chunk.slice())
    this.parts.set(key, bucket)
    return { partNumber, etag: `etag-${partNumber}` }
  }

  async completeObject(
    id: string,
    kind: ObjectKind,
    parts: Array<{ partNumber: number; etag: string }>,
  ): Promise<void> {
    const bucket = this.parts.get(`${id}:${kind}`)
    if (!bucket) throw new Error('No parts were uploaded.')
    // Assemble in the order the caller declared, exactly as R2 does.
    const ordered = parts.map((part) => {
      const chunk = bucket.get(part.partNumber)
      if (!chunk) throw new Error(`Missing part ${part.partNumber}`)
      return chunk
    })
    const total = ordered.reduce((sum, chunk) => sum + chunk.length, 0)
    const merged = new Uint8Array(total)
    let offset = 0
    for (const chunk of ordered) {
      merged.set(chunk, offset)
      offset += chunk.length
    }

    const item = this.items.get(id)
    if (!item) throw new Error('Unknown item.')
    const expected = kind === 'audio' ? item.audioBytes : item.metadataBytes
    if (merged.length !== expected) throw new Error('SIZE_MISMATCH')

    this.objects.set(`${id}:${kind}`, merged)
    const updated: CloudItem = {
      ...item,
      audioReady: kind === 'audio' ? true : item.audioReady,
      metadataReady: kind === 'metadata' ? true : item.metadataReady,
    }
    updated.status = updated.audioReady && updated.metadataReady ? 'COMPLETE' : 'PENDING'
    this.items.set(id, updated)
  }

  objectUrl(id: string, kind: ObjectKind): string {
    return `https://cloud.test/v1/owner/library/items/${id}/objects/${kind}`
  }

  async fetchObject(id: string, kind: ObjectKind, range?: { start: number; end: number }): Promise<Response> {
    const object = this.objects.get(`${id}:${kind}`)
    if (!object) throw new Error('Object not found.')
    const slice = range ? object.slice(range.start, range.end + 1) : object
    return new Response(slice as BodyInit, { status: range ? 206 : 200 })
  }

  async deleteItem(id: string): Promise<void> {
    this.items.delete(id)
    this.objects.delete(`${id}:audio`)
    this.objects.delete(`${id}:metadata`)
  }

  /** Test inspection: what the server would actually be storing. */
  storedBytes(id: string, kind: ObjectKind): Uint8Array | undefined {
    return this.objects.get(`${id}:${kind}`)
  }
}

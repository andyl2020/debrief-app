/**
 * A tiny in-memory File System Access implementation.
 *
 * jsdom has neither OPFS nor `showDirectoryPicker`, so without this the two
 * storage adapters could only be tested by mocking themselves - which proves
 * nothing. This implements enough of the real API surface that both adapters
 * run their genuine code paths against it.
 */

class FakeWritable {
  private chunks: Uint8Array[] = []
  constructor(private readonly commit: (bytes: Uint8Array) => void) {}

  async write(data: unknown): Promise<void> {
    if (typeof data === 'string') {
      this.chunks.push(new TextEncoder().encode(data))
      return
    }
    // Node's Blob.stream() yields typed arrays from a different realm than
    // jsdom's globals, so `instanceof Uint8Array` is false for them. Duck-type
    // on the buffer view shape instead.
    if (ArrayBuffer.isView(data)) {
      const view = data as ArrayBufferView
      this.chunks.push(new Uint8Array(view.buffer, view.byteOffset, view.byteLength))
      return
    }
    if (data instanceof ArrayBuffer || Object.prototype.toString.call(data) === '[object ArrayBuffer]') {
      this.chunks.push(new Uint8Array(data as ArrayBuffer))
      return
    }
    if (data && typeof data === 'object' && 'data' in data) {
      await this.write((data as { data: unknown }).data)
    }
  }

  async close(): Promise<void> {
    const total = this.chunks.reduce((sum, chunk) => sum + chunk.byteLength, 0)
    const merged = new Uint8Array(total)
    let offset = 0
    for (const chunk of this.chunks) {
      merged.set(chunk, offset)
      offset += chunk.byteLength
    }
    this.commit(merged)
  }

  /** `pipeTo` needs a WritableStream, which is what the adapters actually use. */
  toStream(): WritableStream<Uint8Array> {
    return new WritableStream<Uint8Array>({
      write: (chunk) => void this.write(chunk),
      close: () => void this.close(),
    })
  }
}

class FakeFileHandle {
  readonly kind = 'file' as const
  constructor(
    readonly name: string,
    private readonly directory: FakeDirectoryHandle,
  ) {}

  async getFile(): Promise<File> {
    const bytes = this.directory.files.get(this.name)
    if (!bytes) throw new DOMException('not found', 'NotFoundError')
    return new File([bytes as BlobPart], this.name, {
      lastModified: this.directory.timestamps.get(this.name) ?? 0,
    })
  }

  async createWritable(): Promise<WritableStream<Uint8Array> & { write: (d: unknown) => Promise<void>; close: () => Promise<void> }> {
    const writable = new FakeWritable((bytes) => {
      this.directory.files.set(this.name, bytes)
      this.directory.timestamps.set(this.name, this.directory.nextTimestamp())
    })
    const stream = writable.toStream() as WritableStream<Uint8Array> & {
      write: (d: unknown) => Promise<void>
      close: () => Promise<void>
    }
    // The adapters use both `pipeTo(writable)` and `writable.write/close`, so
    // expose both shapes on one object.
    stream.write = (data: unknown) => writable.write(data)
    stream.close = () => writable.close()
    return stream
  }
}

export class FakeDirectoryHandle {
  readonly kind = 'directory' as const
  readonly files = new Map<string, Uint8Array>()
  readonly timestamps = new Map<string, number>()
  private readonly children = new Map<string, FakeDirectoryHandle>()
  private clock = 1_700_000_000_000
  /** Set false to emulate Safari 16, which has OPFS but no `createWritable`. */
  supportsCreateWritable = true

  constructor(readonly name = 'Recordings') {}

  nextTimestamp(): number {
    this.clock += 1_000
    return this.clock
  }

  async getFileHandle(name: string, options?: { create?: boolean }) {
    if (!this.files.has(name)) {
      if (!options?.create) throw new DOMException('not found', 'NotFoundError')
      this.files.set(name, new Uint8Array())
      this.timestamps.set(name, this.nextTimestamp())
    }
    const handle = new FakeFileHandle(name, this)
    if (this.supportsCreateWritable) return handle as unknown as FileSystemFileHandle
    // Safari 16.4 shipped OPFS without `createWritable`; it only arrived in
    // Safari 17. Hand back a handle genuinely lacking the method so the
    // adapter's feature check is what decides, not a stubbed return value.
    const { createWritable: _omitted, ...rest } = handle as unknown as Record<string, unknown>
    return Object.assign(Object.create(null), rest, {
      kind: 'file',
      name,
      getFile: () => handle.getFile(),
    }) as unknown as FileSystemFileHandle
  }

  async getDirectoryHandle(name: string, options?: { create?: boolean }) {
    let child = this.children.get(name)
    if (!child) {
      if (!options?.create) throw new DOMException('not found', 'NotFoundError')
      child = new FakeDirectoryHandle(name)
      this.children.set(name, child)
    }
    return child as unknown as FileSystemDirectoryHandle
  }

  async removeEntry(name: string): Promise<void> {
    if (!this.files.delete(name)) throw new DOMException('not found', 'NotFoundError')
    this.timestamps.delete(name)
  }

  async *entries(): AsyncIterableIterator<[string, FileSystemHandle]> {
    for (const name of [...this.files.keys()]) {
      yield [name, new FakeFileHandle(name, this) as unknown as FileSystemHandle]
    }
  }

  /** Seeds a file as if it were already sitting in the folder. */
  seed(name: string, contents: string): void {
    this.files.set(name, new TextEncoder().encode(contents))
    this.timestamps.set(name, this.nextTimestamp())
  }
}

export function audioFile(name: string, contents = 'fake-audio-bytes'): File {
  return new File([contents], name, { type: 'audio/mp4', lastModified: 1_700_000_000_000 })
}

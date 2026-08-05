import { beforeEach, describe, expect, it } from 'vitest'
import { OpfsStorageAdapter } from '../src/storage/opfs-adapter'
import { FileSystemAccessAdapter } from '../src/storage/fsa-adapter'
import { freshDatabase } from './fresh-db'
import { backupSidecarName, sidecarName } from '../src/core/sidecar'
import type { StorageAdapter } from '../src/storage/adapter'
import { isAudioFileName, isSidecarFileName, guessMimeType } from '../src/storage/adapter'
import { FakeDirectoryHandle, audioFile } from './fake-fs'


describe('storage adapter file-name rules', () => {
  it('recognises the audio formats the Android library accepts', () => {
    expect(isAudioFileName('Interview.m4a')).toBe(true)
    expect(isAudioFileName('Interview.MP3')).toBe(true)
    expect(isAudioFileName('Interview.wav')).toBe(true)
    expect(isAudioFileName('Interview.aac')).toBe(true)
    expect(isAudioFileName('Interview.m4a.debrief.json')).toBe(false)
    expect(isAudioFileName('notes.txt')).toBe(false)
  })

  it('recognises both sidecar copies', () => {
    expect(isSidecarFileName('Interview.m4a.debrief.json')).toBe(true)
    expect(isSidecarFileName('Interview.m4a.debrief.backup.json')).toBe(true)
    expect(isSidecarFileName('Interview.m4a')).toBe(false)
  })

  it('guesses a MIME type when the platform omits one', () => {
    expect(guessMimeType('a.m4a')).toBe('audio/mp4')
    expect(guessMimeType('a.mp3')).toBe('audio/mpeg')
    expect(guessMimeType('a.txt')).toBeNull()
  })
})

/**
 * The same contract, run against both real adapter implementations. This is the
 * point where "hybrid storage" either genuinely works on both paths or does not.
 */
describe.each([
  ['OpfsStorageAdapter', async () => makeOpfs()],
  ['FileSystemAccessAdapter', async () => makeFsa()],
])('%s storage contract', (_name, build) => {
  let adapter: StorageAdapter

  beforeEach(async () => {
    await freshDatabase()
    adapter = await build()
  })

  it('starts empty', async () => {
    expect(await adapter.list()).toEqual([])
  })

  it('adds audio and reads it back without loading it into memory as bytes', async () => {
    const [source] = await adapter.add([audioFile('Interview.m4a', 'hello-audio')])

    const listed = await adapter.list()
    expect(listed).toHaveLength(1)
    expect(listed[0]!.name).toBe('Interview.m4a')

    const blob = await adapter.open(source!.key)
    expect(blob).toBeInstanceOf(Blob)
    expect(await blob.text()).toBe('hello-audio')
  })

  it('round-trips a sidecar under the name Android uses', async () => {
    await adapter.add([audioFile('Interview.m4a')])
    await adapter.writeSidecar('Interview.m4a', '{"schemaVersion":4}')

    expect(await adapter.readSidecar('Interview.m4a')).toBe('{"schemaVersion":4}')
    expect(await adapter.readSidecar('Missing.m4a')).toBeNull()
  })

  it('removes a recording', async () => {
    const [source] = await adapter.add([audioFile('Interview.m4a')])
    await adapter.remove(source!.key)

    expect(await adapter.list()).toEqual([])
  })

  it('reports an actionable error when the audio is gone', async () => {
    const [source] = await adapter.add([audioFile('Interview.m4a')])
    await adapter.remove(source!.key)

    await expect(adapter.open(source!.key)).rejects.toThrow()
  })
})

describe('FileSystemAccessAdapter specifics', () => {
  beforeEach(freshDatabase)

  it('writes both the sidecar and its backup copy, as SidecarStore does', async () => {
    const directory = new FakeDirectoryHandle()
    const adapter = await makeFsa(directory)

    await adapter.writeSidecar('Interview.m4a', '{"schemaVersion":4}')

    expect(directory.files.has(sidecarName('Interview.m4a'))).toBe(true)
    expect(directory.files.has(backupSidecarName('Interview.m4a'))).toBe(true)
  })

  it('falls back to the backup copy when the primary sidecar is missing', async () => {
    const directory = new FakeDirectoryHandle()
    directory.seed(backupSidecarName('Interview.m4a'), '{"schemaVersion":4,"recordingName":"x"}')
    const adapter = await makeFsa(directory)

    expect(await adapter.readSidecar('Interview.m4a')).toContain('schemaVersion')
  })

  it('lists only audio, ignoring the sidecars sitting beside it', async () => {
    const directory = new FakeDirectoryHandle()
    directory.seed('Interview.m4a', 'audio')
    directory.seed('Interview.m4a.debrief.json', '{}')
    directory.seed('notes.txt', 'hello')
    const adapter = await makeFsa(directory)

    expect((await adapter.list()).map((source) => source.name)).toEqual(['Interview.m4a'])
  })

  it('reports itself as the mode with full Android parity', async () => {
    const adapter = await makeFsa()
    expect(adapter.mode).toBe('linked-folder')
    expect(adapter.supportsAutomaticSidecars).toBe(true)
    expect(adapter.label).toContain('Recordings')
  })

  it('restores nothing when no folder was ever linked', async () => {
    expect(await FileSystemAccessAdapter.restore()).toBeNull()
    expect(await FileSystemAccessAdapter.reauthorize()).toBeNull()
  })
})

describe('OpfsStorageAdapter specifics', () => {
  beforeEach(freshDatabase)

  it('is the mode without automatic sidecars, and says so', async () => {
    const adapter = await makeOpfs()
    expect(adapter.mode).toBe('browser-storage')
    expect(adapter.supportsAutomaticSidecars).toBe(false)
  })
})

// --- adapter construction against the fake file system --------------------

async function makeOpfs(): Promise<StorageAdapter> {
  const root = new FakeDirectoryHandle('root')
  Object.defineProperty(globalThis.navigator, 'storage', {
    value: { getDirectory: async () => root },
    configurable: true,
  })
  return new OpfsStorageAdapter()
}

async function makeFsa(
  directory: FakeDirectoryHandle = new FakeDirectoryHandle(),
): Promise<StorageAdapter> {
  // Real browsers hand back a live platform object from IndexedDB; the fake
  // structured-clones it and loses the prototype. Adopt the handle directly so
  // the adapter's own code paths are what's under test.
  return FileSystemAccessAdapter.adopt(directory as unknown as FileSystemDirectoryHandle)
}

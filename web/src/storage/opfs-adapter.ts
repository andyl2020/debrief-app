import { IoError } from '../core/errors'
import { backupSidecarName, sidecarName } from '../core/sidecar'
import {
  guessMimeType,
  type AudioSource,
  type StorageAdapter,
  type StorageMode,
} from './adapter'
import { STORES, get, getAll, put, remove } from './db'

/**
 * The universal storage path, and the one iOS Safari uses.
 *
 * Audio bytes go into the origin-private file system, which every modern
 * browser has including iOS Safari 16.4+. Sidecars are kept in IndexedDB and
 * exported on demand, because there is no folder next to the recording to write
 * them into - the browser sandbox is the only place we can reach.
 *
 * OPFS is evictable. `requestPersistentStorage` asks the browser not to reclaim
 * it, but on iOS that request is often refused, so the UI tells the user their
 * data is not guaranteed durable and offers sidecar export.
 */

const AUDIO_DIRECTORY = 'recordings'

type AudioLocation = 'opfs' | 'indexeddb'

interface StoredAudioMeta extends AudioSource {
  fileName: string
  /** Where the bytes actually landed. Older rows predate the fallback. */
  location?: AudioLocation
}

export class OpfsStorageAdapter implements StorageAdapter {
  readonly mode: StorageMode = 'browser-storage'
  readonly label = 'Browser storage'
  readonly supportsAutomaticSidecars = false

  private directory: FileSystemDirectoryHandle | null = null

  static isSupported(scope: typeof globalThis = globalThis): boolean {
    return typeof scope.navigator?.storage?.getDirectory === 'function'
  }

  private async audioDirectory(): Promise<FileSystemDirectoryHandle> {
    if (this.directory) return this.directory
    try {
      const root = await navigator.storage.getDirectory()
      this.directory = await root.getDirectoryHandle(AUDIO_DIRECTORY, { create: true })
      return this.directory
    } catch (error) {
      throw new IoError('This browser could not open private storage for recordings.', {
        cause: error,
      })
    }
  }

  async list(): Promise<AudioSource[]> {
    const stored = await getAll<unknown>(STORES.audio)
    return stored
      // The store holds metadata rows AND, for the IndexedDB fallback, the raw
      // audio blobs under `blob:` keys. Only the metadata rows are recordings.
      .filter(isAudioMeta)
      .map(({ fileName: _fileName, location: _location, ...source }) => source)
      .sort((a, b) => b.lastModified - a.lastModified)
  }

  async open(key: string): Promise<Blob> {
    const meta = await get<StoredAudioMeta>(STORES.audio, key)
    if (!meta) throw new IoError('That recording is no longer in browser storage.')

    if (meta.location !== 'indexeddb') {
      try {
        const directory = await this.audioDirectory()
        const handle = await directory.getFileHandle(meta.fileName)
        return await handle.getFile()
      } catch (error) {
        // Rows written before the location field was recorded could be in
        // either place, so try the blob store before giving up.
        if (meta.location === 'opfs') throw missingAudio(error)
      }
    }

    const blob = await get<Blob>(STORES.audio, `blob:${meta.fileName}`)
    if (!blob) throw missingAudio(null)
    return blob
  }

  async add(files: File[]): Promise<AudioSource[]> {
    const added: AudioSource[] = []

    for (const file of files) {
      const key = crypto.randomUUID()
      const fileName = `${key}-${sanitize(file.name)}`
      const stored = await this.writeAudio(fileName, file)

      const source: AudioSource = {
        key,
        name: file.name,
        sizeBytes: file.size,
        lastModified: file.lastModified,
        mimeType: file.type || guessMimeType(file.name),
      }
      await put(
        STORES.audio,
        { ...source, fileName, location: stored } satisfies StoredAudioMeta,
        key,
      )
      added.push(source)
    }

    return added
  }

  /**
   * Writes the audio, preferring OPFS streaming and falling back to an
   * IndexedDB blob.
   *
   * The fallback exists for a specific, common case: Safari shipped OPFS in
   * 16.4 but did NOT add `createWritable` until 17. Without this, an iPhone on
   * iOS 16 would pass the "is OPFS supported" check, then fail at the moment
   * the user tried to import - which is the worst possible time to discover it.
   */
  private async writeAudio(fileName: string, file: File): Promise<AudioLocation> {
    try {
      const directory = await this.audioDirectory()
      const handle = await directory.getFileHandle(fileName, { create: true })
      if (typeof handle.createWritable === 'function') {
        const writable = await handle.createWritable()
        // Streams the File straight through; a multi-hour recording is never
        // materialised in memory.
        await file.stream().pipeTo(writable)
        return 'opfs'
      }
    } catch (error) {
      // Fall through to the blob store rather than failing the import. If that
      // also fails, the error below is the one the user sees.
      console.warn('Debrief: OPFS write unavailable, using IndexedDB instead.', error)
    }

    try {
      await put(STORES.audio, file, `blob:${fileName}`)
      return 'indexeddb'
    } catch (error) {
      throw new IoError(
        `Could not save "${file.name}" on this device. It may be too large for the space this browser allows.`,
        { cause: error },
      )
    }
  }

  async remove(key: string): Promise<void> {
    const meta = await get<StoredAudioMeta>(STORES.audio, key)
    if (!meta) return
    try {
      const directory = await this.audioDirectory()
      await directory.removeEntry(meta.fileName)
    } catch {
      // Already gone is the desired end state, so this is not an error.
    }
    await remove(STORES.audio, `blob:${meta.fileName}`).catch(() => undefined)
    await remove(STORES.audio, key)
  }

  /**
   * There is no folder beside the recording, so sidecars are kept in IndexedDB
   * under the same names Android would use. The UI offers them as downloads.
   */
  async writeSidecar(recordingName: string, contents: string): Promise<void> {
    await put(STORES.settings, contents, `sidecar:${sidecarName(recordingName)}`)
    await put(STORES.settings, contents, `sidecar:${backupSidecarName(recordingName)}`)
  }

  async readSidecar(recordingName: string): Promise<string | null> {
    return (
      (await get<string>(STORES.settings, `sidecar:${sidecarName(recordingName)}`)) ??
      (await get<string>(STORES.settings, `sidecar:${backupSidecarName(recordingName)}`)) ??
      null
    )
  }
}

function sanitize(name: string): string {
  return name.replace(/[^\w.\-]+/g, '_').slice(0, 120)
}

function isAudioMeta(value: unknown): value is StoredAudioMeta {
  return (
    typeof value === 'object' &&
    value !== null &&
    !(value instanceof Blob) &&
    typeof (value as StoredAudioMeta).key === 'string' &&
    typeof (value as StoredAudioMeta).fileName === 'string'
  )
}

function missingAudio(cause: unknown): IoError {
  return new IoError(
    'The audio for this recording is missing. The browser may have reclaimed storage; import the file again.',
    { cause },
  )
}

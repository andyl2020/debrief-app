import { IoError, SecurityError } from '../core/errors'
import { backupSidecarName, sidecarName } from '../core/sidecar'
import {
  guessMimeType,
  isAudioFileName,
  type AudioSource,
  type StorageAdapter,
  type StorageMode,
} from './adapter'
import { STORES, get, put } from './db'

/**
 * Full parity with Android, where the browser allows it.
 *
 * This is the direct analogue of the Storage Access Framework folder the
 * Android app links: the user picks a real directory once, the app rescans it
 * for audio, and writes the two JSON sidecars beside each recording exactly as
 * `SidecarStore.kt` does. A folder written here opens on Android and vice versa.
 *
 * Chromium desktop only. `showDirectoryPicker` does not exist in Safari at all,
 * which is the entire reason `OpfsStorageAdapter` exists.
 */

const HANDLE_KEY = 'recordings-directory'

type PermissionMode = 'read' | 'readwrite'

/**
 * The DOM lib does not yet declare the permission methods or the async
 * `entries()` iterator, both of which are shipped in Chromium and required
 * here: permissions to survive a restart, `entries()` to scan the folder.
 */
type DirectoryHandleWithPermissions = FileSystemDirectoryHandle & {
  queryPermission?: (descriptor: { mode: PermissionMode }) => Promise<PermissionState>
  requestPermission?: (descriptor: { mode: PermissionMode }) => Promise<PermissionState>
}

export class FileSystemAccessAdapter implements StorageAdapter {
  readonly mode: StorageMode = 'linked-folder'
  readonly supportsAutomaticSidecars = true

  private constructor(private readonly directory: DirectoryHandleWithPermissions) {}

  get label(): string {
    return `Linked folder: ${this.directory.name}`
  }

  static isSupported(scope: typeof globalThis = globalThis): boolean {
    return typeof (scope as { showDirectoryPicker?: unknown }).showDirectoryPicker === 'function'
  }

  /**
   * Wraps a directory handle obtained elsewhere - a drag-and-drop, a file
   * picker, or a test double - without going through the picker dialog.
   */
  static adopt(handle: FileSystemDirectoryHandle): FileSystemAccessAdapter {
    return new FileSystemAccessAdapter(handle as DirectoryHandleWithPermissions)
  }

  /** Prompts the user to link a folder, and remembers it for next launch. */
  static async pick(): Promise<FileSystemAccessAdapter> {
    const picker = (globalThis as unknown as {
      showDirectoryPicker: (options?: { mode?: PermissionMode }) => Promise<FileSystemDirectoryHandle>
    }).showDirectoryPicker
    let handle: FileSystemDirectoryHandle
    try {
      handle = await picker({ mode: 'readwrite' })
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        throw new SecurityError('No folder was linked.')
      }
      throw error
    }
    // Directory handles are structured-cloneable, so IndexedDB can hold one
    // across sessions. The permission grant is NOT persisted and must be
    // re-confirmed - see `restore`.
    await put(STORES.handles, handle, HANDLE_KEY)
    return new FileSystemAccessAdapter(handle as DirectoryHandleWithPermissions)
  }

  /**
   * Reconnects to the previously linked folder. Returns null when there is
   * nothing saved or the browser will no longer grant access without a fresh
   * user gesture - the caller then shows the "Re-link folder" prompt rather
   * than failing silently.
   */
  static async restore(): Promise<FileSystemAccessAdapter | null> {
    const handle = await get<DirectoryHandleWithPermissions>(STORES.handles, HANDLE_KEY)
    if (!handle) return null
    if (typeof handle.queryPermission !== 'function') {
      return new FileSystemAccessAdapter(handle)
    }
    const state = await handle.queryPermission({ mode: 'readwrite' })
    if (state === 'granted') return new FileSystemAccessAdapter(handle)
    return null
  }

  /** Re-asks for permission. Must be called from a user gesture. */
  static async reauthorize(): Promise<FileSystemAccessAdapter | null> {
    const handle = await get<DirectoryHandleWithPermissions>(STORES.handles, HANDLE_KEY)
    if (!handle || typeof handle.requestPermission !== 'function') return null
    const state = await handle.requestPermission({ mode: 'readwrite' })
    return state === 'granted' ? new FileSystemAccessAdapter(handle) : null
  }

  async list(): Promise<AudioSource[]> {
    const sources: AudioSource[] = []
    try {
      for await (const [name, handle] of this.directory.entries()) {
        if (handle.kind !== 'file' || !isAudioFileName(name)) continue
        const file = await (handle as FileSystemFileHandle).getFile()
        sources.push({
          key: name,
          name,
          sizeBytes: file.size,
          lastModified: file.lastModified,
          mimeType: file.type || guessMimeType(name),
        })
      }
    } catch (error) {
      throw permissionAware(error, 'Could not read the linked folder.')
    }
    return sources.sort((a, b) => b.lastModified - a.lastModified)
  }

  async open(key: string): Promise<Blob> {
    try {
      const handle = await this.directory.getFileHandle(key)
      return await handle.getFile()
    } catch (error) {
      throw permissionAware(error, 'Could not open that recording from the linked folder.')
    }
  }

  /** Copies chosen files into the linked folder, so it stays the source of truth. */
  async add(files: File[]): Promise<AudioSource[]> {
    const added: AudioSource[] = []
    for (const file of files) {
      try {
        const handle = await this.directory.getFileHandle(file.name, { create: true })
        const writable = await handle.createWritable()
        await file.stream().pipeTo(writable)
      } catch (error) {
        throw permissionAware(error, `Could not copy "${file.name}" into the linked folder.`)
      }
      added.push({
        key: file.name,
        name: file.name,
        sizeBytes: file.size,
        lastModified: file.lastModified,
        mimeType: file.type || guessMimeType(file.name),
      })
    }
    return added
  }

  async remove(key: string): Promise<void> {
    try {
      await this.directory.removeEntry(key)
    } catch (error) {
      if (error instanceof DOMException && error.name === 'NotFoundError') return
      throw permissionAware(error, 'Could not remove that recording from the linked folder.')
    }
  }

  /**
   * Writes the primary sidecar, then the backup. Mirrors `SidecarStore.kt`: two
   * copies, so a failure partway through still leaves one readable file.
   */
  async writeSidecar(recordingName: string, contents: string): Promise<void> {
    await this.writeFile(sidecarName(recordingName), contents)
    await this.writeFile(backupSidecarName(recordingName), contents)
  }

  async readSidecar(recordingName: string): Promise<string | null> {
    return (
      (await this.readFile(sidecarName(recordingName))) ??
      (await this.readFile(backupSidecarName(recordingName)))
    )
  }

  private async writeFile(name: string, contents: string): Promise<void> {
    try {
      const handle = await this.directory.getFileHandle(name, { create: true })
      const writable = await handle.createWritable()
      await writable.write(contents)
      await writable.close()
    } catch (error) {
      throw permissionAware(error, `Could not write "${name}" to the linked folder.`)
    }
  }

  private async readFile(name: string): Promise<string | null> {
    try {
      const handle = await this.directory.getFileHandle(name)
      return await (await handle.getFile()).text()
    } catch (error) {
      if (error instanceof DOMException && error.name === 'NotFoundError') return null
      throw permissionAware(error, `Could not read "${name}" from the linked folder.`)
    }
  }
}

/**
 * Keeps a revoked folder permission distinguishable from a disk problem, so the
 * user is told to re-link rather than to free up space.
 */
function permissionAware(error: unknown, fallback: string): Error {
  if (
    error instanceof DOMException &&
    (error.name === 'NotAllowedError' || error.name === 'SecurityError')
  ) {
    return new SecurityError(fallback)
  }
  return new IoError(fallback, { cause: error })
}

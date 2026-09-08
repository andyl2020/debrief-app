import type { StorageAdapter, StorageMode } from './adapter'
import { STORES, get, put } from './db'
import { FileSystemAccessAdapter } from './fsa-adapter'
import { OpfsStorageAdapter } from './opfs-adapter'

export * from './adapter'
export { FileSystemAccessAdapter } from './fsa-adapter'
export { OpfsStorageAdapter } from './opfs-adapter'

const MODE_KEY = 'storage-mode'

/** Remembers which mode the user actually chose, so they are only asked once. */
export async function saveStorageMode(mode: StorageMode): Promise<void> {
  await put(STORES.settings, mode, MODE_KEY)
}

export async function loadStorageMode(): Promise<StorageMode | null> {
  return (await get<StorageMode>(STORES.settings, MODE_KEY)) ?? null
}

export interface ResolvedStorage {
  adapter: StorageAdapter | null
  /** True when a folder was linked before but needs the user to re-grant access. */
  needsRelink: boolean
  /** True when the browser can do folder linking but the user has not chosen yet. */
  needsChoice: boolean
}

/**
 * Works out which storage to open, WITHOUT quietly making the choice for the
 * user.
 *
 * The subtlety that a first version of this got wrong: when a browser supports
 * folder linking but no folder has been linked yet, falling through to browser
 * storage means the user is never offered the mode with real Android parity -
 * they just silently get the lesser one. So that case now returns
 * `needsChoice`, and the setup screen asks.
 *
 * Browser storage is only chosen automatically when it is the ONLY option,
 * which is the situation on iOS.
 */
export async function resolveStorageAdapter(): Promise<ResolvedStorage> {
  const canLinkFolder = FileSystemAccessAdapter.isSupported()
  const chosen = await loadStorageMode()

  if (canLinkFolder) {
    const restored = await FileSystemAccessAdapter.restore()
    if (restored) return { adapter: restored, needsRelink: false, needsChoice: false }

    // A saved handle that no longer has permission means "ask the user to
    // re-grant", not "silently drop them into a different storage mode".
    if (await hasSavedHandle()) {
      return { adapter: null, needsRelink: true, needsChoice: false }
    }

    // Folder linking is available but unused. Only skip the question if the
    // user has already said they want browser storage.
    if (chosen !== 'browser-storage') {
      return { adapter: null, needsRelink: false, needsChoice: true }
    }
  }

  if (OpfsStorageAdapter.isSupported()) {
    return { adapter: new OpfsStorageAdapter(), needsRelink: false, needsChoice: false }
  }

  return { adapter: null, needsRelink: false, needsChoice: false }
}

async function hasSavedHandle(): Promise<boolean> {
  return (await get(STORES.handles, 'recordings-directory')) !== undefined
}

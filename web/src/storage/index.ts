import type { StorageAdapter } from './adapter'
import { STORES, get } from './db'
import { FileSystemAccessAdapter } from './fsa-adapter'
import { OpfsStorageAdapter } from './opfs-adapter'

export * from './adapter'
export { FileSystemAccessAdapter } from './fsa-adapter'
export { OpfsStorageAdapter } from './opfs-adapter'

/**
 * Chooses the best storage the browser can actually offer.
 *
 * Preference order is deliberate: a previously linked folder wins, because it
 * is the only mode with real Android parity. Everything else - including all of
 * iOS - falls back to browser storage, which is what makes the app usable there
 * at all.
 */
export async function resolveStorageAdapter(): Promise<{
  adapter: StorageAdapter | null
  /** True when a folder was linked before but needs the user to re-grant access. */
  needsRelink: boolean
}> {
  if (FileSystemAccessAdapter.isSupported()) {
    const restored = await FileSystemAccessAdapter.restore()
    if (restored) return { adapter: restored, needsRelink: false }
    // A saved handle that no longer has permission means "ask the user", not
    // "silently drop them into a different storage mode".
    const hadHandle = await hasSavedHandle()
    if (hadHandle) return { adapter: null, needsRelink: true }
  }
  if (OpfsStorageAdapter.isSupported()) {
    return { adapter: new OpfsStorageAdapter(), needsRelink: false }
  }
  return { adapter: null, needsRelink: false }
}

async function hasSavedHandle(): Promise<boolean> {
  return (await get(STORES.handles, 'recordings-directory')) !== undefined
}

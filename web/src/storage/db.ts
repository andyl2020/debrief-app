import { IoError } from '../core/errors'

/**
 * A thin promise wrapper over IndexedDB.
 *
 * This replaces Room, and it is worth being explicit about what is lost: the
 * Android database is encrypted at rest with SQLCipher. IndexedDB is not. It is
 * protected by the browser origin and the device, and nothing more. The app
 * discloses that in Settings rather than implying parity it does not have.
 */

const DB_NAME = 'debrief'
const DB_VERSION = 1

export const STORES = {
  recordings: 'recordings',
  segments: 'segments',
  words: 'words',
  comments: 'comments',
  redactions: 'redactions',
  aliases: 'aliases',
  sets: 'sets',
  quality: 'quality',
  settings: 'settings',
  secrets: 'secrets',
  handles: 'handles',
  audio: 'audio',
} as const

export type StoreName = (typeof STORES)[keyof typeof STORES]

/** Stores keyed by recording, with a `recordingId` index for bulk reads. */
const RECORDING_SCOPED: StoreName[] = [
  STORES.segments,
  STORES.words,
  STORES.comments,
  STORES.redactions,
  STORES.aliases,
  STORES.sets,
]

let connection: Promise<IDBDatabase> | null = null

export function openDatabase(): Promise<IDBDatabase> {
  if (connection) return connection
  connection = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)

    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(STORES.recordings)) {
        db.createObjectStore(STORES.recordings, { keyPath: 'id' })
      }
      for (const store of RECORDING_SCOPED) {
        if (!db.objectStoreNames.contains(store)) {
          const created = db.createObjectStore(store, { keyPath: 'key', autoIncrement: true })
          created.createIndex('recordingId', 'recordingId', { unique: false })
        }
      }
      if (!db.objectStoreNames.contains(STORES.quality)) {
        db.createObjectStore(STORES.quality, { keyPath: 'recordingId' })
      }
      for (const store of [STORES.settings, STORES.secrets, STORES.handles, STORES.audio]) {
        if (!db.objectStoreNames.contains(store)) db.createObjectStore(store)
      }
    }

    request.onsuccess = () => resolve(request.result)
    request.onerror = () =>
      reject(new IoError(request.error?.message ?? 'Could not open local storage.'))
    request.onblocked = () =>
      reject(new IoError('Another Debrief tab is upgrading local storage. Close it and reload.'))
  })
  return connection
}

/** Test seam - drops the memoised connection so a fresh fake-indexeddb can be used. */
export function resetDatabaseConnection(): void {
  connection = null
}

async function run<T>(
  store: StoreName,
  mode: IDBTransactionMode,
  work: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  const db = await openDatabase()
  return new Promise<T>((resolve, reject) => {
    const transaction = db.transaction(store, mode)
    const request = work(transaction.objectStore(store))
    request.onsuccess = () => resolve(request.result)
    request.onerror = () =>
      reject(new IoError(request.error?.message ?? `Could not access ${store}.`))
    transaction.onabort = () =>
      reject(new IoError(transaction.error?.message ?? `Could not access ${store}.`))
  })
}

export function get<T>(store: StoreName, key: IDBValidKey): Promise<T | undefined> {
  return run<T | undefined>(store, 'readonly', (objectStore) => objectStore.get(key))
}

export function getAll<T>(store: StoreName): Promise<T[]> {
  return run<T[]>(store, 'readonly', (objectStore) => objectStore.getAll())
}

export function put(store: StoreName, value: unknown, key?: IDBValidKey): Promise<IDBValidKey> {
  return run<IDBValidKey>(store, 'readwrite', (objectStore) =>
    key === undefined ? objectStore.put(value) : objectStore.put(value, key),
  )
}

export function remove(store: StoreName, key: IDBValidKey): Promise<undefined> {
  return run<undefined>(store, 'readwrite', (objectStore) => objectStore.delete(key))
}

export function getAllByRecording<T>(store: StoreName, recordingId: string): Promise<T[]> {
  return run<T[]>(store, 'readonly', (objectStore) =>
    objectStore.index('recordingId').getAll(IDBKeyRange.only(recordingId)),
  )
}

/** Replaces every row for one recording in one transaction. */
export async function replaceForRecording(
  store: StoreName,
  recordingId: string,
  rows: unknown[],
): Promise<void> {
  const db = await openDatabase()
  await new Promise<void>((resolve, reject) => {
    const transaction = db.transaction(store, 'readwrite')
    const objectStore = transaction.objectStore(store)
    const cursorRequest = objectStore.index('recordingId').openKeyCursor(IDBKeyRange.only(recordingId))

    cursorRequest.onsuccess = () => {
      const cursor = cursorRequest.result
      if (cursor) {
        objectStore.delete(cursor.primaryKey)
        cursor.continue()
        return
      }
      for (const row of rows) objectStore.put(row)
    }

    transaction.oncomplete = () => resolve()
    transaction.onerror = () =>
      reject(new IoError(transaction.error?.message ?? `Could not update ${store}.`))
    transaction.onabort = () =>
      reject(new IoError(transaction.error?.message ?? `Could not update ${store}.`))
  })
}

/** Removes every trace of a recording. Mirrors Room's ON DELETE CASCADE. */
export async function deleteRecordingCascade(recordingId: string): Promise<void> {
  for (const store of RECORDING_SCOPED) {
    await replaceForRecording(store, recordingId, [])
  }
  await remove(STORES.quality, recordingId)
  await remove(STORES.recordings, recordingId)
  await remove(STORES.audio, recordingId)
}

import FDBFactory from 'fake-indexeddb/lib/FDBFactory'
import { resetDatabaseConnection } from '../src/storage/db'

/**
 * Gives a test its own empty IndexedDB.
 *
 * A shared database between test files would let one test's recordings leak
 * into another's assertions, so each suite installs a brand new factory and
 * drops the memoised connection.
 */
export function freshDatabase(): void {
  Object.defineProperty(globalThis, 'indexedDB', { value: new FDBFactory(), configurable: true })
  resetDatabaseConnection()
}

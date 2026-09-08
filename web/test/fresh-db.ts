import FDBFactory from 'fake-indexeddb/lib/FDBFactory'
import { resetDatabaseConnection } from '../src/storage/db'

/**
 * Gives a test its own empty IndexedDB.
 *
 * A shared database between test files would let one test's recordings leak
 * into another's assertions, so each suite installs a brand new factory and
 * drops the memoised connection.
 */
let generation = 0

export function freshDatabase(): void {
  Object.defineProperty(globalThis, 'indexedDB', { value: new FDBFactory(), configurable: true })
  // A unique name per test as well as a fresh factory. A previous test's
  // unmounted app can still have IndexedDB work in flight; without rotating the
  // name it re-opens the new database and writes its rows into this test's
  // fixture, which showed up as an intermittent failure.
  generation += 1
  resetDatabaseConnection(`debrief-test-${generation}`)
}

/**
 * Switches which database subsequent calls use, keeping the same factory.
 *
 * Lets one test model two devices — upload from "desktop", read on "phone" —
 * which is the whole point of the cloud library and cannot be expressed with a
 * single shared database.
 */
export function useDatabase(name: string): void {
  resetDatabaseConnection(name)
}

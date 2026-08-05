import '@testing-library/jest-dom/vitest'
import 'fake-indexeddb/auto'
import { Blob as NodeBlob, File as NodeFile } from 'node:buffer'
import { webcrypto } from 'node:crypto'

/**
 * jsdom ships Blob and File without `stream()`, `text()` or `arrayBuffer()`.
 * The storage adapters rely on `stream()` specifically - it is how a multi-hour
 * recording is written without being pulled into memory - so testing them
 * against jsdom's stubs would test the wrong thing. Node's implementations are
 * spec-complete, so use those.
 */
Object.defineProperty(globalThis, 'Blob', { value: NodeBlob, configurable: true, writable: true })
Object.defineProperty(globalThis, 'File', { value: NodeFile, configurable: true, writable: true })

// jsdom exposes crypto.getRandomValues but not SubtleCrypto, which the key
// vault needs.
if (!globalThis.crypto?.subtle) {
  Object.defineProperty(globalThis, 'crypto', { value: webcrypto, configurable: true })
}

// jsdom does not implement crypto.randomUUID in every version we support.
if (typeof globalThis.crypto?.randomUUID !== 'function') {
  Object.defineProperty(globalThis.crypto, 'randomUUID', {
    value: () =>
      '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, (character) =>
        (
          Number(character) ^
          (globalThis.crypto.getRandomValues(new Uint8Array(1))[0]! & (15 >> (Number(character) / 4)))
        ).toString(16),
      ),
    configurable: true,
  })
}

import { beforeEach, describe, expect, it } from 'vitest'
import { webcrypto } from 'node:crypto'
import { KeyVault, WrongPassphraseError, destroyVault, vaultExists } from '../src/storage/keys'
import { ValidationError } from '../src/core/errors'
import { resetDatabaseConnection } from '../src/storage/db'

/**
 * Web-specific. Android holds API keys in a non-exportable hardware Keystore
 * key; the browser has nothing equivalent, so these tests pin the properties
 * the passphrase vault DOES guarantee.
 */
describe('KeyVault', () => {
  beforeEach(async () => {
    // jsdom exposes crypto.getRandomValues but not always subtle.
    if (!globalThis.crypto?.subtle) {
      Object.defineProperty(globalThis, 'crypto', { value: webcrypto, configurable: true })
    }
    const { indexedDB, IDBKeyRange } = await import('fake-indexeddb')
    Object.defineProperty(globalThis, 'indexedDB', { value: indexedDB, configurable: true })
    Object.defineProperty(globalThis, 'IDBKeyRange', { value: IDBKeyRange, configurable: true })
    resetDatabaseConnection()
    await destroyVault()
  })

  it('stores and returns an API key once unlocked', async () => {
    const vault = await KeyVault.create('correct horse battery')
    await vault.put('assemblyai', 'aai-secret-key')

    expect(await vault.get('assemblyai')).toBe('aai-secret-key')
    expect(vault.has('assemblyai')).toBe(true)
    expect(vault.providers()).toEqual(['assemblyai'])
  })

  it('never persists the key in readable form', async () => {
    const vault = await KeyVault.create('correct horse battery')
    await vault.put('deepgram', 'dg-secret-key')

    const { STORES, get } = await import('../src/storage/db')
    const raw = JSON.stringify(await get(STORES.secrets, 'vault'))

    expect(raw).not.toContain('dg-secret-key')
  })

  it('rejects the wrong passphrase instead of returning garbage', async () => {
    const vault = await KeyVault.create('correct horse battery')
    await vault.put('assemblyai', 'aai-secret-key')

    await expect(KeyVault.unlock('wrong passphrase')).rejects.toThrow(WrongPassphraseError)
  })

  it('reopens with the right passphrase in a later session', async () => {
    const created = await KeyVault.create('correct horse battery')
    await created.put('assemblyai', 'aai-secret-key')

    const reopened = await KeyVault.unlock('correct horse battery')

    expect(await reopened.get('assemblyai')).toBe('aai-secret-key')
  })

  it('refuses a passphrase too short to be worth encrypting with', async () => {
    await expect(KeyVault.create('short')).rejects.toThrow(ValidationError)
  })

  it('refuses to save a blank API key', async () => {
    const vault = await KeyVault.create('correct horse battery')

    await expect(vault.put('assemblyai', '   ')).rejects.toThrow(ValidationError)
  })

  it('forgets a removed key and the whole vault on request', async () => {
    const vault = await KeyVault.create('correct horse battery')
    await vault.put('assemblyai', 'aai-secret-key')
    await vault.remove('assemblyai')

    expect(vault.has('assemblyai')).toBe(false)
    expect(await vault.get('assemblyai')).toBeNull()

    expect(await vaultExists()).toBe(true)
    await destroyVault()
    expect(await vaultExists()).toBe(false)
  })
})

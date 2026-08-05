import { IoError, ValidationError } from '../core/errors'
import { STORES, get, put, remove } from './db'

/**
 * Replaces `SecureSecretStore` in
 * `app/src/main/java/com/andyluu/debrief/data/SettingsStore.kt:102-141`.
 *
 * Android wraps API keys in an AES-GCM key generated inside the hardware-backed
 * Android Keystore, which is non-exportable. A browser has no equivalent: there
 * is no way to hold a key that page script cannot eventually reach.
 *
 * The closest honest analogue is what this does - derive a key from a passphrase
 * the user types, with PBKDF2, and keep only the ciphertext at rest. That means:
 *
 *  - a copy of IndexedDB alone does not yield the API keys
 *  - the plaintext key exists in memory only while the vault is unlocked
 *  - it is still weaker than Android, and Settings says so plainly
 *
 * The salt and IV are stored beside the ciphertext; neither is secret.
 */

const PBKDF2_ITERATIONS = 310_000
const SALT_BYTES = 16
const IV_BYTES = 12
const VAULT_KEY = 'vault'
const VERIFIER_PLAINTEXT = 'debrief-vault-v1'

interface StoredVault {
  version: 1
  salt: number[]
  /** Encrypts a known constant, so a wrong passphrase is detected on unlock. */
  verifier: { iv: number[]; ciphertext: number[] }
  entries: Record<string, { iv: number[]; ciphertext: number[] }>
}

export class WrongPassphraseError extends Error {
  override readonly name = 'WrongPassphraseError'
  constructor() {
    super('That passphrase does not match the one this vault was created with.')
  }
}

async function deriveKey(passphrase: string, salt: Uint8Array): Promise<CryptoKey> {
  const material = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(passphrase),
    'PBKDF2',
    false,
    ['deriveKey'],
  )
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt: salt as BufferSource, iterations: PBKDF2_ITERATIONS, hash: 'SHA-256' },
    material,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  )
}

async function encrypt(key: CryptoKey, plaintext: string) {
  const iv = crypto.getRandomValues(new Uint8Array(IV_BYTES))
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv },
    key,
    new TextEncoder().encode(plaintext),
  )
  return { iv: [...iv], ciphertext: [...new Uint8Array(ciphertext)] }
}

async function decrypt(
  key: CryptoKey,
  payload: { iv: number[]; ciphertext: number[] },
): Promise<string> {
  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: new Uint8Array(payload.iv) },
    key,
    new Uint8Array(payload.ciphertext),
  )
  return new TextDecoder().decode(plaintext)
}

async function loadVault(): Promise<StoredVault | null> {
  return (await get<StoredVault>(STORES.secrets, VAULT_KEY)) ?? null
}

export async function vaultExists(): Promise<boolean> {
  return (await loadVault()) !== null
}

/**
 * An unlocked vault. The derived key is held in this object and nowhere else,
 * so dropping the reference (or reloading the page) re-locks it.
 */
export class KeyVault {
  private constructor(
    private readonly key: CryptoKey,
    private vault: StoredVault,
  ) {}

  static async create(passphrase: string): Promise<KeyVault> {
    if (passphrase.length < 8) {
      throw new ValidationError('Use a passphrase of at least 8 characters.')
    }
    const salt = crypto.getRandomValues(new Uint8Array(SALT_BYTES))
    const key = await deriveKey(passphrase, salt)
    const vault: StoredVault = {
      version: 1,
      salt: [...salt],
      verifier: await encrypt(key, VERIFIER_PLAINTEXT),
      entries: {},
    }
    await put(STORES.secrets, vault, VAULT_KEY)
    return new KeyVault(key, vault)
  }

  static async unlock(passphrase: string): Promise<KeyVault> {
    const vault = await loadVault()
    if (!vault) throw new IoError('There is no saved key vault on this device yet.')
    const key = await deriveKey(passphrase, new Uint8Array(vault.salt))
    let verified: string
    try {
      verified = await decrypt(key, vault.verifier)
    } catch {
      throw new WrongPassphraseError()
    }
    if (verified !== VERIFIER_PLAINTEXT) throw new WrongPassphraseError()
    return new KeyVault(key, vault)
  }

  /** Names of the providers that have a key stored, without revealing any of them. */
  providers(): string[] {
    return Object.keys(this.vault.entries)
  }

  has(provider: string): boolean {
    return provider in this.vault.entries
  }

  async put(provider: string, apiKey: string): Promise<void> {
    const trimmed = apiKey.trim()
    if (trimmed.length === 0) throw new ValidationError('Enter the API key before saving it.')
    this.vault = {
      ...this.vault,
      entries: { ...this.vault.entries, [provider]: await encrypt(this.key, trimmed) },
    }
    await put(STORES.secrets, this.vault, VAULT_KEY)
  }

  async get(provider: string): Promise<string | null> {
    const entry = this.vault.entries[provider]
    if (!entry) return null
    try {
      return await decrypt(this.key, entry)
    } catch {
      // A corrupted entry should not take the whole app down; the user is asked
      // to re-enter that one key.
      return null
    }
  }

  async remove(provider: string): Promise<void> {
    const entries = { ...this.vault.entries }
    delete entries[provider]
    this.vault = { ...this.vault, entries }
    await put(STORES.secrets, this.vault, VAULT_KEY)
  }
}

/** Deletes the whole vault, for "forget my keys on this device". */
export async function destroyVault(): Promise<void> {
  await remove(STORES.secrets, VAULT_KEY)
}

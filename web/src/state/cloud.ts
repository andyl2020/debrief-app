import { ValidationError } from '../core/errors'
import {
  alignRange,
  counterForOffset,
  decryptRange,
  decryptWhole,
  encryptWhole,
  fromBase64,
  generateLibraryKey,
  randomNonce,
  toBase64,
  unwrapLibraryKey,
  wrapLibraryKey,
} from '../core/cloud-crypto'
import { buildSidecar, parseSidecar, serializeSidecar, sidecarToEntities } from '../core/sidecar'
import type { Recording } from '../core/models'
import { STORES, get, getAll, put, remove } from '../storage/db'
import type { Repository } from './repository'
import { CloudClient, type CloudItem, type ObjectKind } from './cloud-client'

/**
 * Sync between the local library and the encrypted cloud library.
 *
 * Deliberately a layer over the repository rather than a third StorageAdapter.
 * A recording can be local, cloud, or both at once, and an adapter would force
 * an either/or that does not match how this is actually used: you upload from
 * the desktop where the folder is linked, and read on a phone that has no local
 * copy at all.
 *
 * Everything crosses the network already encrypted. The sidecar reuses
 * `buildSidecar`/`parseSidecar` unchanged so a cloud payload stays the same
 * schema v4 that Android reads.
 */

const CLOUD_CONFIG_KEY = 'cloud-config'
const CLOUD_STATE_PREFIX = 'cloud-state:'
/** R2 requires every multipart part except the last to be at least 5 MiB. */
const PART_BYTES = 8 * 1024 * 1024

export interface CloudConfig {
  baseUrl: string
  token: string
  deviceLabel: string
}

/** Per-recording sync bookkeeping, kept out of `Recording` so it can be absent. */
export interface CloudState {
  recordingId: string
  version: number
  updatedAt: number
  audioNonce: string
  metadataNonce: string
  audioBytes: number
  syncedAt: number
}

export async function loadCloudConfig(): Promise<CloudConfig | null> {
  return (await get<CloudConfig>(STORES.settings, CLOUD_CONFIG_KEY)) ?? null
}

export async function saveCloudConfig(config: CloudConfig): Promise<void> {
  await put(STORES.settings, config, CLOUD_CONFIG_KEY)
}

export async function clearCloudConfig(): Promise<void> {
  await remove(STORES.settings, CLOUD_CONFIG_KEY)
  for (const state of await listCloudStates()) {
    await remove(STORES.settings, `${CLOUD_STATE_PREFIX}${state.recordingId}`)
  }
}

export async function getCloudState(recordingId: string): Promise<CloudState | null> {
  return (await get<CloudState>(STORES.settings, `${CLOUD_STATE_PREFIX}${recordingId}`)) ?? null
}

export async function listCloudStates(): Promise<CloudState[]> {
  const all = await getAll<unknown>(STORES.settings)
  return all.filter(isCloudState)
}

async function saveCloudState(state: CloudState): Promise<void> {
  await put(STORES.settings, state, `${CLOUD_STATE_PREFIX}${state.recordingId}`)
}

/**
 * Establishes or recovers the library data key.
 *
 * First device generates a random key and stores it wrapped; later devices
 * unwrap the same one. Generating a fresh key when the server already has one
 * would silently orphan every existing recording, so that path is never taken
 * here — the server also refuses it.
 */
export async function unlockLibraryKey(
  client: CloudClient,
  passphrase: string,
): Promise<{ key: CryptoKey; created: boolean }> {
  if (passphrase.trim().length < 8) {
    throw new ValidationError('Use a cloud passphrase of at least 8 characters.')
  }
  const existing = await client.getWrappedKey()
  if (existing) {
    return {
      key: await unwrapLibraryKey(existing.wrappedKey, passphrase, existing.salt, existing.iterations),
      created: false,
    }
  }
  const key = await generateLibraryKey()
  await client.putWrappedKey(await wrapLibraryKey(key, passphrase))
  return { key, created: true }
}

export interface PushProgress {
  (stage: string, fraction: number | null): void
}

/**
 * Uploads one recording: its audio and its sidecar, both encrypted.
 *
 * Audio is read and encrypted a part at a time. A six-hour recording is roughly
 * 350 MB and must never be held in memory whole — the same constraint that
 * shaped the local storage layer.
 */
export async function pushRecording(
  repository: Repository,
  client: CloudClient,
  key: CryptoKey,
  recordingId: string,
  onProgress?: PushProgress,
): Promise<CloudState> {
  const bundle = await repository.loadReview(recordingId)
  if (!bundle) throw new ValidationError('That recording is no longer in the library.')

  const audioBlob = await repository.adapter.open(bundle.recording.sourceKey)
  const sidecar = new TextEncoder().encode(
    serializeSidecar(
      buildSidecar({
        recording: bundle.recording,
        segments: bundle.segments,
        words: bundle.words,
        comments: bundle.comments,
        redactions: bundle.redactions,
        aliases: bundle.aliases,
        sets: bundle.sets,
      }),
    ),
  )

  const audioNonce = randomNonce()
  const metadataNonce = randomNonce()
  const encryptedMetadata = await encryptWhole(key, metadataNonce, sidecar)

  onProgress?.('Preparing upload', null)
  await client.beginItem({
    id: recordingId,
    audioBytes: audioBlob.size,
    metadataBytes: encryptedMetadata.length,
    audioNonce: toBase64(audioNonce),
    metadataNonce: toBase64(metadataNonce),
  })

  // Audio, part by part. Each part is encrypted at its own plaintext offset,
  // which is exactly the same arithmetic the player uses to seek.
  const audioParts: Array<{ partNumber: number; etag: string }> = []
  let offset = 0
  let partNumber = 1
  while (offset < audioBlob.size) {
    const end = Math.min(offset + PART_BYTES, audioBlob.size)
    const plain = new Uint8Array(await audioBlob.slice(offset, end).arrayBuffer())
    const cipher = await encryptAtOffset(key, audioNonce, offset, plain)
    audioParts.push(await client.uploadPart(recordingId, 'audio', partNumber, cipher))
    onProgress?.('Uploading audio', end / Math.max(1, audioBlob.size))
    offset = end
    partNumber += 1
  }
  await client.completeObject(recordingId, 'audio', audioParts)

  onProgress?.('Uploading transcript', null)
  const metadataPart = await client.uploadPart(recordingId, 'metadata', 1, encryptedMetadata)
  await client.completeObject(recordingId, 'metadata', [metadataPart])

  const state: CloudState = {
    recordingId,
    version: 0,
    updatedAt: Date.now(),
    audioNonce: toBase64(audioNonce),
    metadataNonce: toBase64(metadataNonce),
    audioBytes: audioBlob.size,
    syncedAt: Date.now(),
  }
  await saveCloudState(state)
  return state
}

/**
 * Brings a cloud item into the local library.
 *
 * Metadata only — audio stays in the cloud and is streamed on demand, because
 * the whole point on a phone is not to need 350 MB of local space.
 */
export async function pullRecording(
  repository: Repository,
  client: CloudClient,
  key: CryptoKey,
  item: CloudItem,
): Promise<Recording | null> {
  if (!item.metadataReady) return null

  const response = await client.fetchObject(item.id, 'metadata')
  const cipher = new Uint8Array(await response.arrayBuffer())
  const plain = await decryptWhole(key, fromBase64(item.metadataNonce), cipher)
  const document = parseSidecar(new TextDecoder().decode(plain))

  const existing = await repository.getRecording(item.id)
  const recording: Recording = existing ?? {
    id: item.id,
    // Cloud-only recordings have no local audio; the player resolves this
    // through the cloud instead.
    sourceKey: `cloud:${item.id}`,
    displayName: document.recordingName,
    mimeType: 'audio/mp4',
    sizeBytes: document.recordingSizeBytes || item.audioBytes,
    lastModified: item.updatedAt,
    durationMs: document.recordingDurationMs,
    status: document.transcript.length > 0 ? 'READY' : 'NEW',
    errorMessage: null,
    playbackPositionMs: 0,
    discoveredAt: item.updatedAt,
    providerJobId: null,
    provider: null,
  }
  await repository.saveRecording({
    ...recording,
    displayName: document.recordingName || recording.displayName,
    durationMs: document.recordingDurationMs || recording.durationMs,
  })

  const entities = sidecarToEntities(item.id, document)
  await repository.replaceTranscript(item.id, entities.segments, entities.words)
  await repository.setComments(item.id, entities.comments)
  await repository.setRedactions(item.id, entities.redactions)
  await repository.setAliases(item.id, entities.aliases)
  await repository.setSets(item.id, entities.sets)
  await repository.rebuildSearch(item.id)

  await saveCloudState({
    recordingId: item.id,
    version: item.version,
    updatedAt: item.updatedAt,
    audioNonce: item.audioNonce,
    metadataNonce: item.metadataNonce,
    audioBytes: item.audioBytes,
    syncedAt: Date.now(),
  })

  return (await repository.getRecording(item.id)) ?? null
}

/**
 * Pulls anything the cloud has that this device does not, or has an older
 * version of. Last writer wins on `(version, updatedAt)`, which is adequate for
 * one person's devices and is stated plainly rather than pretending to merge.
 */
export async function pullAll(
  repository: Repository,
  client: CloudClient,
  key: CryptoKey,
): Promise<{ pulled: number; skipped: number }> {
  const items = await client.listItems()
  let pulled = 0
  let skipped = 0
  for (const item of items) {
    if (item.status !== 'COMPLETE') {
      skipped += 1
      continue
    }
    const local = await getCloudState(item.id)
    if (local && local.version >= item.version && local.updatedAt >= item.updatedAt) {
      skipped += 1
      continue
    }
    await pullRecording(repository, client, key, item)
    pulled += 1
  }
  return { pulled, skipped }
}

export async function removeFromCloud(client: CloudClient, recordingId: string): Promise<void> {
  await client.deleteItem(recordingId)
  await remove(STORES.settings, `${CLOUD_STATE_PREFIX}${recordingId}`)
}

/** True when this recording's audio lives only in the cloud. */
export function isCloudSourceKey(sourceKey: string): boolean {
  return sourceKey.startsWith('cloud:')
}

/**
 * Downloads and decrypts a whole cloud object.
 *
 * The fallback for playback where a service worker is unavailable. Size-gated
 * by the caller: holding hours of audio in memory is exactly what the streaming
 * path exists to avoid.
 */
export async function fetchDecryptedObject(
  client: CloudClient,
  key: CryptoKey,
  id: string,
  kind: ObjectKind,
  nonceBase64: string,
): Promise<Uint8Array> {
  const response = await client.fetchObject(id, kind)
  const cipher = new Uint8Array(await response.arrayBuffer())
  return decryptWhole(key, fromBase64(nonceBase64), cipher)
}

/** Fetches and decrypts one plaintext byte range. Used by the playback proxy. */
export async function fetchDecryptedRange(
  client: CloudClient,
  key: CryptoKey,
  id: string,
  nonceBase64: string,
  totalBytes: number,
  start: number,
  end: number,
): Promise<Uint8Array> {
  const aligned = alignRange(start, end, totalBytes)
  const response = await client.fetchObject(id, 'audio', {
    start: aligned.fetchStart,
    end: aligned.fetchEnd,
  })
  const cipher = new Uint8Array(await response.arrayBuffer())
  return decryptRange(key, fromBase64(nonceBase64), aligned, cipher)
}

/**
 * Encrypts a chunk that sits at `offset` within the plaintext.
 *
 * Uploading in parts means each part must be encrypted with the counter it will
 * later be decrypted with; encrypting each part as if it started at zero would
 * produce a file that only plays correctly for its first part.
 */
async function encryptAtOffset(
  key: CryptoKey,
  nonce: Uint8Array,
  offset: number,
  plain: Uint8Array,
): Promise<Uint8Array> {
  if (offset % 16 !== 0) {
    throw new ValidationError('Upload parts must start on a 16-byte boundary.')
  }
  const cipher = await crypto.subtle.encrypt(
    { name: 'AES-CTR', counter: counterForOffset(nonce, offset), length: 64 },
    key,
    plain as BufferSource,
  )
  return new Uint8Array(cipher)
}

function isCloudState(value: unknown): value is CloudState {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as CloudState).recordingId === 'string' &&
    typeof (value as CloudState).audioNonce === 'string' &&
    typeof (value as CloudState).metadataNonce === 'string'
  )
}

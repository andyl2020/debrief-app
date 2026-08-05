import { beforeEach, describe, expect, it } from 'vitest'
import { Repository } from '../src/state/repository'
import {
  fetchDecryptedObject,
  fetchDecryptedRange,
  getCloudState,
  isCloudSourceKey,
  pullAll,
  pushRecording,
  unlockLibraryKey,
} from '../src/state/cloud'
import { generateLibraryKey } from '../src/core/cloud-crypto'
import type { AudioSource, StorageAdapter } from '../src/storage/adapter'
import { freshDatabase, useDatabase } from './fresh-db'
import { FakeCloud } from './fake-cloud'
import { comment, conversationSet, recording as makeRecording, redaction, segment, word } from './helpers'

/**
 * End-to-end sync, against an in-memory cloud.
 *
 * The property that matters most is that a recording uploaded in parts comes
 * back byte-identical, including from the middle. Multi-part encryption that
 * gets its counter offsets wrong does not fail — it plays as noise — so it is
 * checked here rather than trusted.
 */

class MemoryAdapter implements StorageAdapter {
  readonly mode = 'browser-storage' as const
  readonly label = 'Memory'
  readonly supportsAutomaticSidecars = false
  sidecars = new Map<string, string>()
  audio = new Map<string, Uint8Array>()

  async list(): Promise<AudioSource[]> {
    return []
  }
  async open(key: string): Promise<Blob> {
    const bytes = this.audio.get(key)
    if (!bytes) throw new Error(`no audio for ${key}`)
    return new Blob([bytes as BlobPart])
  }
  async add(): Promise<AudioSource[]> {
    return []
  }
  async remove(): Promise<void> {}
  async writeSidecar(name: string, contents: string): Promise<void> {
    this.sidecars.set(name, contents)
  }
  async readSidecar(name: string): Promise<string | null> {
    return this.sidecars.get(name) ?? null
  }
}

describe('cloud sync', () => {
  let adapter: MemoryAdapter
  let repository: Repository
  let cloud: FakeCloud

  const RECORDING_ID = 'rec-cloud-1'
  const DESKTOP_DB = 'device-desktop'
  const PHONE_DB = 'device-phone'
  // The shared fixtures hardcode recordingId 'recording'; rebind them so the
  // rows actually belong to the recording under test.
  const forThis = <T extends { recordingId: string }>(row: T): T => ({ ...row, recordingId: RECORDING_ID })
  // Deliberately larger than one 8 MiB upload part, so multipart assembly and
  // per-part counter offsets are genuinely exercised.
  const audioBytes = new Uint8Array(9 * 1024 * 1024).map((_, index) => index % 251)

  beforeEach(async () => {
    freshDatabase()
    useDatabase(DESKTOP_DB)
    adapter = new MemoryAdapter()
    adapter.audio.set('local-audio', audioBytes)
    repository = new Repository(adapter)
    cloud = new FakeCloud()

    await repository.saveRecording(
      makeRecording({
        id: RECORDING_ID,
        sourceKey: 'local-audio',
        displayName: 'Run club.m4a',
        durationMs: 600_000,
        sizeBytes: audioBytes.length,
        status: 'READY',
      }),
    )
    await repository.replaceTranscript(
      RECORDING_ID,
      [
        forThis(segment(1_000, 5_000, 'Speaker A', 'Hey good to meet you.')),
        forThis(segment(6_000, 10_000, 'Speaker B', 'The seawall route was solid.')),
      ],
      [forThis(word('Hey', 1_000, 1_400)), forThis(word('good', 1_400, 1_800))],
    )
    await repository.setComments(RECORDING_ID, [forThis(comment('c1', 7_000, 'Follow up'))])
    await repository.setRedactions(RECORDING_ID, [forThis(redaction(2_000, 2_400))])
    await repository.setAliases(RECORDING_ID, [
      { recordingId: RECORDING_ID, speakerId: 'Speaker A', displayName: 'Andy' },
    ])
    await repository.setSets(RECORDING_ID, [forThis(conversationSet('s1', 1_000, 'Set 1', 10_000))])
  })

  it('creates the library key on the first device and reuses it on the next', async () => {
    const first = await unlockLibraryKey(cloud.asClient(), 'correct horse battery')
    expect(first.created).toBe(true)

    const second = await unlockLibraryKey(cloud.asClient(), 'correct horse battery')
    expect(second.created).toBe(false)
  })

  it('rejects the wrong passphrase on a second device', async () => {
    await unlockLibraryKey(cloud.asClient(), 'correct horse battery')

    await expect(unlockLibraryKey(cloud.asClient(), 'not the passphrase')).rejects.toThrow(
      /does not match/i,
    )
  })

  it('uploads audio in parts and brings it back byte-identical', async () => {
    const key = await generateLibraryKey()

    await pushRecording(repository, cloud.asClient(), key, RECORDING_ID)

    const restored = await fetchDecryptedObject(
      cloud.asClient(),
      key,
      RECORDING_ID,
      'audio',
      (await getCloudState(RECORDING_ID))!.audioNonce,
    )
    expect(restored.length).toBe(audioBytes.length)
    expect(Buffer.from(restored).equals(Buffer.from(audioBytes))).toBe(true)
  })

  it('decrypts a range from the middle, across an upload part boundary', async () => {
    // The nastiest case: a seek that lands after the first 8 MiB part, where a
    // wrong counter offset would silently return noise.
    const key = await generateLibraryKey()
    await pushRecording(repository, cloud.asClient(), key, RECORDING_ID)
    const state = (await getCloudState(RECORDING_ID))!

    for (const [start, end] of [
      [0, 99],
      [8 * 1024 * 1024 - 50, 8 * 1024 * 1024 + 50],
      [8 * 1024 * 1024 + 1, 8 * 1024 * 1024 + 1_000],
      [audioBytes.length - 10, audioBytes.length - 1],
    ] as const) {
      const got = await fetchDecryptedRange(
        cloud.asClient(),
        key,
        RECORDING_ID,
        state.audioNonce,
        state.audioBytes,
        start,
        end,
      )
      expect(Buffer.from(got)).toEqual(Buffer.from(audioBytes.slice(start, end + 1)))
    }
  })

  it('stores nothing readable on the server', async () => {
    const key = await generateLibraryKey()

    await pushRecording(repository, cloud.asClient(), key, RECORDING_ID)

    const storedMetadata = cloud.storedBytes(RECORDING_ID, 'metadata')!
    const asText = new TextDecoder().decode(storedMetadata)
    // The transcript, the filename and the schema marker must all be absent.
    expect(asText).not.toContain('seawall')
    expect(asText).not.toContain('Run club')
    expect(asText).not.toContain('schemaVersion')

    const storedAudio = cloud.storedBytes(RECORDING_ID, 'audio')!
    expect(Buffer.from(storedAudio.slice(0, 1024)).equals(Buffer.from(audioBytes.slice(0, 1024)))).toBe(false)
  })

  it('restores the transcript, comments, redactions, aliases and sets on another device', async () => {
    // The second device: nothing local, everything pulled from the cloud.
    const key = await generateLibraryKey()
    await pushRecording(repository, cloud.asClient(), key, RECORDING_ID)

    useDatabase(PHONE_DB)
    const phoneAdapter = new MemoryAdapter()
    const phone = new Repository(phoneAdapter)

    const result = await pullAll(phone, cloud.asClient(), key)
    expect(result.pulled).toBe(1)

    const bundle = await phone.loadReview(RECORDING_ID)
    expect(bundle).not.toBeNull()
    expect(bundle!.recording.displayName).toBe('Run club.m4a')
    expect(bundle!.recording.durationMs).toBe(600_000)
    expect(bundle!.segments.map((item) => item.text)).toEqual([
      'Hey good to meet you.',
      'The seawall route was solid.',
    ])
    expect(bundle!.comments[0]!.text).toBe('Follow up')
    expect(bundle!.redactions).toHaveLength(1)
    expect(bundle!.aliases[0]!.displayName).toBe('Andy')
    expect(bundle!.sets[0]!.title).toBe('Set 1')

    // Audio stays in the cloud rather than being downloaded onto the phone.
    expect(isCloudSourceKey(bundle!.recording.sourceKey)).toBe(true)
    // And it is searchable straight away.
    expect(phone.search.search('seawall', RECORDING_ID)).toHaveLength(1)
  })

  it('skips items it already has at the same version', async () => {
    const key = await generateLibraryKey()
    await pushRecording(repository, cloud.asClient(), key, RECORDING_ID)

    useDatabase(PHONE_DB)
    const phone = new Repository(new MemoryAdapter())
    await pullAll(phone, cloud.asClient(), key)

    const second = await pullAll(phone, cloud.asClient(), key)
    expect(second.pulled).toBe(0)
    expect(second.skipped).toBe(1)
  })

  it('ignores an item whose upload never finished', async () => {
    const key = await generateLibraryKey()
    await cloud.beginItem({
      id: 'half-done',
      audioBytes: 10,
      metadataBytes: 10,
      audioNonce: 'AAAAAAAAAAA=',
      metadataNonce: 'BBBBBBBBBBB=',
    })

    const result = await pullAll(repository, cloud.asClient(), key)
    expect(result.pulled).toBe(0)
    expect(result.skipped).toBe(1)
  })

  it('re-uploading bumps the version so other devices pick it up', async () => {
    const key = await generateLibraryKey()
    await pushRecording(repository, cloud.asClient(), key, RECORDING_ID)

    useDatabase(PHONE_DB)
    const phone = new Repository(new MemoryAdapter())
    await pullAll(phone, cloud.asClient(), key)

    // Back to the desktop, which still has the local audio, and edit there.
    useDatabase(DESKTOP_DB)
    await repository.setComments(RECORDING_ID, [
      forThis(comment('c1', 7_000, 'Follow up')),
      forThis(comment('c2', 9_000, 'Added later')),
    ])
    await pushRecording(repository, cloud.asClient(), key, RECORDING_ID)

    useDatabase(PHONE_DB)
    const again = await pullAll(phone, cloud.asClient(), key)
    expect(again.pulled).toBe(1)
    const bundle = await phone.loadReview(RECORDING_ID)
    expect(bundle!.comments.map((item) => item.text)).toContain('Added later')
  })
})

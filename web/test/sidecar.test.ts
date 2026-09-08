import { describe, expect, it } from 'vitest'
import {
  SIDECAR_SCHEMA_VERSION,
  SidecarFormatError,
  backupSidecarName,
  buildSidecar,
  parseSidecar,
  serializeSidecar,
  sidecarName,
  sidecarToEntities,
} from '../src/core/sidecar'
import androidSidecar from './fixtures/sidecar-v4.json'
import { comment, conversationSet, recording, redaction, segment, word } from './helpers'

/**
 * Web-specific, and the most important interop test in the suite: an Android
 * user must be able to open their recordings folder here and get their work
 * back, then take it home again.
 */
describe('Sidecar v4', () => {
  it('uses the same file names Android writes', () => {
    expect(sidecarName('Interview.m4a')).toBe('Interview.m4a.debrief.json')
    expect(backupSidecarName('Interview.m4a')).toBe('Interview.m4a.debrief.backup.json')
  })

  it('reads a sidecar written by the Android app', () => {
    const document = parseSidecar(JSON.stringify(androidSidecar))

    expect(document.schemaVersion).toBe(4)
    expect(document.recordingName).toBe('2026-07-18 Run club.m4a')
    expect(document.transcript).toHaveLength(2)
    expect(document.words).toHaveLength(4)
    expect(document.comments[0]!.text).toBe('Ask about the seawall route')
    expect(document.redactions[0]!.startMs).toBe(4_200)
    expect(document.sets[0]!.title).toBe('Set 1')
    expect(document.speakerAliases).toEqual({ 'Speaker A': 'Andy', 'Speaker B': 'Priya' })
  })

  it('binds a parsed sidecar to a local recording id', () => {
    const entities = sidecarToEntities('local-id', parseSidecar(JSON.stringify(androidSidecar)))

    expect(entities.segments.every((item) => item.recordingId === 'local-id')).toBe(true)
    expect(entities.words[0]!.confidence).toBeCloseTo(0.98)
    expect(entities.aliases).toContainEqual({
      recordingId: 'local-id',
      speakerId: 'Speaker B',
      displayName: 'Priya',
    })
    expect(entities.sets[0]!.recordingId).toBe('local-id')
  })

  it('round-trips everything the web app owns', () => {
    const input = {
      recording: recording({ displayName: 'Interview.m4a', durationMs: 900_000, sizeBytes: 4096 }),
      segments: [segment(0, 2_000, 'Speaker A', 'Hello there.')],
      words: [word('Hello', 0, 900, 'Speaker A', 0.91), word('there.', 900, 2_000, 'Speaker A', null)],
      comments: [comment('c1', 1_500, 'Follow up on this')],
      redactions: [redaction(500, 800)],
      aliases: [{ recordingId: 'recording', speakerId: 'Speaker A', displayName: 'Andy' }],
      sets: [conversationSet('s1', 0, 'Set 1', 2_000)],
      writtenAtEpochMs: 1_754_000_000_000,
    }

    const reparsed = parseSidecar(serializeSidecar(buildSidecar(input)))

    expect(reparsed.schemaVersion).toBe(SIDECAR_SCHEMA_VERSION)
    expect(reparsed.recordingName).toBe('Interview.m4a')
    expect(reparsed.recordingDurationMs).toBe(900_000)
    expect(reparsed.transcript).toEqual([
      { speakerId: 'Speaker A', startMs: 0, endMs: 2_000, text: 'Hello there.' },
    ])
    expect(reparsed.words[1]!.confidence).toBeNull()
    expect(reparsed.comments[0]!.text).toBe('Follow up on this')
    expect(reparsed.redactions[0]!.endMs).toBe(800)
    expect(reparsed.speakerAliases).toEqual({ 'Speaker A': 'Andy' })
    expect(reparsed.sets[0]!.title).toBe('Set 1')
  })

  it('accepts an older schema, filling in the fields it predates', () => {
    const older = JSON.stringify({
      schemaVersion: 2,
      recordingName: 'Old.m4a',
      recordingSizeBytes: 10,
      transcript: [{ speakerId: 'Speaker A', startMs: 0, endMs: 1_000, text: 'Hi' }],
      words: [],
      comments: [],
      speakerAliases: {},
    })

    const document = parseSidecar(older)

    expect(document.schemaVersion).toBe(2)
    expect(document.redactions).toEqual([])
    expect(document.sets).toEqual([])
  })

  it('refuses a newer schema rather than half-reading it', () => {
    const newer = JSON.stringify({
      schemaVersion: SIDECAR_SCHEMA_VERSION + 1,
      recordingName: 'Future.m4a',
    })

    expect(() => parseSidecar(newer)).toThrow(SidecarFormatError)
    expect(() => parseSidecar(newer)).toThrow(/newer version of Debrief/)
  })

  it('rejects a file that is not a sidecar at all', () => {
    expect(() => parseSidecar('not json')).toThrow(SidecarFormatError)
    expect(() => parseSidecar('[]')).toThrow(SidecarFormatError)
    expect(() => parseSidecar('{"schemaVersion":4}')).toThrow(/missing its recording name/)
  })
})

import { sha256Hex } from '../core/cloud-crypto'
import type { Comment, ConversationSet, Redaction, ReviewBundle, TranscriptSegment } from '../core/models'
import { redactedTranscriptText, wordsForSegment } from '../core/redactions'

const PART_BYTES = 8 * 1024 * 1024

interface PreparedSet { set: ConversationSet; audio: Uint8Array; metadata: Uint8Array }

export async function createShareLink(
  baseUrl: string,
  token: string,
  bundle: ReviewBundle,
  audioBlob: Blob,
  selected: ConversationSet[],
  expiryDays: 30 | 60 | 90,
  pin: string | null,
  onProgress?: (message: string) => void,
): Promise<{ shareId: string; url: string; expiresAt: number; sizeBytes: number }> {
  if (selected.length === 0) throw new Error('Choose at least one completed set.')
  if (selected.some((set) => set.endMs <= set.startMs)) throw new Error('Finish every selected set before sharing.')
  const total = selected.reduce((sum, set) => sum + set.endMs - set.startMs, 0)
  if (total > 3 * 60 * 60 * 1000) throw new Error('One link can contain at most three hours of selected audio.')
  onProgress?.('Preparing private audio clips')
  const context = new AudioContext()
  let decoded: AudioBuffer
  try { decoded = await context.decodeAudioData(await audioBlob.arrayBuffer()) } finally { void context.close() }
  const prepared = selected.map((set) => prepareSet(bundle, decoded, set))
  const root = baseUrl.replace(/\/+$/, '')
  const manifest = {
    title: bundle.recording.displayName,
    expiryDays,
    pin: pin || null,
    sets: prepared.map(({ set, audio, metadata }) => ({
      clientSetId: set.id.replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 100),
      title: set.title || `Set ${set.orderIndex + 1}`,
      durationMs: set.endMs - set.startMs,
      audioMimeType: 'audio/wav',
      expectedAudioBytes: audio.length,
      expectedMetadataBytes: metadata.length,
    })),
  }
  const draftResponse = await api(root, token, '/v1/owner/share-drafts', { method: 'POST', json: manifest })
  const draft = await draftResponse.json() as { draftId: string; sets: Array<{ clientSetId: string; objects: Array<{ objectId: string; kind: 'AUDIO' | 'METADATA' }> }> }
  for (let index = 0; index < prepared.length; index += 1) {
    const item = prepared[index]!
    const remote = draft.sets[index]
    if (!remote) throw new Error('The share service returned an incomplete draft.')
    for (const object of remote.objects) {
      const bytes = object.kind === 'AUDIO' ? item.audio : item.metadata
      onProgress?.(`Uploading ${index + 1} of ${prepared.length}: ${item.set.title}`)
      const parts: Array<{ partNumber: number; etag: string }> = []
      for (let offset = 0, number = 1; offset < bytes.length; offset += PART_BYTES, number += 1) {
        const response = await api(root, token, `/v1/owner/share-drafts/${draft.draftId}/objects/${object.objectId}/parts/${number}`, { method: 'PUT', body: bytes.slice(offset, offset + PART_BYTES) })
        parts.push(await response.json() as { partNumber: number; etag: string })
      }
      await api(root, token, `/v1/owner/share-drafts/${draft.draftId}/objects/${object.objectId}/complete`, {
        method: 'POST', json: { parts, sizeBytes: bytes.length, sha256: await sha256Hex(bytes) },
      })
    }
  }
  onProgress?.('Activating private link')
  const publicToken = randomToken()
  const published = await api(root, token, `/v1/owner/share-drafts/${draft.draftId}/publish`, { method: 'POST', json: { publicToken } })
  return await published.json() as { shareId: string; url: string; expiresAt: number; sizeBytes: number }
}

function prepareSet(bundle: ReviewBundle, decoded: AudioBuffer, set: ConversationSet): PreparedSet {
  const startFrame = Math.max(0, Math.floor(set.startMs / 1000 * decoded.sampleRate))
  const endFrame = Math.min(decoded.length, Math.ceil(set.endMs / 1000 * decoded.sampleRate))
  if (endFrame <= startFrame) throw new Error(`${set.title} falls outside the audio.`)
  const channels = Array.from({ length: decoded.numberOfChannels }, (_, channel) => decoded.getChannelData(channel).slice(startFrame, endFrame))
  mute(channels, decoded.sampleRate, bundle.redactions, set.startMs, set.endMs)
  // Public clips are speech-focused 16 kHz mono PCM. That is lossless at the
  // chosen sample rate, universally playable, and keeps an hour near 115 MB
  // instead of producing multi-gigabyte stereo WAV files.
  const audio = encodeWav([speechMono(channels, decoded.sampleRate)], 16_000)
  const durationMs = set.endMs - set.startMs
  const segments = bundle.segments
    .filter((segment) => segment.startMs < set.endMs && segment.endMs > set.startMs)
    .map((segment) => publicSegment(bundle, segment, set, durationMs))
    .filter((segment): segment is NonNullable<typeof segment> => segment !== null)
  const comments = bundle.comments
    .filter((comment) => comment.timestampMs >= set.startMs && comment.timestampMs <= set.endMs)
    .map((comment: Comment) => ({ timestampMs: Math.min(durationMs, comment.timestampMs - set.startMs), text: comment.text }))
  const metadata = new TextEncoder().encode(JSON.stringify({ schemaVersion: 1, title: set.title, durationMs, segments, comments }))
  return { set, audio, metadata }
}

function publicSegment(bundle: ReviewBundle, segment: TranscriptSegment, set: ConversationSet, durationMs: number) {
  const startMs = Math.max(0, segment.startMs - set.startMs)
  const endMs = Math.min(durationMs, segment.endMs - set.startMs)
  if (endMs <= startMs) return null
  const alias = bundle.aliases.find((item) => item.speakerId === segment.speakerId)?.displayName ?? segment.speakerId
  return {
    speaker: alias,
    startMs,
    endMs,
    text: redactedTranscriptText(segment.text, wordsForSegment(bundle.words, segment.startMs, segment.endMs), bundle.redactions, segment.startMs, segment.endMs),
  }
}

function mute(channels: Float32Array[], rate: number, redactions: Redaction[], setStart: number, setEnd: number) {
  for (const redaction of redactions.filter((item) => item.startMs < setEnd && item.endMs > setStart)) {
    const start = Math.max(0, Math.floor((redaction.startMs - 750 - setStart) / 1000 * rate))
    const end = Math.min(channels[0]?.length ?? 0, Math.ceil((redaction.endMs + 250 - setStart) / 1000 * rate))
    for (const channel of channels) channel.fill(0, start, end)
  }
}

function encodeWav(channels: Float32Array[], sampleRate: number): Uint8Array {
  const frames = channels[0]?.length ?? 0
  const output = new ArrayBuffer(44 + frames * channels.length * 2)
  const view = new DataView(output)
  write(view, 0, 'RIFF'); view.setUint32(4, output.byteLength - 8, true); write(view, 8, 'WAVEfmt ')
  view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, channels.length, true)
  view.setUint32(24, sampleRate, true); view.setUint32(28, sampleRate * channels.length * 2, true)
  view.setUint16(32, channels.length * 2, true); view.setUint16(34, 16, true); write(view, 36, 'data'); view.setUint32(40, output.byteLength - 44, true)
  let offset = 44
  for (let frame = 0; frame < frames; frame += 1) for (const channel of channels) {
    const sample = Math.max(-1, Math.min(1, channel[frame] ?? 0))
    view.setInt16(offset, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true); offset += 2
  }
  return new Uint8Array(output)
}

function speechMono(channels: Float32Array[], sourceRate: number): Float32Array {
  const sourceFrames = channels[0]?.length ?? 0
  const targetRate = 16_000
  const output = new Float32Array(Math.max(1, Math.floor(sourceFrames * targetRate / sourceRate)))
  for (let index = 0; index < output.length; index += 1) {
    const sourceIndex = Math.min(sourceFrames - 1, Math.floor(index * sourceRate / targetRate))
    let sum = 0
    for (const channel of channels) sum += channel[sourceIndex] ?? 0
    output[index] = sum / Math.max(1, channels.length)
  }
  return output
}

function write(view: DataView, offset: number, value: string) { for (let i = 0; i < value.length; i += 1) view.setUint8(offset + i, value.charCodeAt(i)) }
function randomToken(): string { const bytes = crypto.getRandomValues(new Uint8Array(32)); return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '') }

async function api(base: string, token: string, path: string, options: { method: string; json?: unknown; body?: Uint8Array }): Promise<Response> {
  const response = await fetch(`${base}${path}`, { method: options.method, headers: { Authorization: `Bearer ${token}`, ...(options.json ? { 'Content-Type': 'application/json' } : { 'Content-Type': 'application/octet-stream' }) }, body: options.json ? JSON.stringify(options.json) : options.body as BodyInit })
  if (!response.ok) { const data = await response.json().catch(() => null) as { error?: { message?: string } } | null; throw new Error(data?.error?.message ?? `Share service failed (${response.status}).`) }
  return response
}

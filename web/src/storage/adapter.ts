/**
 * The storage seam.
 *
 * Android has one answer for "where do the recordings live": a folder the user
 * linked through the Storage Access Framework, with JSON sidecars written next
 * to each file. The web has two answers, and which one you get depends on the
 * browser rather than on any choice the app makes:
 *
 *  - `linked-folder`  - File System Access API. A real folder, real sidecars
 *                       beside the audio. Full parity with Android. Chromium
 *                       desktop only.
 *  - `browser-storage`- the origin-private file system. The user imports files;
 *                       audio and annotations live in browser storage and
 *                       sidecars are exported and imported by hand. This is
 *                       what iOS Safari gets, and therefore what makes the app
 *                       reachable at all.
 *
 * Both implement this interface, and the UI always states which one is active.
 */

export type StorageMode = 'linked-folder' | 'browser-storage'

export interface AudioSource {
  /** Stable identifier for this audio within the adapter. */
  key: string
  name: string
  sizeBytes: number
  lastModified: number
  mimeType: string | null
}

export interface StorageAdapter {
  readonly mode: StorageMode
  /** Short human description shown in the UI, e.g. "Linked folder: Recordings". */
  readonly label: string
  /** True when sidecars are written automatically beside the audio, as on Android. */
  readonly supportsAutomaticSidecars: boolean

  /** Recordings currently visible to this adapter. */
  list(): Promise<AudioSource[]>
  /** Opens audio as a Blob. Never read into an ArrayBuffer - these are hours long. */
  open(key: string): Promise<Blob>
  /** Adds files chosen by the user. A linked folder re-scans instead. */
  add(files: File[]): Promise<AudioSource[]>
  remove(key: string): Promise<void>

  writeSidecar(recordingName: string, contents: string): Promise<void>
  readSidecar(recordingName: string): Promise<string | null>
}

/** Extensions the Android library accepts: MP3, M4A, WAV and AAC. */
const AUDIO_EXTENSIONS = ['.mp3', '.m4a', '.wav', '.aac', '.mp4', '.ogg', '.opus', '.flac', '.webm']

export function isAudioFileName(name: string): boolean {
  const lower = name.toLowerCase()
  return AUDIO_EXTENSIONS.some((extension) => lower.endsWith(extension))
}

export function isSidecarFileName(name: string): boolean {
  return name.toLowerCase().endsWith('.debrief.json') || name.toLowerCase().endsWith('.debrief.backup.json')
}

/** Best-effort MIME type when the platform does not supply one. */
export function guessMimeType(name: string): string | null {
  const lower = name.toLowerCase()
  if (lower.endsWith('.mp3')) return 'audio/mpeg'
  if (lower.endsWith('.m4a') || lower.endsWith('.mp4')) return 'audio/mp4'
  if (lower.endsWith('.wav')) return 'audio/wav'
  if (lower.endsWith('.aac')) return 'audio/aac'
  if (lower.endsWith('.ogg') || lower.endsWith('.opus')) return 'audio/ogg'
  if (lower.endsWith('.flac')) return 'audio/flac'
  if (lower.endsWith('.webm')) return 'audio/webm'
  return null
}

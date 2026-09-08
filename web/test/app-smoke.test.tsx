import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from '../src/ui/App'
import { OpfsStorageAdapter } from '../src/storage/opfs-adapter'
import { Repository } from '../src/state/repository'
import { FakeDirectoryHandle, audioFile } from './fake-fs'
import { freshDatabase } from './fresh-db'

/**
 * End-to-end-ish smoke coverage in jsdom. Unit tests prove the ported logic is
 * faithful; these prove the app actually mounts, picks a storage mode, lists a
 * recording and opens it, rather than crashing on first render.
 */
describe('App', () => {
  beforeEach(() => {
    freshDatabase()
    vi.unstubAllGlobals()
    // jsdom has no media element playback and no object URLs for blobs.
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:audio', revokeObjectURL: () => {} }))
  })

  it('explains itself when the browser can store nothing at all', async () => {
    // This is what a page served over plain http:// looks like: the browser
    // switches off OPFS and WebCrypto, and every button would fail. Saying so
    // beats showing an app that appears to do nothing.
    removeOpfs()

    render(<App />)

    expect(
      await screen.findByRole('heading', { name: /This browser can’t store recordings/i }),
    ).toBeInTheDocument()
    expect(screen.getByText(/switch off local storage/i)).toBeInTheDocument()
  })

  it('asks which storage to use when the browser CAN link a folder', async () => {
    // Regression: a browser that supports folder linking used to fall straight
    // through to browser storage, so the user was never offered the mode with
    // real Android parity.
    installOpfs()
    vi.stubGlobal('showDirectoryPicker', () => Promise.reject(new Error('not called')))

    render(<App />)

    expect(
      await screen.findByRole('heading', { name: /Where should Debrief keep your recordings/i }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Link a folder' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Use browser storage' })).toBeInTheDocument()
  })

  it('remembers a choice of browser storage and stops asking', async () => {
    installOpfs()
    vi.stubGlobal('showDirectoryPicker', () => Promise.reject(new Error('not called')))
    const { saveStorageMode } = await import('../src/storage')
    await saveStorageMode('browser-storage')

    render(<App />)

    expect(await screen.findByRole('button', { name: 'Add audio' })).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: /Where should Debrief keep your recordings/i }),
    ).not.toBeInTheDocument()
  })

  it('says why a non-audio file was skipped instead of doing nothing', async () => {
    installOpfs()

    render(<App />)
    await screen.findByRole('button', { name: 'Add audio' })
    const input = document.querySelector('input[type=file]') as HTMLInputElement
    await userEvent.upload(input, new File(['notes'], 'notes.txt', { type: 'text/plain' }))

    expect(await screen.findByText(/Skipped notes\.txt/i)).toBeInTheDocument()
    expect(screen.getByText(/MP3, M4A, WAV and AAC/i)).toBeInTheDocument()
  })

  it('does not filter the file picker, so iOS can reach files outside the media library', () => {
    installOpfs()
    render(<App />)

    return waitFor(() => {
      const input = document.querySelector('input[type=file]') as HTMLInputElement
      // An `accept` list makes ordinary files unselectable on iOS, which looks
      // exactly like the button being broken.
      expect(input).not.toHaveAttribute('accept')
    })
  })

  it('mounts into browser storage, lists an imported recording and opens it', async () => {
    const root = installOpfs()
    await seedRecording(root)

    render(<App />)

    // Library shows the recording and its status.
    expect(await screen.findByText('Interview.m4a')).toBeInTheDocument()
    expect(screen.getByText('New')).toBeInTheDocument()
    expect(screen.getByText('Browser storage')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Interview\.m4a/ }))

    expect(await screen.findByRole('button', { name: '← Library' })).toBeInTheDocument()
    expect(
      screen.getByText(/No transcript yet\. Select this recording in the Library/i),
    ).toBeInTheDocument()
  })

  it('renders a transcript with speakers, comments and tap-to-seek timestamps', async () => {
    installOpfs()
    await seedRecording(new FakeDirectoryHandle(), { withTranscript: true })

    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: /Interview\.m4a/ }))

    expect(await screen.findByText('Hey good to meet you.')).toBeInTheDocument()
    expect(screen.getByText('The seawall route was solid.')).toBeInTheDocument()
    expect(screen.getAllByText('Speaker A').length).toBeGreaterThan(0)
    // A comment left in the gap after the final line must still be visible.
    expect(screen.getByText('Left during silence')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '0:01' })).toBeInTheDocument()
  })

  it('keeps the comment composer reachable below the transcript', async () => {
    // Regression: the composer used to sit above the transcript, so on a long
    // recording you had to scroll all the way back up to add a comment.
    installOpfs()
    await seedRecording(new FakeDirectoryHandle(), { withTranscript: true })

    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: /Interview\.m4a/ }))
    await screen.findByText('Hey good to meet you.')

    const composer = document.querySelector('.composer')
    const transcript = document.querySelector('.transcript')!
    // Pinned to the viewport by `.composer` (jsdom does not apply the
    // stylesheet, so the class is what can be asserted here)...
    expect(composer).not.toBeNull()
    // ...and after the transcript in reading and tab order, rather than above it.
    expect(
      transcript.compareDocumentPosition(composer!) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
  })

  it('attaches a comment to where you started typing, not where the audio got to', async () => {
    installOpfs()
    await seedRecording(new FakeDirectoryHandle(), { withTranscript: true })

    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: /Interview\.m4a/ }))
    await screen.findByText('Hey good to meet you.')

    // Seek to 0:06, then start writing.
    await userEvent.click(screen.getByRole('button', { name: '0:06' }))
    const input = screen.getByLabelText(/Add a comment at/i)
    await userEvent.type(input, 'Check this')

    // The composer should be committed to 0:06 even though playback may move on.
    expect(screen.getByLabelText(/Add a comment at 0:06/i)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Add' }))

    await waitFor(() => {
      expect(screen.getByText('Check this')).toBeInTheDocument()
    })
    expect(screen.getAllByRole('button', { name: '0:06' }).length).toBeGreaterThan(0)
  })

  it('masks redacted words in the transcript when redaction mode is on', async () => {
    installOpfs()
    await seedRecording(new FakeDirectoryHandle(), { withTranscript: true })

    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: /Interview\.m4a/ }))
    await screen.findByText('Hey good to meet you.')

    // Privacy mode is on by default. Redact the first line, then confirm the
    // words are gone from the DOM.
    expect(screen.getByLabelText(/Redaction mode/i)).toBeChecked()
    await userEvent.click(screen.getAllByRole('button', { name: 'Redact this line' })[0]!)

    await waitFor(() => {
      expect(screen.queryByText('Hey good to meet you.')).not.toBeInTheDocument()
    })
    expect(screen.getByText('[redacted]')).toBeInTheDocument()
    expect(screen.getByText(/playback mutes from/i)).toBeInTheDocument()
  })

  it('offers Transcribe on the recording itself, not only behind a checkbox', async () => {
    // Regression: the only way to transcribe used to be ticking a checkbox with
    // a visually-hidden label, and the toolbar button sat there permanently
    // disabled. Clicking it did nothing and explained nothing.
    installOpfs()
    await seedRecording(new FakeDirectoryHandle())

    render(<App />)
    await screen.findByText('Interview.m4a')

    expect(screen.getByRole('button', { name: 'Transcribe' })).toBeEnabled()
    // No inert batch button when nothing is selected.
    expect(screen.queryByRole('button', { name: /selected/ })).not.toBeInTheDocument()
  })

  it('says what is missing when transcription cannot start', async () => {
    installOpfs()
    await seedRecording(new FakeDirectoryHandle())

    render(<App />)
    await screen.findByText('Interview.m4a')
    await userEvent.click(screen.getByRole('button', { name: 'Transcribe' }))

    // Rather than failing silently, it names the next step.
    expect(await screen.findByText(/create a key vault, and add your AssemblyAI or Deepgram API key/i)).toBeInTheDocument()
  })

  it('reveals the batch button once recordings are ticked', async () => {
    installOpfs()
    await seedRecording(new FakeDirectoryHandle())

    render(<App />)
    await screen.findByText('Interview.m4a')
    await userEvent.click(screen.getByRole('checkbox'))

    expect(await screen.findByRole('button', { name: 'Transcribe 1 selected' })).toBeEnabled()
  })

  it('offers the offline recorder from the Record tab', async () => {
    installOpfs()

    render(<App />)
    await screen.findByRole('button', { name: 'Record' })
    await userEvent.click(screen.getByRole('button', { name: 'Record' }))

    expect(await screen.findByRole('heading', { name: '0:00' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Start recording' })).toBeEnabled()
    expect(screen.getByText(/Keep this app visible while recording on iPhone/i)).toBeInTheDocument()
  })

  it('offers no upload control until the cloud library is connected', async () => {
    // Opt-in per recording means exactly that: with no cloud configured there
    // is nothing on the card that could send audio anywhere.
    installOpfs()
    await seedRecording(new FakeDirectoryHandle())

    render(<App />)
    await screen.findByText('Interview.m4a')

    expect(screen.queryByRole('button', { name: 'Upload' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /In cloud/ })).not.toBeInTheDocument()
  })

  it('asks to pair before asking for a cloud passphrase', async () => {
    installOpfs()

    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'Settings' }))

    expect(await screen.findByRole('heading', { name: 'Cloud library' })).toBeInTheDocument()
    expect(screen.getByLabelText(/Worker URL/i)).toBeInTheDocument()
    // The passphrase step only appears once a device token exists, so a
    // pairing failure and a wrong passphrase stay distinguishable.
    expect(screen.queryByLabelText(/Cloud passphrase/i)).not.toBeInTheDocument()
  })

  it('states plainly that losing the cloud passphrase loses the data', async () => {
    installOpfs()

    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'Settings' }))
    await screen.findByRole('heading', { name: 'Cloud library' })

    expect(screen.getByText(/if you lose the passphrase, the cloud\s+copy cannot be recovered/i)).toBeInTheDocument()
    expect(screen.getByText(/stores authenticated bytes it cannot read/i)).toBeInTheDocument()
  })

  it('rejects a Worker URL that is not a full https address', async () => {
    installOpfs()

    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'Settings' }))
    const workerUrl = await screen.findByLabelText(/Worker URL/i)
    await userEvent.clear(workerUrl)
    await userEvent.type(workerUrl, 'my-worker.dev')
    await userEvent.type(screen.getByLabelText(/Pairing code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: 'Pair this device' }))

    expect(await screen.findByText(/including https:\/\//i)).toBeInTheDocument()
  })

  it('asks for a vault passphrase before any key can be entered', async () => {
    installOpfs()

    render(<App />)
    await screen.findByRole('button', { name: 'Settings' })
    await userEvent.click(screen.getByRole('button', { name: 'Settings' }))

    expect(await screen.findByLabelText(/Choose a vault passphrase/i)).toBeInTheDocument()
    // No key field is offered until the vault exists and is unlocked.
    expect(screen.queryByLabelText(/AssemblyAI API key/i)).not.toBeInTheDocument()
  })
})

function installOpfs(): FakeDirectoryHandle {
  const root = new FakeDirectoryHandle('root')
  Object.defineProperty(globalThis.navigator, 'storage', {
    value: { getDirectory: async () => root, persist: async () => false, estimate: async () => ({ usage: 0, quota: 0 }) },
    configurable: true,
  })
  return root
}

function removeOpfs(): void {
  Object.defineProperty(globalThis.navigator, 'storage', { value: undefined, configurable: true })
}

async function seedRecording(
  _root: FakeDirectoryHandle,
  options: { withTranscript?: boolean } = {},
): Promise<void> {
  const adapter = new OpfsStorageAdapter()
  const repository = new Repository(adapter)
  const [source] = await adapter.add([audioFile('Interview.m4a')])
  const recording = await repository.importSource(source!)
  if (!options.withTranscript) return

  await repository.replaceTranscript(
    recording.id,
    [
      segment(recording.id, 1_000, 5_000, 'Speaker A', 'Hey good to meet you.'),
      segment(recording.id, 6_000, 10_000, 'Speaker B', 'The seawall route was solid.'),
    ],
    [
      word(recording.id, 'Hey', 1_000, 1_400),
      word(recording.id, 'good', 1_400, 1_800),
      word(recording.id, 'to', 1_800, 2_100),
      word(recording.id, 'meet', 2_100, 2_500),
      word(recording.id, 'you.', 2_500, 3_000),
    ],
  )
  await repository.setComments(recording.id, [
    {
      id: 'c1',
      recordingId: recording.id,
      timestampMs: 40_000,
      text: 'Left during silence',
      createdAt: 0,
      updatedAt: 0,
    },
  ])
  await repository.updateRecording(recording.id, { status: 'READY', durationMs: 60_000 })
}

function segment(
  recordingId: string,
  startMs: number,
  endMs: number,
  speakerId: string,
  text: string,
) {
  return { id: startMs, recordingId, speakerId, startMs, endMs, text }
}

function word(recordingId: string, text: string, startMs: number, endMs: number) {
  return { id: startMs, recordingId, speakerId: 'Speaker A', startMs, endMs, text, confidence: 0.9 }
}

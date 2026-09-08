import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { userMessage } from '../core/errors'
import type { Recording } from '../core/models'
import type { ProviderId } from '../core/transcription/provider'
import {
  detectCapabilities,
  requestPersistentStorage,
  storageEstimate,
  type Capabilities,
} from '../platform/capabilities'
import {
  FileSystemAccessAdapter,
  OpfsStorageAdapter,
  resolveStorageAdapter,
  saveStorageMode,
} from '../storage'
import { isAudioFileName, type StorageAdapter } from '../storage/adapter'
import { KeyVault, vaultExists } from '../storage/keys'
import { Repository } from './repository'
import { DEFAULT_SETTINGS, loadSettings, saveSettings, type AppSettings } from './settings'
import { runTranscription } from './transcription-job'

export interface TranscriptionProgress {
  stage: string
  fraction: number | null
}

export interface AppState {
  ready: boolean
  capabilities: Capabilities
  storage: StorageAdapter | null
  needsRelink: boolean
  /** The browser can link a folder but the user has not chosen a mode yet. */
  needsChoice: boolean
  persisted: boolean
  estimate: { usage: number; quota: number } | null
  recordings: Recording[]
  settings: AppSettings
  vault: KeyVault | null
  hasVault: boolean
  messages: string[]
  progress: Record<string, TranscriptionProgress>
}

export function useApp() {
  const capabilities = useMemo(() => detectCapabilities(), [])
  const repositoryRef = useRef<Repository | null>(null)

  const [ready, setReady] = useState(false)
  const [storage, setStorage] = useState<StorageAdapter | null>(null)
  const [needsRelink, setNeedsRelink] = useState(false)
  const [needsChoice, setNeedsChoice] = useState(false)
  const [persisted, setPersisted] = useState(false)
  const [estimate, setEstimate] = useState<{ usage: number; quota: number } | null>(null)
  const [recordings, setRecordings] = useState<Recording[]>([])
  const [settings, setSettings] = useState<AppSettings>(DEFAULT_SETTINGS)
  const [vault, setVault] = useState<KeyVault | null>(null)
  const [hasVault, setHasVault] = useState(false)
  const [messages, setMessages] = useState<string[]>([])
  const [progress, setProgress] = useState<Record<string, TranscriptionProgress>>({})

  const notify = useCallback((message: string) => {
    setMessages((current) => [...current.slice(-4), message])
  }, [])

  const dismissMessage = useCallback((index: number) => {
    setMessages((current) => current.filter((_, position) => position !== index))
  }, [])

  const refreshRecordings = useCallback(async () => {
    const repository = repositoryRef.current
    if (!repository) return
    setRecordings(await repository.listRecordings())
  }, [])

  // --- startup ------------------------------------------------------------

  useEffect(() => {
    let cancelled = false

    void (async () => {
      try {
        const loaded = await loadSettings()
        // Deepgram holds one multi-hour request open, which iOS will kill when
        // the tab is backgrounded. Default such devices to the resumable provider.
        const provider: ProviderId =
          capabilities.suspendsBackgroundTabs && loaded.provider === 'deepgram'
            ? 'deepgram'
            : loaded.provider
        if (!cancelled) setSettings({ ...loaded, provider })

        const resolved = await resolveStorageAdapter()
        if (cancelled) return
        setNeedsRelink(resolved.needsRelink)
        setNeedsChoice(resolved.needsChoice)

        if (resolved.adapter) {
          const repository = new Repository(resolved.adapter)
          repositoryRef.current = repository
          setStorage(resolved.adapter)
          await repository.rescan().catch(() => undefined)
          await repository.rebuildAllSearch()
          setRecordings(await repository.listRecordings())
        }

        setHasVault(await vaultExists())
        setPersisted(await requestPersistentStorage())
        setEstimate(await storageEstimate())
      } catch (error) {
        if (!cancelled) notify(userMessage('Debrief could not start up cleanly.', error))
      } finally {
        if (!cancelled) setReady(true)
      }
    })()

    return () => {
      cancelled = true
    }
  }, [capabilities.suspendsBackgroundTabs, notify])

  // --- storage ------------------------------------------------------------

  const linkFolder = useCallback(async () => {
    try {
      const adapter = await FileSystemAccessAdapter.pick()
      const repository = repositoryRef.current ?? new Repository(adapter)
      repository.setStorage(adapter)
      repositoryRef.current = repository
      setStorage(adapter)
      setNeedsRelink(false)
      setNeedsChoice(false)
      await saveStorageMode('linked-folder')
      await repository.rescan()
      await repository.rebuildAllSearch()
      await refreshRecordings()
      notify(`Linked ${adapter.label}.`)
    } catch (error) {
      notify(userMessage('Could not link that folder.', error))
    }
  }, [notify, refreshRecordings])

  const relinkFolder = useCallback(async () => {
    try {
      const adapter = (await FileSystemAccessAdapter.reauthorize()) ?? (await FileSystemAccessAdapter.pick())
      const repository = repositoryRef.current ?? new Repository(adapter)
      repository.setStorage(adapter)
      repositoryRef.current = repository
      setStorage(adapter)
      setNeedsRelink(false)
      setNeedsChoice(false)
      await saveStorageMode('linked-folder')
      await repository.rescan()
      await repository.rebuildAllSearch()
      await refreshRecordings()
    } catch (error) {
      notify(userMessage('Could not reconnect to the folder.', error))
    }
  }, [notify, refreshRecordings])

  /** Falls back to browser storage when the user declines to link a folder. */
  const useBrowserStorage = useCallback(async () => {
    if (!OpfsStorageAdapter.isSupported()) {
      notify('This browser has no private storage available for recordings.')
      return
    }
    const adapter = new OpfsStorageAdapter()
    const repository = repositoryRef.current ?? new Repository(adapter)
    repository.setStorage(adapter)
    repositoryRef.current = repository
    setStorage(adapter)
    setNeedsRelink(false)
    setNeedsChoice(false)
    await saveStorageMode('browser-storage')
    await repository.rebuildAllSearch()
    await refreshRecordings()
  }, [notify, refreshRecordings])

  /**
   * Import must never end in silence. Every path out of here either adds
   * recordings or says why it did not - an earlier version returned quietly
   * when there was no repository or nothing usable in the selection, which
   * looked exactly like the button being broken.
   */
  const importFiles = useCallback(
    async (files: File[]) => {
      // An empty selection means the user cancelled the picker. That is the one
      // case where saying nothing is right.
      if (files.length === 0) return

      const repository = repositoryRef.current
      if (!repository) {
        notify('Debrief has no storage open yet. Choose where to keep recordings first.')
        return
      }

      const audio = files.filter(
        (file) => file.type.startsWith('audio/') || isAudioFileName(file.name),
      )
      const rejected = files.filter((file) => !audio.includes(file))
      if (rejected.length > 0) {
        notify(
          `Skipped ${rejected.map((file) => file.name).join(', ')} — Debrief reads MP3, M4A, WAV and AAC audio.`,
        )
      }
      if (audio.length === 0) return

      const empty = audio.filter((file) => file.size === 0)
      if (empty.length > 0) {
        notify(
          `${empty.map((file) => file.name).join(', ')} came through empty. On iPhone, pick the file from Browse rather than a share sheet preview.`,
        )
      }

      try {
        const sources = await repository.adapter.add(audio.filter((file) => file.size > 0))
        if (sources.length === 0) {
          notify('Nothing was imported. The files could not be read from this device.')
          return
        }
        for (const source of sources) await repository.importSource(source)
        await repository.rebuildAllSearch()
        await refreshRecordings()
        setEstimate(await storageEstimate())
        notify(`Imported ${sources.length} recording${sources.length === 1 ? '' : 's'}.`)
      } catch (error) {
        notify(userMessage('Could not import those recordings.', error))
      }
    },
    [notify, refreshRecordings],
  )

  const rescan = useCallback(async () => {
    const repository = repositoryRef.current
    if (!repository) return
    try {
      const { added, removed } = await repository.rescan()
      await repository.rebuildAllSearch()
      await refreshRecordings()
      notify(`Rescan complete. ${added.length} added, ${removed.length} removed.`)
    } catch (error) {
      notify(userMessage('Could not rescan the folder.', error))
    }
  }, [notify, refreshRecordings])

  const deleteRecording = useCallback(
    async (id: string) => {
      const repository = repositoryRef.current
      if (!repository) return
      try {
        await repository.deleteRecording(id)
        await refreshRecordings()
      } catch (error) {
        notify(userMessage('Could not remove that recording.', error))
      }
    },
    [notify, refreshRecordings],
  )

  // --- settings and keys --------------------------------------------------

  const updateSettings = useCallback(async (changes: Partial<AppSettings>) => {
    setSettings((current) => {
      const next = { ...current, ...changes }
      void saveSettings(next)
      return next
    })
  }, [])

  const createVault = useCallback(
    async (passphrase: string) => {
      try {
        setVault(await KeyVault.create(passphrase))
        setHasVault(true)
      } catch (error) {
        notify(userMessage('Could not create the key vault.', error))
      }
    },
    [notify],
  )

  const unlockVault = useCallback(
    async (passphrase: string) => {
      try {
        setVault(await KeyVault.unlock(passphrase))
      } catch (error) {
        notify(error instanceof Error ? error.message : 'Could not unlock the key vault.')
      }
    },
    [notify],
  )

  const lockVault = useCallback(() => setVault(null), [])

  // --- transcription ------------------------------------------------------

  const transcribe = useCallback(
    async (recordingIds: string[]) => {
      const repository = repositoryRef.current
      if (!repository) return
      if (!vault) {
        notify(
          hasVault
            ? 'Open Settings and unlock your key vault before transcribing.'
            : 'Open Settings, create a key vault, and add your AssemblyAI or Deepgram API key before transcribing.',
        )
        return
      }
      if (!vault.has(settings.provider)) {
        notify(
          `No ${settings.provider === 'assemblyai' ? 'AssemblyAI' : 'Deepgram'} API key is saved. Add one in Settings.`,
        )
        return
      }

      for (const id of recordingIds) {
        await repository.updateStatus(id, 'QUEUED')
      }
      await refreshRecordings()

      for (const id of recordingIds) {
        const result = await runTranscription(id, {
          repository,
          settings: {
            provider: settings.provider,
            keyterms: settings.keyterms,
            audioQuality: settings.transcriptionAudioQuality,
          },
          resolveApiKey: (provider) => vault.get(provider),
          onProgress: (recordingId, stage, fraction) =>
            setProgress((current) => ({ ...current, [recordingId]: { stage, fraction } })),
          onMessage: notify,
        })
        setProgress((current) => {
          const next = { ...current }
          delete next[id]
          return next
        })
        if (!result.ok && result.message) notify(result.message)
        await refreshRecordings()
      }
    },
    [hasVault, notify, refreshRecordings, settings, vault],
  )

  return {
    state: {
      ready,
      capabilities,
      storage,
      needsRelink,
      needsChoice,
      persisted,
      estimate,
      recordings,
      settings,
      vault,
      hasVault,
      messages,
      progress,
    } satisfies AppState,
    repository: repositoryRef.current,
    actions: {
      linkFolder,
      relinkFolder,
      useBrowserStorage,
      importFiles,
      rescan,
      deleteRecording,
      updateSettings,
      createVault,
      unlockVault,
      lockVault,
      transcribe,
      refreshRecordings,
      notify,
      dismissMessage,
    },
  }
}

export type AppApi = ReturnType<typeof useApp>

import { useCallback, useEffect, useMemo, useState } from 'react'
import { userMessage } from '../core/errors'
import { CloudClient, type CloudUsage } from './cloud-client'
import {
  clearCloudConfig,
  getCloudState,
  listCloudStates,
  loadCloudConfig,
  pullAll,
  pushRecording,
  removeFromCloud,
  saveCloudConfig,
  unlockLibraryKey,
  type CloudConfig,
  type CloudState,
} from './cloud'
import { clearServiceWorkerKey, ensureServiceWorker, primeServiceWorkerKey } from './cloud-playback'
import type { Repository } from './repository'

/**
 * Cloud state for the UI.
 *
 * Two separate things have to be true before the library is usable, and they
 * fail differently, so they are tracked separately: the device must be *paired*
 * (has an owner token) and the library must be *unlocked* (has the data key).
 * Collapsing them into one "connected" flag would make "wrong passphrase" and
 * "not paired" indistinguishable.
 */

export interface CloudUiState {
  config: CloudConfig | null
  paired: boolean
  unlocked: boolean
  usage: CloudUsage | null
  states: Record<string, CloudState>
  busy: string | null
  progress: { id: string; stage: string; fraction: number | null } | null
}

export function useCloud(repository: Repository | null, notify: (message: string) => void) {
  const [config, setConfig] = useState<CloudConfig | null>(null)
  const [key, setKey] = useState<CryptoKey | null>(null)
  const [usage, setUsage] = useState<CloudUsage | null>(null)
  const [states, setStates] = useState<Record<string, CloudState>>({})
  const [busy, setBusy] = useState<string | null>(null)
  const [progress, setProgress] = useState<CloudUiState['progress']>(null)

  const client = useMemo(
    () => (config ? new CloudClient(config.baseUrl, config.token) : null),
    [config],
  )

  const refreshStates = useCallback(async () => {
    const all = await listCloudStates()
    setStates(Object.fromEntries(all.map((state) => [state.recordingId, state])))
  }, [])

  useEffect(() => {
    void (async () => {
      setConfig(await loadCloudConfig())
      await refreshStates()
    })()
  }, [refreshStates])

  const refreshUsage = useCallback(async () => {
    if (!client) return
    try {
      setUsage(await client.usage())
    } catch {
      // A stale figure is better than a wrong one; leave the last value.
    }
  }, [client])

  useEffect(() => {
    void refreshUsage()
  }, [refreshUsage])

  const pair = useCallback(
    async (baseUrl: string, code: string, label: string) => {
      setBusy('Pairing this device')
      try {
        const normalised = baseUrl.trim().replace(/\/+$/, '')
        if (!/^https?:\/\//.test(normalised)) {
          notify('Enter the full Worker URL, including https://.')
          return
        }
        const token = await CloudClient.pair(normalised, code, label)
        const next = { baseUrl: normalised, token, deviceLabel: label }
        await saveCloudConfig(next)
        setConfig(next)
        notify('This device is paired with your cloud library.')
      } catch (error) {
        notify(userMessage('Could not pair this device.', error))
      } finally {
        setBusy(null)
      }
    },
    [notify],
  )

  const unlock = useCallback(
    async (passphrase: string) => {
      if (!client) {
        notify('Pair this device before unlocking the cloud library.')
        return
      }
      setBusy('Unlocking the cloud library')
      try {
        const { key: dataKey, created } = await unlockLibraryKey(client, passphrase)
        setKey(dataKey)
        await ensureServiceWorker()
        await primeServiceWorkerKey(dataKey)
        notify(
          created
            ? 'Cloud library created. Keep this passphrase safe — nobody can recover it for you.'
            : 'Cloud library unlocked.',
        )
        await refreshUsage()
      } catch (error) {
        notify(userMessage('Could not unlock the cloud library.', error))
      } finally {
        setBusy(null)
      }
    },
    [client, notify, refreshUsage],
  )

  const lock = useCallback(async () => {
    setKey(null)
    await clearServiceWorkerKey()
  }, [])

  const disconnect = useCallback(async () => {
    await clearCloudConfig()
    await clearServiceWorkerKey()
    setConfig(null)
    setKey(null)
    setUsage(null)
    setStates({})
    notify('This device is no longer connected to your cloud library. Nothing in the cloud was deleted.')
  }, [notify])

  const upload = useCallback(
    async (recordingId: string) => {
      if (!repository || !client || !key) {
        notify('Unlock the cloud library in Settings first.')
        return
      }
      setBusy('Uploading')
      try {
        await pushRecording(repository, client, key, recordingId, (stage, fraction) =>
          setProgress({ id: recordingId, stage, fraction }),
        )
        await refreshStates()
        await refreshUsage()
        notify('Uploaded to your cloud library.')
      } catch (error) {
        notify(userMessage('Could not upload that recording.', error))
      } finally {
        setProgress(null)
        setBusy(null)
      }
    },
    [client, key, notify, refreshStates, refreshUsage, repository],
  )

  const sync = useCallback(async () => {
    if (!repository || !client || !key) {
      notify('Unlock the cloud library in Settings first.')
      return
    }
    setBusy('Syncing')
    try {
      const { pulled, skipped } = await pullAll(repository, client, key)
      await refreshStates()
      notify(
        pulled > 0
          ? `Pulled ${pulled} recording${pulled === 1 ? '' : 's'} from the cloud.`
          : `Already up to date${skipped > 0 ? ` (${skipped} checked)` : ''}.`,
      )
    } catch (error) {
      notify(userMessage('Could not sync with the cloud.', error))
    } finally {
      setBusy(null)
    }
  }, [client, key, notify, refreshStates, repository])

  const removeUpload = useCallback(
    async (recordingId: string) => {
      if (!client) return
      try {
        await removeFromCloud(client, recordingId)
        await refreshStates()
        await refreshUsage()
        notify('Removed from your cloud library. The local copy is untouched.')
      } catch (error) {
        notify(userMessage('Could not remove that recording from the cloud.', error))
      }
    },
    [client, notify, refreshStates, refreshUsage],
  )

  return {
    state: {
      config,
      paired: config !== null,
      unlocked: key !== null,
      usage,
      states,
      busy,
      progress,
    } satisfies CloudUiState,
    client,
    key,
    actions: { pair, unlock, lock, disconnect, upload, sync, removeUpload, refreshUsage },
    getState: getCloudState,
  }
}

export type CloudApi = ReturnType<typeof useCloud>

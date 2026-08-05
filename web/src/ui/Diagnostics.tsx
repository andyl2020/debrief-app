import { useEffect, useState } from 'react'
import type { AppApi } from '../state/useApp'

/**
 * What this browser can and cannot do, in plain terms.
 *
 * This exists because a web app fails differently on every browser, and "it
 * didn't work" is impossible to act on. When something goes wrong, this page
 * says which capability is missing and what that means, and can be copied into
 * a bug report.
 */
export function Diagnostics({ app }: { app: AppApi }) {
  const { capabilities, storage, persisted, estimate } = app.state
  const [opfsWritable, setOpfsWritable] = useState<boolean | null>(null)

  useEffect(() => {
    // Feature-detecting `createWritable` matters on its own: Safari shipped
    // OPFS in 16.4 but only added createWritable in 17, so "has OPFS" alone
    // does not mean "can save a recording".
    void (async () => {
      if (!capabilities.opfs) {
        setOpfsWritable(false)
        return
      }
      try {
        const root = await navigator.storage.getDirectory()
        const handle = await root.getFileHandle('.debrief-probe', { create: true })
        setOpfsWritable(typeof handle.createWritable === 'function')
        await root.removeEntry('.debrief-probe').catch(() => undefined)
      } catch {
        setOpfsWritable(false)
      }
    })()
  }, [capabilities.opfs])

  const rows: Array<{ label: string; ok: boolean | null; detail: string }> = [
    {
      label: 'Secure context (HTTPS or localhost)',
      ok: window.isSecureContext,
      detail: window.isSecureContext
        ? 'Storage and encryption APIs are available.'
        : 'Served over plain HTTP, so the browser disables local storage and encryption. Use HTTPS, or localhost on this device.',
    },
    {
      label: 'Folder linking',
      ok: capabilities.fileSystemAccess,
      detail: capabilities.fileSystemAccess
        ? 'This browser can link a real folder, with automatic sidecars.'
        : 'Not available in this browser. Safari has no File System Access API — browser storage is used instead.',
    },
    {
      label: 'Browser storage (OPFS)',
      ok: capabilities.opfs,
      detail: capabilities.opfs
        ? 'Private storage for recordings is available.'
        : 'Unavailable. Without a secure context or OPFS, recordings cannot be kept on this device.',
    },
    {
      label: 'Can write audio to storage',
      ok: opfsWritable,
      detail:
        opfsWritable === false
          ? 'This browser has storage but cannot stream files into it (Safari before 17). Debrief falls back to storing audio in IndexedDB.'
          : 'Recordings can be written directly.',
    },
    {
      label: 'Encryption for API keys',
      ok: capabilities.webCrypto,
      detail: capabilities.webCrypto
        ? 'The key vault can encrypt your API keys.'
        : 'WebCrypto is unavailable, usually because the page is not served over HTTPS. Keys cannot be saved.',
    },
    {
      label: 'Persistent storage granted',
      ok: persisted,
      detail: persisted
        ? 'The browser has agreed not to reclaim your recordings.'
        : 'The browser may reclaim recordings when space runs low. Export sidecars for anything important.',
    },
    {
      label: 'Background tab suspension',
      ok: !capabilities.suspendsBackgroundTabs,
      detail: capabilities.suspendsBackgroundTabs
        ? 'This device suspends background tabs. Keep Debrief open while transcribing; AssemblyAI jobs resume, Deepgram jobs do not.'
        : 'Long transcriptions can run while this tab is in the background.',
    },
  ]

  const report = [
    `Debrief Web diagnostics`,
    `userAgent: ${navigator.userAgent}`,
    `storage mode: ${storage?.label ?? 'none'}`,
    `estimate: ${estimate ? `${estimate.usage} / ${estimate.quota}` : 'unknown'}`,
    ...rows.map((row) => `${row.ok === null ? '?' : row.ok ? 'yes' : 'NO '} ${row.label}`),
  ].join('\n')

  return (
    <div className="card">
      <h3>Diagnostics</h3>
      <p className="muted">
        What this browser supports. If something isn’t working, this is the first place to look.
      </p>
      <ul className="diagnostics">
        {rows.map((row) => (
          <li key={row.label}>
            <span className={`pill pill--${row.ok === null ? 'check' : row.ok ? 'good' : 'issue'}`}>
              {row.ok === null ? 'Checking' : row.ok ? 'Yes' : 'No'}
            </span>
            <span>
              <strong>{row.label}</strong>
              <span className="muted"> {row.detail}</span>
            </span>
          </li>
        ))}
      </ul>
      <button
        type="button"
        className="button"
        onClick={() => {
          void navigator.clipboard
            ?.writeText(report)
            .then(() => app.actions.notify('Diagnostics copied to the clipboard.'))
            .catch(() => app.actions.notify('Could not copy. Select the text above instead.'))
        }}
      >
        Copy diagnostics
      </button>
    </div>
  )
}

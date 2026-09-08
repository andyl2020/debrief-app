import { useEffect, useState } from 'react'
import { formatBytes } from '../core/format'
import type { CloudApi } from '../state/useCloud'
import type { SharedLink } from '../state/cloud-client'
import { GITHUB_URL } from './ComingSoon'

/**
 * Cloud library setup.
 *
 * Pairing and unlocking are presented as the two separate steps they are,
 * because they fail for different reasons: a bad pairing code means this
 * device is not authorised, a bad passphrase means the data cannot be read.
 * One combined "connect" button would make those indistinguishable.
 */
export function CloudSettings({ cloud }: { cloud: CloudApi }) {
  const { config, paired, unlocked, usage, busy } = cloud.state
  const [baseUrl, setBaseUrl] = useState('')
  const [code, setCode] = useState('')
  const [passphrase, setPassphrase] = useState('')
  const [shares, setShares] = useState<SharedLink[]>([])
  const refreshShares = async () => {
    if (!cloud.client) return
    try { setShares(await cloud.client.listShares()) } catch { /* preserve the last good list */ }
  }
  useEffect(() => { void refreshShares() }, [cloud.client])

  return (
    <div className="card">
      <h3>Cloud library</h3>
      <p className="muted">
        Keep chosen recordings in your own Cloudflare account so you can reach them from any device.
        Nothing uploads unless you ask for it.
      </p>

      {!paired ? (
        <form
          className="cloud-form"
          onSubmit={(event) => {
            event.preventDefault()
            void cloud.actions.pair(baseUrl, code, deviceLabel())
          }}
        >
          <label>
            Worker URL
            <input
              value={baseUrl}
              placeholder="https://debrief-share.your-account.workers.dev"
              onChange={(event) => setBaseUrl(event.target.value)}
            />
          </label>
          <label>
            Pairing code
            <input
              value={code}
              placeholder="From wrangler, see below"
              onChange={(event) => setCode(event.target.value)}
            />
          </label>
          <button type="submit" className="button button--primary" disabled={busy !== null}>
            {busy ?? 'Pair this device'}
          </button>
          <p className="muted">
            Get a code by calling <code>POST /v1/admin/pairing-codes</code> on your Worker with the
            bootstrap secret. Codes are single-use and last 15 minutes.
          </p>
        </form>
      ) : (
        <>
          <p>
            Paired with <strong>{config?.baseUrl}</strong> as {config?.deviceLabel}.
          </p>

          {!unlocked ? (
            <form
              className="cloud-form"
              onSubmit={(event) => {
                event.preventDefault()
                void cloud.actions.unlock(passphrase)
                setPassphrase('')
              }}
            >
              <label>
                Cloud passphrase
                <input
                  type="password"
                  value={passphrase}
                  autoComplete="current-password"
                  onChange={(event) => setPassphrase(event.target.value)}
                />
              </label>
              <button type="submit" className="button button--primary" disabled={busy !== null}>
                {busy ?? 'Unlock'}
              </button>
              <p className="muted">
                The first device to unlock creates the library. Every other device needs the same
                passphrase — that is the only thing you have to carry between them.
              </p>
            </form>
          ) : (
            <div className="setup__actions">
              <button type="button" className="button button--primary" onClick={() => void cloud.actions.sync()}>
                Sync now
              </button>
              <button type="button" className="button" onClick={() => void cloud.actions.lock()}>
                Lock
              </button>
              <button type="button" className="button button--quiet" onClick={() => void cloud.actions.disconnect()}>
                Disconnect this device
              </button>
            </div>
          )}

          {usage && (
            <>
              <p className="muted">
                Using {formatBytes(usage.bytes)} of {formatBytes(usage.freeReferenceBytes)} across{' '}
                {usage.items} recording{usage.items === 1 ? '' : 's'}.
              </p>
              <div
                className="meter"
                role="img"
                aria-label={`${Math.round((usage.bytes / Math.max(1, usage.freeReferenceBytes)) * 100)}% of the free storage reference used`}
              >
                <span
                  className={`meter__fill ${usage.bytes / usage.freeReferenceBytes > 0.9 ? 'meter__fill--warn' : ''}`}
                  style={{ width: `${Math.min(100, (usage.bytes / Math.max(1, usage.freeReferenceBytes)) * 100)}%` }}
                />
              </div>
              <p className="muted">
                10 GB is Cloudflare's free-tier reference, not a limit Debrief enforces. A six-hour
                recording is roughly 350 MB.
              </p>
            </>
          )}
          <div className="cloud-links">
            <div className="review__header">
              <h4>Shared links</h4>
              <button type="button" className="button button--quiet" onClick={() => void refreshShares()}>Refresh</button>
            </div>
            {shares.length === 0 ? <p className="muted">No published links.</p> : shares.map((share) => (
              <div className="cloud-link" key={share.id}>
                <span><strong>{share.title}</strong><br/><span className="muted">{share.setCount} set{share.setCount === 1 ? '' : 's'} · {formatBytes(share.totalSizeBytes)} · {share.status}{share.expiresAt ? ` · expires ${new Date(share.expiresAt).toLocaleDateString()}` : ''}</span></span>
                {share.status === 'ACTIVE' && <span className="setup__actions">
                  <button type="button" className="button button--quiet" onClick={() => void cloud.client?.extendShare(share.id, 30).then(refreshShares)}>+30 days</button>
                  <button type="button" className="button button--quiet" onClick={() => { if (confirm('Revoke this link now?')) void cloud.client?.revokeShare(share.id).then(refreshShares) }}>Revoke</button>
                </span>}
              </div>
            ))}
          </div>
        </>
      )}

      <p className="disclosure">
        <strong>What Cloudflare can and cannot see.</strong> Audio and transcripts are encrypted in
        this browser before they are uploaded, with a key derived from your passphrase. Your provider
        stores authenticated bytes it cannot read. <strong>If you lose the passphrase, the cloud
        copy cannot be recovered.</strong> Modified or truncated objects are rejected before playback.
      </p>
      <p className="disclosure">
        The personal cloud library is separate from{' '}
        <a href={GITHUB_URL} target="_blank" rel="noreferrer">
          Share Sets
        </a>{' '}
        which publishes selected, privacy-redacted clips on an expiring link.
      </p>
    </div>
  )
}

/** A human-readable device name, so paired devices are distinguishable later. */
function deviceLabel(): string {
  const agent = navigator.userAgent
  if (/iPhone/i.test(agent)) return 'iPhone browser'
  if (/iPad/i.test(agent)) return 'iPad browser'
  if (/Android/i.test(agent)) return 'Android browser'
  if (/Macintosh/i.test(agent)) return 'Mac browser'
  if (/Windows/i.test(agent)) return 'Windows browser'
  return 'Web browser'
}

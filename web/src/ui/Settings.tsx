import { useEffect, useState } from 'react'
import { AUDIO_QUALITY, AUDIO_QUALITY_ORDER } from '../core/audio-quality'
import { formatBytes } from '../core/format'
import type { ProviderId } from '../core/transcription/provider'
import { confirmAacEncoder } from '../platform/capabilities'
import { destroyVault } from '../storage/keys'
import type { AppApi } from '../state/useApp'
import { COMING_SOON_SCREENS, ComingSoon, GITHUB_URL } from './ComingSoon'
import { Diagnostics } from './Diagnostics'

const PROVIDERS: Array<{ id: ProviderId; label: string; blurb: string }> = [
  {
    id: 'assemblyai',
    label: 'AssemblyAI',
    blurb:
      'Recommended for noisy field recordings, and the only provider that can resume if this tab is backgrounded mid-job.',
  },
  {
    id: 'deepgram',
    label: 'Deepgram Nova-3',
    blurb:
      'Fast single-request transcription. The request stays open for the whole upload, so a suspended tab loses the job.',
  },
]

export function SettingsScreen({ app }: { app: AppApi }) {
  const { settings, capabilities, vault, persisted, estimate, storage } = app.state
  const [reencode, setReencode] = useState(false)
  const [section, setSection] = useState<'settings' | keyof typeof COMING_SOON_SCREENS>('settings')

  useEffect(() => {
    void confirmAacEncoder().then(setReencode)
  }, [])

  if (section !== 'settings') {
    return (
      <div>
        <button type="button" className="button button--quiet" onClick={() => setSection('settings')}>
          ← Settings
        </button>
        <ComingSoon {...COMING_SOON_SCREENS[section]!} />
      </div>
    )
  }

  return (
    <section className="settings">
      <h2>Settings</h2>

      <div className="card">
        <h3>API keys</h3>
        <p className="muted">
          Your key is sent only to the transcription provider you choose, straight from this browser.
          Debrief has no server.
        </p>
        <VaultPanel app={app} />
        {vault && (
          <div className="settings__keys">
            {PROVIDERS.map((provider) => (
              <ApiKeyRow key={provider.id} app={app} providerId={provider.id} label={provider.label} />
            ))}
          </div>
        )}
        <p className="disclosure">
          <strong>How this differs from Android.</strong> The Android app seals API keys with a
          non-exportable key held in the phone’s hardware Keystore. Browsers have no equivalent, so
          Debrief encrypts your keys with a passphrase you choose and keeps only the ciphertext. That
          is meaningfully weaker: anyone who can run script on this page while the vault is unlocked
          could read a key. Use a key scoped to transcription, and lock the vault when you are done.
        </p>
      </div>

      <div className="card">
        <h3>Transcription provider</h3>
        {PROVIDERS.map((provider) => (
          <label key={provider.id} className="radio">
            <input
              type="radio"
              name="provider"
              checked={settings.provider === provider.id}
              onChange={() => void app.actions.updateSettings({ provider: provider.id })}
            />
            <span>
              <strong>{provider.label}</strong>
              <span className="muted"> {provider.blurb}</span>
            </span>
          </label>
        ))}
        {capabilities.suspendsBackgroundTabs && settings.provider === 'deepgram' && (
          <p className="notice notice--warn">
            On iPhone and iPad the browser suspends background tabs. A long Deepgram upload will stall
            if you leave this page. AssemblyAI is the safer choice here.
          </p>
        )}
      </div>

      <div className="card">
        <h3>Key terms</h3>
        <p className="muted">
          Names, jargon and place names the provider should expect. One per line, or comma separated.
          Up to 100 are sent.
        </p>
        <textarea
          value={settings.keyterms}
          rows={4}
          onChange={(event) => void app.actions.updateSettings({ keyterms: event.target.value })}
          aria-label="Key terms"
        />
      </div>

      <div className="card">
        <h3>Upload quality</h3>
        {AUDIO_QUALITY_ORDER.map((quality) => {
          const spec = AUDIO_QUALITY[quality]
          const unavailable = quality !== 'ORIGINAL' && !reencode
          return (
            <label key={quality} className={`radio ${unavailable ? 'radio--disabled' : ''}`}>
              <input
                type="radio"
                name="quality"
                disabled={unavailable}
                checked={settings.transcriptionAudioQuality === quality}
                onChange={() => void app.actions.updateSettings({ transcriptionAudioQuality: quality })}
              />
              <span>
                <strong>{spec.label}</strong>
                <span className="muted"> {spec.description}</span>
              </span>
            </label>
          )
        })}
        {!reencode && (
          <p className="notice">
            The compressed modes need in-browser AAC encoding, which this browser does not provide —
            it is absent on iOS entirely. Recordings upload unchanged, which is also the Android
            default and the most accurate option.
          </p>
        )}
      </div>

      <div className="card">
        <h3>Storage</h3>
        <p>
          <strong>{storage?.label ?? 'Not linked'}</strong>
        </p>
        {storage?.supportsAutomaticSidecars ? (
          <p className="muted">
            Debrief writes <code>&lt;recording&gt;.debrief.json</code> and a{' '}
            <code>.debrief.backup.json</code> copy beside each recording, in the same format the
            Android app reads. Copy the folder to your phone and your work comes with it.
          </p>
        ) : (
          <p className="muted">
            Recordings and notes live in this browser’s private storage. Use <em>Export sidecar</em> on
            a recording to save a file you can open on Android or another device.
          </p>
        )}
        {estimate && (
          <p className="muted">
            Using {formatBytes(estimate.usage)} of roughly {formatBytes(estimate.quota)} available.
          </p>
        )}
        <p className={persisted ? 'muted' : 'notice notice--warn'}>
          {persisted
            ? 'This browser has marked Debrief’s storage as persistent.'
            : 'This browser has not granted persistent storage, so it may reclaim recordings when space runs low. Export sidecars for anything you cannot lose.'}
        </p>
        <p className="disclosure">
          <strong>How this differs from Android.</strong> The Android database is encrypted at rest
          with SQLCipher. Browser storage is not encrypted — it is protected by this site’s origin and
          by your device, and nothing more.
        </p>
      </div>

      <Diagnostics app={app} />

      <div className="card">
        <h3>Android-only features</h3>
        <p className="muted">
          These need capabilities a browser does not have. Download the full app on Android to
          experience full features.
        </p>
        <ul className="settings__links">
          {Object.entries(COMING_SOON_SCREENS).map(([key, screen]) => (
            <li key={key}>
              <button type="button" className="button button--quiet" onClick={() => setSection(key)}>
                {screen.title} →
              </button>
            </li>
          ))}
        </ul>
        <a className="button" href={GITHUB_URL} target="_blank" rel="noreferrer">
          Debrief on GitHub
        </a>
      </div>
    </section>
  )
}

function VaultPanel({ app }: { app: AppApi }) {
  const { vault, hasVault } = app.state
  const [passphrase, setPassphrase] = useState('')

  if (vault) {
    return (
      <div className="vault vault--unlocked">
        <p>Key vault unlocked{vault.providers().length > 0 ? ` · ${vault.providers().join(', ')}` : ''}.</p>
        <div className="setup__actions">
          <button type="button" className="button" onClick={app.actions.lockVault}>
            Lock vault
          </button>
          <button
            type="button"
            className="button button--quiet"
            onClick={() =>
              void (async () => {
                await destroyVault()
                app.actions.lockVault()
                app.actions.notify('Key vault deleted from this device.')
              })()
            }
          >
            Forget all keys
          </button>
        </div>
      </div>
    )
  }

  return (
    <form
      className="vault"
      onSubmit={(event) => {
        event.preventDefault()
        if (hasVault) void app.actions.unlockVault(passphrase)
        else void app.actions.createVault(passphrase)
        setPassphrase('')
      }}
    >
      <label>
        {hasVault ? 'Vault passphrase' : 'Choose a vault passphrase (8+ characters)'}
        <input
          type="password"
          value={passphrase}
          autoComplete={hasVault ? 'current-password' : 'new-password'}
          onChange={(event) => setPassphrase(event.target.value)}
        />
      </label>
      <button type="submit" className="button button--primary">
        {hasVault ? 'Unlock' : 'Create vault'}
      </button>
    </form>
  )
}

function ApiKeyRow({
  app,
  providerId,
  label,
}: {
  app: AppApi
  providerId: ProviderId
  label: string
}) {
  const vault = app.state.vault!
  const [value, setValue] = useState('')
  const saved = vault.has(providerId)

  return (
    <form
      className="key-row"
      onSubmit={(event) => {
        event.preventDefault()
        void (async () => {
          try {
            await vault.put(providerId, value)
            setValue('')
            app.actions.notify(`${label} key saved.`)
          } catch (error) {
            app.actions.notify(error instanceof Error ? error.message : 'Could not save that key.')
          }
        })()
      }}
    >
      <label>
        {label} API key {saved && <span className="pill pill--good">Saved</span>}
        <input
          type="password"
          value={value}
          placeholder={saved ? 'Enter a new key to replace it' : `Paste your ${label} key`}
          onChange={(event) => setValue(event.target.value)}
        />
      </label>
      <button type="submit" className="button" disabled={value.trim().length === 0}>
        Save
      </button>
      {saved && (
        <button
          type="button"
          className="button button--quiet"
          onClick={() =>
            void (async () => {
              await vault.remove(providerId)
              app.actions.notify(`${label} key removed.`)
            })()
          }
        >
          Remove
        </button>
      )}
    </form>
  )
}

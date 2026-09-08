import type { AppApi } from '../state/useApp'
import { GITHUB_URL } from './ComingSoon'

/**
 * First run, and the one place the platform difference is unavoidable.
 *
 * On desktop Chromium we can offer the real thing: link the same folder the
 * Android app uses, with sidecars written beside the recordings. On iOS Safari
 * that API does not exist, so we say so directly rather than showing a button
 * that would fail.
 */
export function StorageSetup({ app }: { app: AppApi }) {
  const { capabilities, needsRelink } = app.state

  if (needsRelink) {
    return (
      <section className="setup">
        <h2>Reconnect your recordings folder</h2>
        <p>
          Debrief remembers which folder you linked, but browsers require you to grant access again
          after a restart. Your transcripts and notes are safe on this device.
        </p>
        <div className="setup__actions">
          <button type="button" className="button button--primary" onClick={app.actions.relinkFolder}>
            Re-link folder
          </button>
          <button type="button" className="button" onClick={app.actions.useBrowserStorage}>
            Use browser storage instead
          </button>
        </div>
      </section>
    )
  }

  // Neither mode can work here. Say so up front instead of showing two buttons
  // that both fail — this is what a non-HTTPS origin looks like, and it is a
  // very easy way to end up with an app that appears to do nothing.
  if (!capabilities.fileSystemAccess && !capabilities.opfs) {
    return (
      <section className="setup">
        <h2>This browser can’t store recordings</h2>
        <div className="card card--highlight">
          <p>
            Debrief needs local storage to keep your audio and transcripts, and this browser is not
            providing it.
          </p>
          {!window.isSecureContext ? (
            <p>
              <strong>Most likely cause:</strong> this page is being served over plain{' '}
              <code>http://</code>. Browsers switch off local storage and encryption outside a secure
              context. Open Debrief over <code>https://</code>, or via <code>localhost</code> on this
              device.
            </p>
          ) : (
            <p>
              Your browser may be in private browsing mode, or may be too old. Debrief needs the
              origin-private file system, which Safari has from version 16.4.
            </p>
          )}
          <a className="button button--primary" href={GITHUB_URL} target="_blank" rel="noreferrer">
            Get Debrief for Android on GitHub
          </a>
        </div>
      </section>
    )
  }

  return (
    <section className="setup">
      <h2>Where should Debrief keep your recordings?</h2>

      {capabilities.fileSystemAccess ? (
        <div className="card card--highlight">
          <h3>Link a folder <span className="pill pill--good">Full parity</span></h3>
          <p>
            Debrief reads audio straight from a folder on this computer and writes its notes into
            JSON sidecar files beside each recording — the same files the Android app reads and
            writes. Copy the folder between devices and your comments, chapters, redactions and
            speaker names come with it.
          </p>
          <button type="button" className="button button--primary" onClick={app.actions.linkFolder}>
            Link a folder
          </button>
        </div>
      ) : (
        <div className="card">
          <h3>Folder linking isn’t available in this browser</h3>
          <p>
            The File System Access API — the closest web equivalent of the folder permission the
            Android app uses — exists only in desktop Chrome and Edge. Safari, including on iPhone
            and iPad, does not have it. Debrief will use browser storage instead, which works
            everywhere.
          </p>
        </div>
      )}

      <div className={`card ${capabilities.fileSystemAccess ? '' : 'card--highlight'}`}>
        <h3>Use browser storage {capabilities.opfs ? '' : <span className="pill pill--bad">Unavailable</span>}</h3>
        <p>
          You pick audio files and Debrief keeps them, plus your transcripts and notes, in this
          browser’s private storage. Nothing is uploaded except the audio you choose to transcribe.
          You export and import sidecar files by hand to move work between devices.
        </p>
        <p className="muted">
          Browser storage can be reclaimed by the operating system when space runs low, especially on
          iOS. Export a sidecar for anything you can’t afford to lose.
        </p>
        <button
          type="button"
          className={`button ${capabilities.fileSystemAccess ? '' : 'button--primary'}`}
          disabled={!capabilities.opfs}
          onClick={app.actions.useBrowserStorage}
        >
          Use browser storage
        </button>
      </div>

      <p className="muted setup__footnote">
        Browser recording is available from the Record tab. On iPhone and iPad, keep Debrief visible
        during important captures because iOS can suspend any browser app when the screen locks.
      </p>
    </section>
  )
}

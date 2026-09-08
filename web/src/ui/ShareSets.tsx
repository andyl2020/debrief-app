import { useState } from 'react'
import type { ReviewBundle } from '../core/models'
import type { CloudApi } from '../state/useCloud'
import type { Repository } from '../state/repository'
import { createShareLink } from '../state/share'
import { fetchDecryptedObject, isCloudSourceKey } from '../state/cloud'

export function ShareSets({ bundle, repository, cloud, onClose, notify }: { bundle: ReviewBundle; repository: Repository; cloud: CloudApi; onClose: () => void; notify: (message: string) => void }) {
  const completed = bundle.sets.filter((set) => set.endMs > set.startMs)
  const [selected, setSelected] = useState(new Set(completed.map((set) => set.id)))
  const [expiry, setExpiry] = useState<30 | 60 | 90>(30)
  const [pin, setPin] = useState('')
  const [busy, setBusy] = useState<string | null>(null)
  const [url, setUrl] = useState('')

  const create = async () => {
    if (!cloud.state.config) { notify('Pair this device with the share service in Settings first.'); return }
    try {
      setBusy('Opening audio')
      let audio: Blob
      if (isCloudSourceKey(bundle.recording.sourceKey)) {
        const state = await cloud.getState(bundle.recording.id)
        if (!state || !cloud.client || !cloud.key) throw new Error('Unlock the cloud library before sharing this recording.')
        const bytes = await fetchDecryptedObject(cloud.client, cloud.key, bundle.recording.id, 'audio', state.audioNonce, state.audioBytes, state.chunkBytes)
        audio = new Blob([bytes as BlobPart], { type: bundle.recording.mimeType ?? 'audio/mp4' })
      } else {
        audio = await repository.adapter.open(bundle.recording.sourceKey)
      }
      const published = await createShareLink(cloud.state.config.baseUrl, cloud.state.config.token, bundle, audio, completed.filter((set) => selected.has(set.id)), expiry, pin.trim() || null, setBusy)
      setUrl(published.url); setBusy(null); await navigator.clipboard?.writeText(published.url).catch(() => undefined)
      notify('Private share link created and copied.')
    } catch (error) { setBusy(null); notify(error instanceof Error ? error.message : 'Could not create the share link.') }
  }

  return <div className="modal-backdrop" role="presentation"><section className="card share-dialog" role="dialog" aria-modal="true" aria-labelledby="share-title">
    <div className="review__header"><h3 id="share-title">Share selected sets</h3><button className="button button--quiet" type="button" onClick={onClose}>Close</button></div>
    <p className="muted">The link contains only the checked sets, their transcript, comments, and privacy-muted audio. There is no download button.</p>
    {completed.length === 0 ? <p>Finish at least one manual set first.</p> : <div className="share-dialog__sets">{completed.map((set) => <label key={set.id} className="radio"><input type="checkbox" checked={selected.has(set.id)} onChange={() => setSelected((current) => { const next = new Set(current); if (next.has(set.id)) next.delete(set.id); else next.add(set.id); return next })}/><span>{set.title}</span></label>)}</div>}
    <label>Link expires<select value={expiry} onChange={(event) => setExpiry(Number(event.target.value) as 30 | 60 | 90)}><option value={30}>30 days</option><option value={60}>60 days</option><option value={90}>90 days</option></select></label>
    <label>Optional PIN<input inputMode="numeric" pattern="[0-9]{6,12}" placeholder="6–12 digits" value={pin} onChange={(event) => setPin(event.target.value.replace(/\D/g, '').slice(0, 12))}/></label>
    <button type="button" className="button button--primary" disabled={busy !== null || selected.size === 0 || (!!pin && pin.length < 6)} onClick={() => void create()}>{busy ?? 'Create private link'}</button>
    {url && <div className="share-result"><input readOnly value={url}/><button type="button" className="button" onClick={() => void navigator.clipboard.writeText(url)}>Copy</button><a className="button" href={url} target="_blank" rel="noreferrer">Open</a></div>}
    <p className="notice notice--warn">Clip preparation decodes audio locally and can need substantial memory for multi-hour source files. Nothing is uploaded until preparation succeeds.</p>
  </section></div>
}

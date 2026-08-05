import { useEffect, useMemo, useRef, useState } from 'react'
import { formatBytes, formatTimestamp } from '../core/format'
import type { Recording, SearchHit } from '../core/models'
import type { AppApi } from '../state/useApp'

/**
 * Mirrors the Android Library tab: status per recording, multi-select batch
 * transcription, and a search that spans filenames, transcripts, summaries and
 * comments (unlike the in-player search, which is transcript-only).
 */
export function Library({ app, onOpen }: { app: AppApi; onOpen: (id: string) => void }) {
  const { recordings, progress, storage } = app.state
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [query, setQuery] = useState('')
  const [hits, setHits] = useState<SearchHit[]>([])
  const fileInput = useRef<HTMLInputElement>(null)

  const repository = app.repository

  useEffect(() => {
    if (!repository || query.trim().length === 0) {
      setHits([])
      return
    }
    try {
      setHits(repository.search.search(query))
    } catch {
      app.actions.notify('Search is temporarily unavailable.')
    }
  }, [app.actions, query, repository])

  const toggle = (id: string) => {
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const transcribable = useMemo(
    () => recordings.filter((recording) => selected.has(recording.id)),
    [recordings, selected],
  )

  return (
    <section className="library">
      <div className="library__toolbar">
        <div className="library__search">
          <input
            type="search"
            value={query}
            placeholder="Search transcripts, comments and filenames"
            onChange={(event) => setQuery(event.target.value)}
            aria-label="Search the library"
          />
        </div>
        <div className="library__actions">
          <input
            ref={fileInput}
            type="file"
            /*
             * Deliberately unfiltered. On iOS an `accept` list routes the
             * picker at the media library and greys out ordinary files, so a
             * voice memo sitting in Files becomes unselectable and tapping
             * "Add audio" appears to do nothing. Non-audio selections are
             * rejected in `importFiles` with an explanation instead.
             */
            multiple
            hidden
            onChange={(event) => {
              const files = [...(event.target.files ?? [])]
              event.target.value = ''
              void app.actions.importFiles(files)
            }}
          />
          <button type="button" className="button" onClick={() => fileInput.current?.click()}>
            Add audio
          </button>
          {storage?.mode === 'linked-folder' && (
            <button type="button" className="button" onClick={app.actions.rescan}>
              Rescan folder
            </button>
          )}
          <button
            type="button"
            className="button button--primary"
            disabled={transcribable.length === 0}
            onClick={() => {
              void app.actions.transcribe(transcribable.map((recording) => recording.id))
              setSelected(new Set())
            }}
          >
            Transcribe{transcribable.length > 0 ? ` (${transcribable.length})` : ''}
          </button>
        </div>
      </div>

      {query.trim().length > 0 && (
        <div className="card">
          <h3>
            {hits.length} result{hits.length === 1 ? '' : 's'}
          </h3>
          <ul className="hits">
            {hits.slice(0, 25).map((hit, index) => (
              <li key={`${hit.recordingId}-${hit.timestampMs}-${index}`}>
                <button type="button" className="hit" onClick={() => onOpen(hit.recordingId)}>
                  <span className="hit__meta">
                    {hit.recordingName} · {formatTimestamp(hit.timestampMs)}
                    {hit.isComment ? ' · comment' : hit.speakerId ? ` · ${hit.speakerId}` : ''}
                  </span>
                  <span className="hit__snippet">{hit.snippet}</span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {recordings.length === 0 ? (
        <div className="card empty">
          <h3>No recordings yet</h3>
          <p>
            {storage?.mode === 'linked-folder'
              ? 'Add audio to the linked folder and rescan, or use Add audio to copy files in.'
              : 'Use Add audio to bring recordings into this browser.'}
          </p>
        </div>
      ) : (
        <ul className="recordings">
          {recordings.map((recording) => (
            <li key={recording.id}>
              <RecordingCard
                recording={recording}
                selected={selected.has(recording.id)}
                progress={progress[recording.id]}
                onToggle={() => toggle(recording.id)}
                onOpen={() => onOpen(recording.id)}
                onDelete={() => void app.actions.deleteRecording(recording.id)}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

const STATUS_LABEL: Record<Recording['status'], string> = {
  NEW: 'New',
  QUEUED: 'Queued',
  TRANSCRIBING: 'Transcribing',
  READY: 'Ready',
  FAILED: 'Failed',
}

function RecordingCard({
  recording,
  selected,
  progress,
  onToggle,
  onOpen,
  onDelete,
}: {
  recording: Recording
  selected: boolean
  progress?: { stage: string; fraction: number | null }
  onToggle: () => void
  onOpen: () => void
  onDelete: () => void
}) {
  return (
    <article className={`recording ${selected ? 'recording--selected' : ''}`}>
      <label className="recording__select">
        <input type="checkbox" checked={selected} onChange={onToggle} />
        <span className="visually-hidden">Select {recording.displayName}</span>
      </label>

      <button type="button" className="recording__body" onClick={onOpen}>
        <span className="recording__name">{recording.displayName}</span>
        <span className="recording__meta">
          {formatBytes(recording.sizeBytes)}
          {recording.durationMs > 0 ? ` · ${formatTimestamp(recording.durationMs)}` : ''}
        </span>
        {progress && (
          <span className="recording__progress">
            {progress.stage}
            {progress.fraction !== null ? ` · ${Math.round(progress.fraction * 100)}%` : ''}
          </span>
        )}
        {/* The stored failure message from the worker, shown verbatim so the
            user sees what the provider actually said. */}
        {recording.status === 'FAILED' && recording.errorMessage && (
          <span className="recording__error">{recording.errorMessage}</span>
        )}
      </button>

      <div className="recording__side">
        <span className={`status status--${recording.status.toLowerCase()}`}>
          {STATUS_LABEL[recording.status]}
        </span>
        <button type="button" className="button button--quiet" onClick={onDelete}>
          Remove
        </button>
      </div>
    </article>
  )
}

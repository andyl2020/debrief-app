import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { userMessage } from '../core/errors'
import { formatTimestamp } from '../core/format'
import { exportMarkdown } from '../core/markdown'
import type { Comment, Redaction, ReviewBundle, SearchHit } from '../core/models'
import {
  DEFAULT_PLAYBACK_SKIP_MS,
  PLAYBACK_SPEED_OPTIONS,
  applyPlaybackRate,
  formatPlaybackSkipInterval,
  formatPlaybackSpeed,
  nextPlaybackSkipInterval,
} from '../core/playback'
import {
  redactedTranscriptText,
  redactedWordChoices,
  redactionMuteRanges,
  redactionPlaybackVolumeForRanges,
  redactionRangesAfterRemovingWord,
  redactionRangesForWholeSegment,
  wordsForSegment,
} from '../core/redactions'
import { commentsForSegment, leadingComments } from '../core/comments'
import type { AppApi } from '../state/useApp'
import { Chapters } from './Chapters'
import { QualityReportCard } from './QualityReport'

/** How often playback position is polled. Matches the Android volume poll. */
const POSITION_POLL_MS = 75

export function Review({
  app,
  recordingId,
  onClose,
}: {
  app: AppApi
  recordingId: string
  onClose: () => void
}) {
  const repository = app.repository
  const audioRef = useRef<HTMLAudioElement>(null)
  const [bundle, setBundle] = useState<ReviewBundle | null>(null)
  const [audioUrl, setAudioUrl] = useState<string | null>(null)
  const [positionMs, setPositionMs] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [speed, setSpeed] = useState(1)
  const [speedNotice, setSpeedNotice] = useState<string | null>(null)
  const [skipMs, setSkipMs] = useState(DEFAULT_PLAYBACK_SKIP_MS)
  const [query, setQuery] = useState('')
  const [hits, setHits] = useState<SearchHit[]>([])
  const [chaptersOpen, setChaptersOpen] = useState(false)
  const [follow, setFollow] = useState(true)

  const redactionMode = app.state.settings.redactionMode

  const reload = useCallback(async () => {
    if (!repository) return
    setBundle(await repository.loadReview(recordingId))
  }, [recordingId, repository])

  useEffect(() => {
    void reload()
  }, [reload])

  // Load audio as an object URL. The Blob is never read into memory as bytes.
  useEffect(() => {
    if (!repository || !bundle) return
    let url: string | null = null
    let cancelled = false
    void (async () => {
      try {
        const blob = await repository.adapter.open(bundle.recording.sourceKey)
        if (cancelled) return
        url = URL.createObjectURL(blob)
        setAudioUrl(url)
      } catch (error) {
        if (!cancelled) app.actions.notify(userMessage('Could not open the audio.', error))
      }
    })()
    return () => {
      cancelled = true
      if (url) URL.revokeObjectURL(url)
    }
  }, [app.actions, bundle?.recording.sourceKey, repository])

  const muteRanges = useMemo(
    () => (redactionMode ? redactionMuteRanges(bundle?.redactions ?? []) : []),
    [bundle?.redactions, redactionMode],
  )

  /**
   * One poll drives both the transcript highlight and the redaction mute. The
   * mute decision is precomputed into ranges so this stays cheap even at 4x,
   * exactly as the Android player does it.
   */
  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return
    const timer = window.setInterval(() => {
      const current = Math.round(audio.currentTime * 1000)
      setPositionMs(current)
      audio.volume = redactionPlaybackVolumeForRanges(current, muteRanges)
    }, POSITION_POLL_MS)
    return () => window.clearInterval(timer)
  }, [muteRanges, audioUrl])

  // Persist the playback position, like Android's saved position.
  useEffect(() => {
    if (!repository || !bundle || positionMs === 0) return
    const timer = window.setTimeout(() => {
      void repository.updateRecording(recordingId, { playbackPositionMs: positionMs })
    }, 2_000)
    return () => window.clearTimeout(timer)
  }, [bundle, positionMs, recordingId, repository])

  useEffect(() => {
    if (!repository || query.trim().length === 0) {
      setHits([])
      return
    }
    // Scoped search: transcript rows of this recording only.
    setHits(repository.search.search(query, recordingId))
  }, [query, recordingId, repository])

  const seekTo = useCallback(
    (milliseconds: number) => {
      const audio = audioRef.current
      if (!audio) return
      const clamped = Math.max(0, milliseconds)
      audio.currentTime = clamped / 1000
      setPositionMs(clamped)
      audio.volume = redactionPlaybackVolumeForRanges(clamped, muteRanges)
    },
    [muteRanges],
  )

  const changeSpeed = useCallback((requested: number) => {
    const audio = audioRef.current
    if (!audio) return
    const applied = applyPlaybackRate(audio, requested)
    setSpeed(requested)
    setSpeedNotice(
      applied.clamped
        ? `This browser caps playback at ${formatPlaybackSpeed(applied.effective)}.`
        : null,
    )
  }, [])

  const mutate = useCallback(
    async (work: () => Promise<void>, failureMessage: string) => {
      if (!repository) return
      try {
        await work()
      } catch (error) {
        app.actions.notify(userMessage(failureMessage, error))
        return
      }
      // The edit itself has succeeded. Index and sidecar updates are reported
      // separately and never allowed to fail the edit.
      await reload()
      try {
        await repository.rebuildSearch(recordingId)
      } catch {
        app.actions.notify('Saved on device, but the search index couldn’t update yet.')
      }
      const backup = await repository.checkpointSidecar(recordingId)
      if (!backup.sidecarCurrent && repository.adapter.supportsAutomaticSidecars) {
        app.actions.notify(backup.error ?? 'The recording-folder backup couldn’t update.')
      }
    },
    [app.actions, recordingId, reload, repository],
  )

  if (!bundle) {
    return (
      <section className="review">
        <p className="muted">Loading recording…</p>
      </section>
    )
  }

  const { recording, segments, words, comments, redactions, aliases, sets, qualityReport } = bundle
  const aliasFor = (speakerId: string) =>
    aliases.find((alias) => alias.speakerId === speakerId)?.displayName ?? speakerId
  const durationMs = recording.durationMs

  const addComment = (timestampMs: number, text: string) =>
    void mutate(async () => {
      const comment: Comment = {
        id: crypto.randomUUID(),
        recordingId,
        timestampMs,
        text,
        createdAt: Date.now(),
        updatedAt: Date.now(),
      }
      await repository!.setComments(recordingId, [...comments, comment])
    }, 'Couldn’t add the comment.')

  const removeComment = (id: string) =>
    void mutate(
      () => repository!.setComments(recordingId, comments.filter((comment) => comment.id !== id)),
      'Couldn’t remove the comment.',
    )

  const setSpeakerAlias = (speakerId: string, displayName: string) =>
    void mutate(() => {
      const others = aliases.filter((alias) => alias.speakerId !== speakerId)
      const next = displayName.trim()
        ? [...others, { recordingId, speakerId, displayName: displayName.trim() }]
        : others
      return repository!.setAliases(recordingId, next)
    }, 'Couldn’t rename that speaker.')

  const applyRedactionRanges = (
    segmentStartMs: number,
    segmentEndMs: number,
    ranges: Array<{ startMs: number; endMs: number; text: string }>,
  ) =>
    void mutate(() => {
      // Replace this card's redactions wholesale with the new set.
      const untouched = redactions.filter(
        (r) => !(r.startMs < segmentEndMs && r.endMs > segmentStartMs),
      )
      const created: Redaction[] = ranges.map((range) => ({
        id: crypto.randomUUID(),
        recordingId,
        startMs: range.startMs,
        endMs: range.endMs,
        text: range.text,
        createdAt: Date.now(),
      }))
      return repository!.setRedactions(recordingId, [...untouched, ...created])
    }, 'Couldn’t update the redaction.')

  return (
    <section className="review">
      <div className="review__header">
        <button type="button" className="button button--quiet" onClick={onClose}>
          ← Library
        </button>
        <h2>{recording.displayName}</h2>
        <div className="review__header-actions">
          <button type="button" className="button" onClick={() => setChaptersOpen((open) => !open)}>
            Chapters
          </button>
          <button
            type="button"
            className="button"
            onClick={() => {
              const markdown = exportMarkdown({
                displayName: recording.displayName,
                segments,
                comments,
                aliases,
              })
              if (markdown.trim().length === 0) {
                app.actions.notify('No transcript is available to export.')
                return
              }
              download(`${recording.displayName}.md`, markdown, 'text/markdown')
            }}
          >
            Export Markdown
          </button>
          <button
            type="button"
            className="button"
            onClick={() =>
              void (async () => {
                const json = await repository!.exportSidecar(recordingId)
                if (!json) return
                download(`${recording.displayName}.debrief.json`, json, 'application/json')
              })()
            }
          >
            Export sidecar
          </button>
        </div>
      </div>

      <div className="player">
        {audioUrl && (
          <audio
            ref={audioRef}
            src={audioUrl}
            preload="metadata"
            onLoadedMetadata={(event) => {
              const seconds = event.currentTarget.duration
              if (Number.isFinite(seconds) && seconds > 0) {
                void repository!.updateRecording(recordingId, {
                  durationMs: Math.round(seconds * 1000),
                })
                void reload()
              }
              if (recording.playbackPositionMs > 0) seekTo(recording.playbackPositionMs)
            }}
            onPlay={() => setPlaying(true)}
            onPause={() => setPlaying(false)}
          />
        )}

        <div className="player__controls">
          <button type="button" className="button" onClick={() => seekTo(positionMs - skipMs)}>
            −{Math.round(skipMs / 1000)}s
          </button>
          <button
            type="button"
            className="button button--primary"
            onClick={() => {
              const audio = audioRef.current
              if (!audio) return
              if (audio.paused) void audio.play()
              else audio.pause()
            }}
          >
            {playing ? 'Pause' : 'Play'}
          </button>
          <button type="button" className="button" onClick={() => seekTo(positionMs + skipMs)}>
            +{Math.round(skipMs / 1000)}s
          </button>
          <button
            type="button"
            className="button button--quiet"
            onClick={() => setSkipMs(nextPlaybackSkipInterval(skipMs))}
            title="Change skip interval"
          >
            Skip: {formatPlaybackSkipInterval(skipMs)}
          </button>
          <span className="player__time">
            {formatTimestamp(positionMs)} / {formatTimestamp(durationMs)}
          </span>
        </div>

        <input
          className="player__scrubber"
          type="range"
          min={0}
          max={Math.max(durationMs, 1)}
          value={Math.min(positionMs, Math.max(durationMs, 1))}
          onChange={(event) => seekTo(Number(event.target.value))}
          aria-label="Playback position"
        />

        <div className="player__speeds">
          {PLAYBACK_SPEED_OPTIONS.map((option) => (
            <button
              key={option}
              type="button"
              className={`chip ${speed === option ? 'chip--active' : ''}`}
              onClick={() => changeSpeed(option)}
            >
              {formatPlaybackSpeed(option)}
            </button>
          ))}
          <label className="chip chip--toggle">
            <input type="checkbox" checked={follow} onChange={(e) => setFollow(e.target.checked)} />
            Follow
          </label>
          <label className="chip chip--toggle">
            <input
              type="checkbox"
              checked={redactionMode}
              onChange={(e) => void app.actions.updateSettings({ redactionMode: e.target.checked })}
            />
            Redaction mode
          </label>
        </div>

        {/* Safari clamps playbackRate; say so rather than showing a rate that isn't happening. */}
        {speedNotice && <p className="notice">{speedNotice}</p>}
        {redactionMode && (
          <p className="notice notice--privacy">
            Redaction mode is on. Redacted text is masked and playback mutes from{' '}
            {formatTimestamp(750)} before each redaction. Your source audio is never modified.
          </p>
        )}
      </div>

      <div className="review__search">
        <input
          type="search"
          value={query}
          placeholder="Search this transcript"
          onChange={(event) => setQuery(event.target.value)}
          aria-label="Search this transcript"
        />
        {hits.length > 0 && (
          <ul className="hits hits--inline">
            {hits.slice(0, 8).map((hit, index) => (
              <li key={`${hit.timestampMs}-${index}`}>
                <button type="button" className="hit" onClick={() => seekTo(hit.timestampMs)}>
                  <span className="hit__meta">{formatTimestamp(hit.timestampMs)}</span>
                  <span className="hit__snippet">{hit.snippet}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {qualityReport && <QualityReportCard report={qualityReport} />}

      {chaptersOpen && (
        <Chapters
          sets={sets}
          comments={comments}
          positionMs={positionMs}
          onSeek={seekTo}
          onClose={() => setChaptersOpen(false)}
        />
      )}

      <SpeakerAliases
        speakerIds={[...new Set(segments.map((segment) => segment.speakerId))]}
        aliasFor={aliasFor}
        onRename={setSpeakerAlias}
      />

      <div className="transcript">
        {segments.length === 0 && (
          <p className="muted">
            No transcript yet. Select this recording in the Library and choose Transcribe.
          </p>
        )}

        {leadingComments(comments, segments).map((comment) => (
          <CommentRow key={comment.id} comment={comment} onSeek={seekTo} onRemove={removeComment} />
        ))}

        {segments.map((segment, index) => {
          const segmentWords = wordsForSegment(words, segment.startMs, segment.endMs)
          const displayText = redactionMode
            ? redactedTranscriptText(segment.text, segmentWords, redactions, segment.startMs, segment.endMs)
            : segment.text
          const active = positionMs >= segment.startMs && positionMs <= segment.endMs
          const choices = redactionMode
            ? redactedWordChoices(segmentWords, redactions, segment.startMs, segment.endMs)
            : []

          return (
            <div key={segment.id || `${segment.startMs}-${index}`}>
              <article
                className={`segment ${active && follow ? 'segment--active' : ''}`}
                ref={active && follow ? scrollIntoView : undefined}
              >
                <button
                  type="button"
                  className="segment__time"
                  onClick={() => seekTo(segment.startMs)}
                >
                  {formatTimestamp(segment.startMs)}
                </button>
                <div className="segment__body">
                  <span className="segment__speaker">{aliasFor(segment.speakerId)}</span>
                  <p className="segment__text">{displayText}</p>
                  {redactionMode && (
                    <div className="segment__redaction">
                      <button
                        type="button"
                        className="button button--quiet"
                        onClick={() =>
                          applyRedactionRanges(
                            segment.startMs,
                            segment.endMs,
                            redactionRangesForWholeSegment(
                              segmentWords,
                              segment.startMs,
                              segment.endMs,
                              segment.text,
                            ),
                          )
                        }
                      >
                        Redact this line
                      </button>
                      {choices.length > 0 && (
                        <>
                          <button
                            type="button"
                            className="button button--quiet"
                            onClick={() => applyRedactionRanges(segment.startMs, segment.endMs, [])}
                          >
                            Clear
                          </button>
                          <select
                            className="segment__undo"
                            value=""
                            onChange={(event) => {
                              const choice = choices.find(
                                (candidate) => String(candidate.index) === event.target.value,
                              )
                              if (!choice) return
                              applyRedactionRanges(
                                segment.startMs,
                                segment.endMs,
                                redactionRangesAfterRemovingWord(
                                  segmentWords,
                                  redactions,
                                  segment.startMs,
                                  segment.endMs,
                                  choice,
                                ),
                              )
                            }}
                            aria-label="Reveal one redacted word"
                          >
                            <option value="">Reveal a word…</option>
                            {choices.map((choice) => (
                              <option key={choice.index} value={choice.index}>
                                {choice.index}. {choice.text}
                              </option>
                            ))}
                          </select>
                        </>
                      )}
                    </div>
                  )}
                </div>
              </article>

              {commentsForSegment(comments, segments, index, durationMs).map((comment) => (
                <CommentRow
                  key={comment.id}
                  comment={comment}
                  onSeek={seekTo}
                  onRemove={removeComment}
                />
              ))}
            </div>
          )
        })}
      </div>

      {/*
        Last in the DOM so it follows the transcript in reading and tab order,
        but pinned to the viewport so it is reachable no matter how far into a
        long recording you have scrolled.
      */}
      <AddComment positionMs={positionMs} onAdd={addComment} />
    </section>
  )
}

function CommentRow({
  comment,
  onSeek,
  onRemove,
}: {
  comment: Comment
  onSeek: (ms: number) => void
  onRemove: (id: string) => void
}) {
  return (
    <div className="comment">
      <button type="button" className="comment__time" onClick={() => onSeek(comment.timestampMs)}>
        {formatTimestamp(comment.timestampMs)}
      </button>
      <p className="comment__text">{comment.text}</p>
      <button type="button" className="button button--quiet" onClick={() => onRemove(comment.id)}>
        Delete
      </button>
    </div>
  )
}

/**
 * The comment composer, pinned to the bottom of the review screen.
 *
 * It used to sit above the transcript, which meant that on a six-hour
 * recording you could not add a comment without scrolling thousands of pixels
 * back up - precisely when you were deepest into the audio and most likely to
 * want one.
 *
 * It also pins its timestamp on the first keystroke rather than reading the
 * playhead at submit time. Typing a sentence takes several seconds, and at 2x
 * that is a long way from the thing you were reacting to. Android captures the
 * position the instant you tap; this is the equivalent.
 */
function AddComment({
  positionMs,
  onAdd,
}: {
  positionMs: number
  onAdd: (timestampMs: number, text: string) => void
}) {
  const [text, setText] = useState('')
  const [pinnedMs, setPinnedMs] = useState<number | null>(null)

  const targetMs = pinnedMs ?? positionMs
  const drifted = pinnedMs !== null && Math.abs(positionMs - pinnedMs) > 2_000

  const submit = () => {
    if (text.trim().length === 0) return
    onAdd(targetMs, text.trim())
    setText('')
    setPinnedMs(null)
  }

  return (
    <form
      className="composer"
      onSubmit={(event) => {
        event.preventDefault()
        submit()
      }}
    >
      <button
        type="button"
        className={`composer__stamp ${drifted ? 'composer__stamp--drifted' : ''}`}
        onClick={() => setPinnedMs(positionMs)}
        title={
          drifted
            ? `Comment will be saved at ${formatTimestamp(targetMs)}. Tap to move it to ${formatTimestamp(positionMs)}.`
            : 'The point in the recording this comment will be attached to'
        }
      >
        {formatTimestamp(targetMs)}
        {drifted && <span className="composer__move">move</span>}
      </button>

      <input
        value={text}
        onChange={(event) => {
          // Capture the playhead as soon as the user starts writing, so the
          // comment lands where they reacted, not where the audio got to.
          if (pinnedMs === null && event.target.value.length > 0) setPinnedMs(positionMs)
          setText(event.target.value)
        }}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            setText('')
            setPinnedMs(null)
          }
        }}
        placeholder="Add a comment here"
        aria-label={`Add a comment at ${formatTimestamp(targetMs)}`}
      />

      <button type="submit" className="button button--primary" disabled={text.trim().length === 0}>
        Add
      </button>
    </form>
  )
}

function SpeakerAliases({
  speakerIds,
  aliasFor,
  onRename,
}: {
  speakerIds: string[]
  aliasFor: (speakerId: string) => string
  onRename: (speakerId: string, displayName: string) => void
}) {
  if (speakerIds.length === 0) return null
  return (
    <div className="speakers">
      {speakerIds.map((speakerId) => (
        <label key={speakerId} className="speakers__item">
          <span className="muted">{speakerId}</span>
          <input
            defaultValue={aliasFor(speakerId) === speakerId ? '' : aliasFor(speakerId)}
            placeholder="Name this speaker"
            onBlur={(event) => onRename(speakerId, event.target.value)}
          />
        </label>
      ))}
    </div>
  )
}

/**
 * Keeps the active transcript line in view as playback advances.
 *
 * Guarded because `scrollIntoView` is not universal - older WebViews omit the
 * options overload, and it is absent entirely in jsdom. Failing to scroll is a
 * cosmetic loss; throwing from a ref callback takes the whole screen down.
 */
function scrollIntoView(element: HTMLElement | null) {
  if (typeof element?.scrollIntoView !== 'function') return
  try {
    element.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  } catch {
    element.scrollIntoView()
  }
}

function download(filename: string, contents: string, type: string) {
  const url = URL.createObjectURL(new Blob([contents], { type }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

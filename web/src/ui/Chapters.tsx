import { buildChapterEntries } from '../core/chapters'
import { formatTimestamp } from '../core/format'
import type { Comment, ConversationSet } from '../core/models'
import { setContainsPosition } from '../core/manual-sets'

/**
 * The side-opening table of contents from `ChaptersDrawer.kt`: manual
 * conversation sets and timestamped comments in one chronological list, with
 * tap-to-jump and the currently-playing set highlighted.
 */
export function Chapters({
  sets,
  comments,
  positionMs,
  onSeek,
  onClose,
}: {
  sets: ConversationSet[]
  comments: Comment[]
  positionMs: number
  onSeek: (ms: number) => void
  onClose: () => void
}) {
  const entries = buildChapterEntries(sets, comments)
  const activeSet = [...sets].reverse().find((set) => setContainsPosition(set, positionMs))

  return (
    <aside className="chapters" aria-label="Chapters">
      <div className="chapters__header">
        <div>
          <h3>Chapters</h3>
          <p className="muted">
            {sets.length} set{sets.length === 1 ? '' : 's'} · {comments.length} comment
            {comments.length === 1 ? '' : 's'}
          </p>
        </div>
        <button type="button" className="button button--quiet" onClick={onClose}>
          Close
        </button>
      </div>

      {entries.length === 0 ? (
        <p className="muted">
          No chapters yet. Add a comment, or mark a conversation set, to build a table of contents.
        </p>
      ) : (
        <ul className="chapters__list">
          {entries.map((entry) => (
            <li key={entry.id}>
              <button
                type="button"
                className={`chapter chapter--${entry.type.toLowerCase()} ${
                  activeSet && entry.id === `set:${activeSet.id}` ? 'chapter--active' : ''
                }`}
                onClick={() => onSeek(entry.timestampMs)}
              >
                <span className="chapter__time">{formatTimestamp(entry.timestampMs)}</span>
                <span className="chapter__title">{entry.title}</span>
                {entry.detail && <span className="chapter__detail">{entry.detail}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </aside>
  )
}

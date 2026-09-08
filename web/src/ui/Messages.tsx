/**
 * The equivalent of the Android snackbar stream (`_messages` in ViewModels.kt).
 *
 * Every message here is a *reported* failure rather than a thrown one - the
 * app's rule is that bookkeeping problems get surfaced, not escalated.
 */
export function Messages({
  messages,
  onDismiss,
}: {
  messages: string[]
  onDismiss: (index: number) => void
}) {
  if (messages.length === 0) return null
  return (
    <div className="messages" role="status" aria-live="polite">
      {messages.map((message, index) => (
        <div className="messages__item" key={`${message}-${index}`}>
          <span>{message}</span>
          <button type="button" className="messages__close" onClick={() => onDismiss(index)} aria-label="Dismiss">
            ×
          </button>
        </div>
      ))}
    </div>
  )
}

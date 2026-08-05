/**
 * Ported from `formatTimestamp` in
 * `app/src/main/java/com/andyluu/debrief/ui/ViewModels.kt:916`.
 *
 * Hours are only shown once the recording passes the hour mark, which keeps
 * transcript cards narrow for the common case.
 */
export function formatTimestamp(milliseconds: number): string {
  const totalSeconds = Math.floor(Math.max(0, milliseconds) / 1000)
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  const pad = (value: number) => String(value).padStart(2, '0')
  return hours > 0
    ? `${hours}:${pad(minutes)}:${pad(seconds)}`
    : `${minutes}:${pad(seconds)}`
}

/** Human-readable byte size for the library and storage disclosures. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const exponent = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)))
  const value = bytes / 1024 ** exponent
  const rounded = exponent === 0 ? String(Math.round(value)) : value.toFixed(1)
  return `${rounded} ${units[exponent]}`
}

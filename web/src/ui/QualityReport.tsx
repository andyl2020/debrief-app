import { formatTimestamp } from '../core/format'
import type { TranscriptQualityReport } from '../core/models'

const STATUS_COPY: Record<TranscriptQualityReport['status'], { label: string; tone: string }> = {
  GOOD: { label: 'Looks complete', tone: 'good' },
  CHECK: { label: 'Worth checking', tone: 'check' },
  ISSUE: { label: 'Possible problem', tone: 'issue' },
}

/**
 * Surfaces the analyzer's verdict. Its job is narrow and worth stating plainly:
 * it detects *structural* problems - missing chunks, broken timestamps,
 * suspicious truncation - not wording mistakes.
 */
export function QualityReportCard({ report }: { report: TranscriptQualityReport }) {
  const status = STATUS_COPY[report.status]
  const warnings = report.warningsText.split('\n').filter((line) => line.trim().length > 0)

  return (
    <details className={`quality quality--${status.tone}`}>
      <summary>
        <span className={`pill pill--${status.tone}`}>{status.label}</span>
        <span className="muted">
          {report.wordCount} words · {report.speakerCount} speaker
          {report.speakerCount === 1 ? '' : 's'} · {Math.round(report.wordsPerMinute)} words/min
          {report.warningCount > 0
            ? ` · ${report.warningCount} warning${report.warningCount === 1 ? '' : 's'}`
            : ''}
        </span>
      </summary>

      <p>{report.recommendation}</p>

      {warnings.length > 0 && (
        <ul className="quality__warnings">
          {warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}

      <p className="muted">
        Transcript covers{' '}
        {report.transcriptStartMs !== null ? formatTimestamp(report.transcriptStartMs) : '—'} to{' '}
        {report.transcriptEndMs !== null ? formatTimestamp(report.transcriptEndMs) : '—'} of{' '}
        {formatTimestamp(report.audioDurationMs)} · {report.provider} · {report.uploadMode} upload
      </p>
    </details>
  )
}

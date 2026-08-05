import { describe, expect, it } from 'vitest'
import { analyzeTranscriptQuality } from '../src/core/transcription/quality'
import { segment, word } from './helpers'

/**
 * Ported from
 * `app/src/test/java/com/andyluu/debrief/transcription/TranscriptQualityAnalyzerTest.kt`.
 */
describe('TranscriptQualityAnalyzer', () => {
  it('goodAssemblyAiOriginalTranscriptHasNoWarnings', () => {
    const segments = [
      segment(1_000, 5_000, 'Speaker A', 'Hey good to meet you at run club today', 0),
      segment(6_000, 10_000, 'Speaker B', 'Yeah the route around the seawall was solid', 1),
    ]
    const words = segments.flatMap((current, segmentIndex) =>
      current.text.split(' ').map((text, index) => {
        const startMs = segmentIndex * 6_000 + 1_000 + index * 400
        return word(text, startMs, startMs + 250, current.speakerId)
      }),
    )

    const report = analyzeTranscriptQuality({
      recordingId: 'recording',
      provider: 'assemblyai',
      uploadQuality: 'ORIGINAL',
      audioDurationMs: 12_000,
      segments,
      words,
    })

    expect(report.status).toBe('GOOD')
    expect(report.warningCount).toBe(0)
  })

  it('largeTimelineGapIsFlaggedAsPossibleIssue', () => {
    const segments = [
      segment(60_000, 62_000, 'Speaker A', 'Beginning', 0),
      segment(8 * 60_000 + 42_000, 8 * 60_000 + 45_000, 'Speaker B', 'Before the gap', 1),
      segment(18 * 60_000 + 50_000, 18 * 60_000 + 55_000, 'Speaker B', 'After the gap', 2),
    ]

    const report = analyzeTranscriptQuality({
      recordingId: 'recording',
      provider: 'assemblyai',
      uploadQuality: 'ORIGINAL',
      audioDurationMs: 19 * 60_000 + 9_000,
      segments,
      words: segments.map((current) =>
        word(current.text, current.startMs, current.startMs + 250, current.speakerId),
      ),
    })

    // A ten-minute hole in the timeline is treated as evidence that a chunk of
    // the recording never made it into the transcript.
    expect(report.status).toBe('ISSUE')
    expect(report.warningsText).toContain('Large timestamp gap')
    expect(report.warningsText).toContain('8:45-18:50')
  })

  it('missingWordTimingIsCheckNotCrash', () => {
    const report = analyzeTranscriptQuality({
      recordingId: 'recording',
      provider: 'deepgram',
      uploadQuality: 'BALANCED',
      audioDurationMs: 20_000,
      segments: [segment(0, 10_000, 'Speaker A', 'A transcript without words', 0)],
      words: [],
    })

    expect(report.status).toBe('CHECK')
    expect(report.warningsText).toContain('word-level timing')
  })
})

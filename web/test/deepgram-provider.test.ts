import { describe, expect, it } from 'vitest'
import { DeepgramProvider } from '../src/core/transcription/deepgram'
import { TranscriptionError } from '../src/core/errors'

/** Ported from `app/src/test/java/com/andyluu/debrief/transcription/DeepgramProviderTest.kt`. */
describe('DeepgramProvider', () => {
  it('requestsLatestBatchDiarizerInsteadOfDeprecatedFlag', () => {
    const url = new DeepgramProvider().requestUrl(['Debrief'])

    expect(url.searchParams.get('diarize_model')).toBe('latest')
    expect(url.searchParams.get('diarize')).toBeNull()
    expect(url.searchParams.get('model')).toBe('nova-3')
    expect(url.searchParams.get('keyterm')).toBe('Debrief')
  })

  it('parsesUtterancesAndWordTimestamps', () => {
    const payload = JSON.stringify({
      results: {
        channels: [
          {
            alternatives: [
              {
                words: [
                  { word: 'hello', punctuated_word: 'Hello', start: 0.1, end: 0.5, speaker: 0 },
                  { word: 'there', punctuated_word: 'there.', start: 0.6, end: 1.0, speaker: 0 },
                ],
              },
            ],
          },
        ],
        utterances: [
          { start: 0.1, end: 1.0, speaker: 0, transcript: 'Hello there.', words: [] },
        ],
      },
    })

    const result = new DeepgramProvider().parse('recording', payload)

    expect(result.segments).toHaveLength(1)
    expect(result.segments[0]!.speakerId).toBe('Speaker A')
    expect(result.segments[0]!.startMs).toBe(100)
    expect(result.words).toHaveLength(2)
    expect(result.words.at(-1)!.text).toBe('there.')
    expect(result.words.at(-1)!.endMs).toBe(1_000)
  })

  it('rejectsEmptySpeechResponse', () => {
    expect(() =>
      new DeepgramProvider().parse('recording', '{"results":{"utterances":[],"channels":[]}}'),
    ).toThrow(TranscriptionError)
  })

  it('channelWordsFillSectionsMissingFromUtteranceList', () => {
    // Deepgram's convenience utterance list can silently omit a recognised
    // stretch. Rebuilding segments from the complete channel word stream is what
    // stops that stretch disappearing from the transcript.
    const payload = JSON.stringify({
      results: {
        channels: [
          {
            alternatives: [
              {
                words: [
                  { word: 'start', punctuated_word: 'Start.', start: 1.0, end: 1.4, speaker: 0 },
                  { word: 'missing', punctuated_word: 'Missing', start: 300.0, end: 300.4, speaker: 1 },
                  { word: 'middle', punctuated_word: 'middle.', start: 300.5, end: 301.0, speaker: 1 },
                  { word: 'end', punctuated_word: 'End.', start: 600.0, end: 600.4, speaker: 0 },
                ],
              },
            ],
          },
        ],
        utterances: [
          { start: 1.0, end: 1.4, speaker: 0, transcript: 'Start.' },
          { start: 600.0, end: 600.4, speaker: 0, transcript: 'End.' },
        ],
      },
    })

    const result = new DeepgramProvider().parse('recording', payload)

    expect(result.segments.map((segment) => segment.text)).toEqual([
      'Start.',
      'Missing middle.',
      'End.',
    ])
    expect(result.segments[1]!.startMs).toBe(300_000)
  })
})

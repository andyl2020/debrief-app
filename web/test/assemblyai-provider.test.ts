import { describe, expect, it } from 'vitest'
import { AssemblyAiProvider } from '../src/core/transcription/assemblyai'
import { TranscriptionError } from '../src/core/errors'

/** Ported from `app/src/test/java/com/andyluu/debrief/transcription/AssemblyAiProviderTest.kt`. */
describe('AssemblyAiProvider', () => {
  it('parsesMillisecondsAndSpeakerLabels', () => {
    // Unlike Deepgram, AssemblyAI already reports milliseconds and letter
    // speaker labels, so no unit conversion should be applied.
    const payload = {
      words: [{ text: 'Hello', start: 120, end: 450, speaker: 'A' }],
      utterances: [{ text: 'Hello', start: 120, end: 450, speaker: 'A' }],
    }

    const result = new AssemblyAiProvider().parse('recording', payload)

    expect(result.segments).toHaveLength(1)
    expect(result.segments[0]!.speakerId).toBe('Speaker A')
    expect(result.segments[0]!.startMs).toBe(120)
    expect(result.words).toHaveLength(1)
    expect(result.words[0]!.endMs).toBe(450)
  })

  it('rejects a response with no speech', () => {
    expect(() => new AssemblyAiProvider().parse('recording', { words: [], utterances: [] })).toThrow(
      new TranscriptionError('AssemblyAI returned no speech'),
    )
  })
})

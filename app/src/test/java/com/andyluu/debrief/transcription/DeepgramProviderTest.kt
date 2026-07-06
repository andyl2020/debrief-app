package com.andyluu.debrief.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepgramProviderTest {
    @Test
    fun parsesUtterancesAndWordTimestamps() {
        val payload = """
            {
              "results": {
                "channels": [{"alternatives": [{"words": [
                  {"word":"hello","punctuated_word":"Hello","start":0.1,"end":0.5,"speaker":0},
                  {"word":"there","punctuated_word":"there.","start":0.6,"end":1.0,"speaker":0}
                ]}]}],
                "utterances": [{
                  "start":0.1,"end":1.0,"speaker":0,"transcript":"Hello there.",
                  "words":[]
                }]
              }
            }
        """.trimIndent()

        val result = DeepgramProvider().parse("recording", payload)

        assertEquals(1, result.segments.size)
        assertEquals("Speaker A", result.segments.single().speakerId)
        assertEquals(100, result.segments.single().startMs)
        assertEquals(2, result.words.size)
        assertEquals("there.", result.words.last().text)
        assertEquals(1_000, result.words.last().endMs)
    }

    @Test
    fun rejectsEmptySpeechResponse() {
        val result = runCatching { DeepgramProvider().parse("recording", "{\"results\":{\"utterances\":[],\"channels\":[]}}") }
        assertTrue(result.exceptionOrNull() is TranscriptionException)
    }
}

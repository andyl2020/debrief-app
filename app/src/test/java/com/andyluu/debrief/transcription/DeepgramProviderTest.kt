package com.andyluu.debrief.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepgramProviderTest {
    @Test
    fun requestsLatestBatchDiarizerInsteadOfDeprecatedFlag() {
        val url = DeepgramProvider().requestUrl(listOf("Debrief"))

        assertEquals("latest", url.queryParameter("diarize_model"))
        assertEquals(null, url.queryParameter("diarize"))
        assertEquals("nova-3", url.queryParameter("model"))
        assertEquals("Debrief", url.queryParameter("keyterm"))
    }

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

    @Test
    fun channelWordsFillSectionsMissingFromUtteranceList() {
        val payload = """
            {
              "results": {
                "channels": [{"alternatives": [{"words": [
                  {"word":"start","punctuated_word":"Start.","start":1.0,"end":1.4,"speaker":0},
                  {"word":"missing","punctuated_word":"Missing","start":300.0,"end":300.4,"speaker":1},
                  {"word":"middle","punctuated_word":"middle.","start":300.5,"end":301.0,"speaker":1},
                  {"word":"end","punctuated_word":"End.","start":600.0,"end":600.4,"speaker":0}
                ]}]}],
                "utterances": [
                  {"start":1.0,"end":1.4,"speaker":0,"transcript":"Start."},
                  {"start":600.0,"end":600.4,"speaker":0,"transcript":"End."}
                ]
              }
            }
        """.trimIndent()

        val result = DeepgramProvider().parse("recording", payload)

        assertEquals(listOf("Start.", "Missing middle.", "End."), result.segments.map { it.text })
        assertEquals(300_000, result.segments[1].startMs)
    }
}

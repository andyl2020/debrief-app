package com.andyluu.debrief.transcription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AssemblyAiProviderTest {
    @Test
    fun parsesMillisecondsAndSpeakerLabels() {
        val payload = Json.parseToJsonElement(
            """{
              "words":[{"text":"Hello","start":120,"end":450,"speaker":"A"}],
              "utterances":[{"text":"Hello","start":120,"end":450,"speaker":"A"}]
            }"""
        ).jsonObject

        val result = AssemblyAiProvider().parse("recording", payload)

        assertEquals("Speaker A", result.segments.single().speakerId)
        assertEquals(120, result.segments.single().startMs)
        assertEquals(450, result.words.single().endMs)
    }
}

package com.andyluu.debrief.ui

import com.andyluu.debrief.data.RedactionEntity
import com.andyluu.debrief.data.TranscriptWordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionsTest {
    @Test
    fun masksOnlyWordsInsideRedactionRange() {
        val text = "My phone number is private today."
        val words = listOf(
            word("My", 0, 100),
            word("phone", 100, 200),
            word("number", 200, 300),
            word("is", 300, 400),
            word("private", 400, 500),
            word("today.", 500, 600),
        )
        val redactions = listOf(redaction(100, 300))

        assertEquals("My [redacted] is private today.", redactedTranscriptText(text, words, redactions, 0, 600))
    }

    @Test
    fun fullCardRedactionMasksFromTheFirstWord() {
        val text = "First word must disappear."
        val words = listOf(
            word("First", 0, 100),
            word("word", 100, 200),
            word("must", 200, 300),
            word("disappear.", 300, 400),
        )

        assertEquals(REDACTION_LABEL, redactedTranscriptText(text, words, listOf(redaction(0, 400)), 0, 400))
    }

    @Test
    fun wholeSegmentFallsBackWhenWordTimingIsMissing() {
        assertEquals(
            REDACTION_LABEL,
            redactedTranscriptText("Private sentence", emptyList(), listOf(redaction(0, 1_000)), 0, 1_000),
        )
    }

    @Test
    fun audioMuteUsesSafetyPadding() {
        val redactions = listOf(redaction(1_000, 2_000))

        assertTrue(redactionActiveAt(900, redactions))
        assertTrue(redactionActiveAt(2_100, redactions))
        assertFalse(redactionActiveAt(800, redactions))
        assertFalse(redactionActiveAt(2_200, redactions))
    }

    private fun word(text: String, startMs: Long, endMs: Long) = TranscriptWordEntity(
        recordingId = "recording",
        speakerId = "Speaker A",
        startMs = startMs,
        endMs = endMs,
        text = text,
    )

    private fun redaction(startMs: Long, endMs: Long) = RedactionEntity(
        id = "$startMs-$endMs",
        recordingId = "recording",
        startMs = startMs,
        endMs = endMs,
        text = "private",
    )
}

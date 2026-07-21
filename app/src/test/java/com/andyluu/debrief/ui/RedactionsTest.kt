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

    @Test
    fun secondLongPressedWordExtendsSelectionRange() {
        val words = listOf(word("hello", 0, 100), word("private", 100, 200), word("name", 200, 300))
        val first = RedactionSelection(segmentId = 10, startMs = 200, endMs = 300, text = "name")
        val target = TimedTextRange(textStart = 6, textEnd = 13, startMs = 100, endMs = 200, text = "private")

        val selection = selectionBetweenWords(10, first, target, words)

        assertEquals(100, selection.startMs)
        assertEquals(300, selection.endMs)
        assertEquals("private name", selection.text)
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

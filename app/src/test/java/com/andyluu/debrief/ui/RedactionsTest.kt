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
    fun allWordRedactionsStillLookLikeOneRedactedCard() {
        val text = "One two three four five extra-cleaned-token."
        val words = listOf(
            word("One", 0, 100),
            word("two", 100, 200),
            word("three", 200, 300),
            word("four", 300, 400),
            word("five.", 400, 500),
        )
        val redactions = words.map { redaction(it.startMs, it.endMs) }

        assertEquals(REDACTION_LABEL, redactedTranscriptText(text, words, redactions, 0, 500))
    }

    @Test
    fun removingFifthWordFromCardRedactionKeepsOtherWordsRedacted() {
        val text = "One two three four five six."
        val words = listOf(
            word("One", 0, 100),
            word("two", 100, 200),
            word("three", 200, 300),
            word("four", 300, 400),
            word("five", 400, 500),
            word("six.", 500, 600),
        )
        val fullCardRedaction = listOf(redaction(0, 600))
        val fifth = redactedWordChoices(words, fullCardRedaction, 0, 600).single { it.index == 5 }

        val remaining = redactionRangesAfterRemovingWord(words, fullCardRedaction, 0, 600, fifth)
            .map { redaction(it.startMs, it.endMs) }

        assertEquals("[redacted] five [redacted]", redactedTranscriptText(text, words, remaining, 0, 600))
        assertEquals(listOf(1, 2, 3, 4, 6), redactedWordChoices(words, remaining, 0, 600).map { it.index })
    }

    @Test
    fun redactedWordChoicesIncludeVisibleWordTextForUndoMenu() {
        val words = listOf(
            word("Andy", 0, 100),
            word("Vancouver", 100, 200),
        )

        val choices = redactedWordChoices(words, listOf(redaction(0, 200)), 0, 200)

        assertEquals(listOf("Andy", "Vancouver"), choices.map { it.text })
    }

    @Test
    fun fullCardRedactionChoicesIncludeTheFirstWord() {
        val words = listOf(
            word("First", 0, 100),
            word("second", 100, 200),
            word("third", 200, 300),
        )

        assertEquals(listOf(1, 2, 3), redactedWordChoices(words, listOf(redaction(0, 300)), 0, 300).map { it.index })
    }

    @Test
    fun wholeSegmentFallsBackWhenWordTimingIsMissing() {
        assertEquals(
            REDACTION_LABEL,
            redactedTranscriptText("Private sentence", emptyList(), listOf(redaction(0, 1_000)), 0, 1_000),
        )
    }

    @Test
    fun audioMuteUsesLargerLeadingPrivacyBuffer() {
        val redactions = listOf(redaction(1_000, 2_000))

        assertTrue(redactionActiveAt(250, redactions))
        assertTrue(redactionActiveAt(2_250, redactions))
        assertFalse(redactionActiveAt(249, redactions))
        assertFalse(redactionActiveAt(2_251, redactions))
    }

    @Test
    fun leadingBufferClampsAtRecordingStart() {
        val ranges = redactionMuteRanges(listOf(redaction(200, 500)))

        assertEquals(listOf(RedactionMuteRange(0, 750)), ranges)
        assertEquals(0f, redactionPlaybackVolumeAt(0, listOf(redaction(200, 500))))
    }

    @Test
    fun overlappingPaddedMuteRangesAreMerged() {
        val ranges = redactionMuteRanges(
            listOf(
                redaction(1_000, 1_200),
                redaction(1_700, 2_000),
            )
        )

        assertEquals(listOf(RedactionMuteRange(250, 2_250)), ranges)
        assertEquals(0f, redactionPlaybackVolumeForRanges(1_450, ranges))
    }

    @Test
    fun playbackVolumeIsFullOutsidePrivacyBuffer() {
        val redactions = listOf(redaction(1_000, 2_000))

        assertEquals(1f, redactionPlaybackVolumeAt(249, redactions))
        assertEquals(1f, redactionPlaybackVolumeAt(2_251, redactions))
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

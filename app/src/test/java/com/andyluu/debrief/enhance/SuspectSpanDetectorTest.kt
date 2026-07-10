package com.andyluu.debrief.enhance

import com.andyluu.debrief.data.TranscriptWordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuspectSpanDetectorTest {
    @Test
    fun detectsStrongLowConfidenceWordWithPadding() {
        val spans = SuspectSpanDetector.detect(
            recordingId = "rec",
            durationMs = 60_000,
            words = listOf(
                word("hello", 10_000, 10_300, 0.99),
                word("gran", 12_000, 12_200, 0.41),
                word("ville", 12_300, 12_500, 0.70),
                word("station", 12_600, 13_000, 0.90),
            ),
        )

        assertEquals(1, spans.size)
        assertEquals(7_000, spans.first().startMs)
        assertEquals(17_200, spans.first().endMs)
        assertEquals(1, spans.first().flaggedWordCount)
        assertTrue(spans.first().originalText.contains("gran"))
    }

    @Test
    fun ignoresSingleWeakWordAboveStrongThreshold() {
        val spans = SuspectSpanDetector.detect(
            recordingId = "rec",
            durationMs = 60_000,
            words = listOf(word("maybe", 1_000, 1_200, 0.55)),
        )

        assertTrue(spans.isEmpty())
    }

    @Test
    fun keepsThreeFlaggedWordsEvenWithoutStrongWord() {
        val spans = SuspectSpanDetector.detect(
            recordingId = "rec",
            durationMs = 60_000,
            words = listOf(
                word("one", 1_000, 1_200, 0.58),
                word("two", 1_400, 1_600, 0.57),
                word("three", 1_700, 1_900, 0.56),
            ),
        )

        assertEquals(1, spans.size)
        assertEquals(3, spans.first().flaggedWordCount)
    }

    private fun word(text: String, startMs: Long, endMs: Long, confidence: Double) = TranscriptWordEntity(
        recordingId = "rec",
        speakerId = "Speaker A",
        startMs = startMs,
        endMs = endMs,
        text = text,
        confidence = confidence,
    )
}

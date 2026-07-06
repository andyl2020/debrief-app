package com.andyluu.debrief.ai

import com.andyluu.debrief.data.TranscriptSegmentEntity
import com.andyluu.debrief.data.TranscriptWordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetDetectorTest {
    @Test
    fun silenceAtThresholdCreatesCompleteChronologicalSets() {
        val segments = listOf(
            segment("Speaker A", 0, 10_000, "First conversation"),
            segment("Speaker B", 190_000, 200_000, "Second conversation"),
            segment("Speaker C", 400_000, 410_000, "Third conversation"),
        )
        val words = segments.map { TranscriptWordEntity(recordingId = "r", speakerId = it.speakerId, startMs = it.startMs, endMs = it.endMs, text = it.text) }

        val sets = SetDetector.detect("r", 420_000, segments, words, gapMinutes = 3)

        assertEquals(3, sets.size)
        assertEquals(listOf(0L, 190_000L, 400_000L), sets.map { it.startMs })
        assertEquals(420_000L, sets.last().endMs)
        assertTrue(sets.zipWithNext().all { (a, b) -> a.endMs + 1 == b.startMs })
        assertEquals("Speaker B", sets[1].speakerIds)
    }

    @Test
    fun gapsBelowThresholdRemainOneSet() {
        val segments = listOf(
            segment("Speaker A", 5_000, 10_000, "Hello"),
            segment("Speaker B", 100_000, 110_000, "Still here"),
        )

        val sets = SetDetector.detect("r", 120_000, segments, emptyList(), gapMinutes = 3)

        assertEquals(1, sets.size)
        assertEquals(5_000, sets.single().startMs)
        assertEquals(setOf("Speaker A", "Speaker B"), sets.single().speakerIds.split('|').toSet())
    }

    private fun segment(speaker: String, start: Long, end: Long, text: String) =
        TranscriptSegmentEntity(recordingId = "r", speakerId = speaker, startMs = start, endMs = end, text = text)
}


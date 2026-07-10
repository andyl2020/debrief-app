package com.andyluu.debrief.transcription

import com.andyluu.debrief.data.TranscriptQualityStatus
import com.andyluu.debrief.data.TranscriptSegmentEntity
import com.andyluu.debrief.data.TranscriptWordEntity
import com.andyluu.debrief.data.TranscriptionAudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptQualityAnalyzerTest {
    @Test
    fun goodAssemblyAiOriginalTranscriptHasNoWarnings() {
        val segments = listOf(
            segment(0, 1_000, 5_000, "Speaker A", "Hey good to meet you at run club today"),
            segment(1, 6_000, 10_000, "Speaker B", "Yeah the route around the seawall was solid"),
        )
        val words = segments.flatMapIndexed { segmentIndex, segment ->
            segment.text.split(" ").mapIndexed { index, word ->
                word(segmentIndex * 6_000L + 1_000L + index * 400L, word, segment.speakerId)
            }
        }

        val report = TranscriptQualityAnalyzer.analyze(
            recordingId = "recording",
            provider = "assemblyai",
            uploadQuality = TranscriptionAudioQuality.ORIGINAL,
            audioDurationMs = 12_000,
            segments = segments,
            words = words,
        )

        assertEquals(TranscriptQualityStatus.GOOD, report.status)
        assertEquals(0, report.warningCount)
    }

    @Test
    fun largeTimelineGapIsFlaggedAsPossibleIssue() {
        val segments = listOf(
            segment(0, 60_000, 62_000, "Speaker A", "Beginning"),
            segment(1, 8 * 60_000L + 42_000L, 8 * 60_000L + 45_000L, "Speaker B", "Before the gap"),
            segment(2, 18 * 60_000L + 50_000L, 18 * 60_000L + 55_000L, "Speaker B", "After the gap"),
        )

        val report = TranscriptQualityAnalyzer.analyze(
            recordingId = "recording",
            provider = "assemblyai",
            uploadQuality = TranscriptionAudioQuality.ORIGINAL,
            audioDurationMs = 19 * 60_000L + 9_000L,
            segments = segments,
            words = segments.map { word(it.startMs, it.text, it.speakerId) },
        )

        assertEquals(TranscriptQualityStatus.ISSUE, report.status)
        assertTrue(report.warningsText.contains("Large timestamp gap"))
        assertTrue(report.warningsText.contains("8:45-18:50"))
    }

    @Test
    fun missingWordTimingIsCheckNotCrash() {
        val report = TranscriptQualityAnalyzer.analyze(
            recordingId = "recording",
            provider = "deepgram",
            uploadQuality = TranscriptionAudioQuality.BALANCED,
            audioDurationMs = 20_000,
            segments = listOf(segment(0, 0, 10_000, "Speaker A", "A transcript without words")),
            words = emptyList(),
        )

        assertEquals(TranscriptQualityStatus.CHECK, report.status)
        assertTrue(report.warningsText.contains("word-level timing"))
    }

    private fun segment(
        id: Long,
        startMs: Long,
        endMs: Long,
        speaker: String,
        text: String,
    ) = TranscriptSegmentEntity(id = id, recordingId = "recording", speakerId = speaker, startMs = startMs, endMs = endMs, text = text)

    private fun word(startMs: Long, text: String, speaker: String) =
        TranscriptWordEntity(recordingId = "recording", speakerId = speaker, startMs = startMs, endMs = startMs + 250, text = text)
}

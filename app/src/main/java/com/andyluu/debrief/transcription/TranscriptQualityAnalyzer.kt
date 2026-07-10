package com.andyluu.debrief.transcription

import com.andyluu.debrief.data.TranscriptQualityReportEntity
import com.andyluu.debrief.data.TranscriptQualityStatus
import com.andyluu.debrief.data.TranscriptSegmentEntity
import com.andyluu.debrief.data.TranscriptWordEntity
import com.andyluu.debrief.data.TranscriptionAudioQuality
import kotlin.math.roundToInt

object TranscriptQualityAnalyzer {
    private const val LARGE_GAP_MS = 5 * 60 * 1000L
    private const val ISSUE_GAP_MS = 10 * 60 * 1000L
    private const val EARLY_END_MS = 5 * 60 * 1000L
    private const val LATE_START_MS = 2 * 60 * 1000L
    private const val MIN_DENSITY_DURATION_MS = 5 * 60 * 1000L
    private const val LOW_WORDS_PER_MINUTE = 35.0
    private const val LOW_CONFIDENCE_MEAN = 0.65
    private const val LOW_CONFIDENCE_SHARE = 0.20

    fun analyze(
        recordingId: String,
        provider: String,
        uploadQuality: TranscriptionAudioQuality,
        audioDurationMs: Long,
        segments: List<TranscriptSegmentEntity>,
        words: List<TranscriptWordEntity>,
    ): TranscriptQualityReportEntity {
        val warnings = mutableListOf<Warning>()
        val sortedSegments = segments.sortedBy { it.startMs }
        val sortedWords = words.sortedBy { it.startMs }
        val timeline = when {
            sortedSegments.isNotEmpty() -> sortedSegments.map { TimelineItem(it.startMs, it.endMs) }
            else -> sortedWords.map { TimelineItem(it.startMs, it.endMs) }
        }.filter { it.startMs >= 0 && it.endMs >= 0 }

        val transcriptStartMs = timeline.minOfOrNull { it.startMs }
        val transcriptEndMs = timeline.maxOfOrNull { it.endMs }
        val segmentWordEstimate = sortedSegments.sumOf { it.text.split(Regex("\\s+")).count(String::isNotBlank) }
        val wordCount = sortedWords.size.takeIf { it > 0 } ?: segmentWordEstimate
        val speakerCount = (sortedSegments.map { it.speakerId } + sortedWords.map { it.speakerId })
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .size
        val minutes = audioDurationMs.coerceAtLeast(1) / 60_000.0
        val wordsPerMinute = wordCount / minutes

        if (sortedSegments.isEmpty() && sortedWords.isEmpty()) {
            warnings += Warning(
                severity = TranscriptQualityStatus.ISSUE,
                message = "Transcript is empty.",
            )
        }

        val invalidSegmentCount = sortedSegments.count { it.startMs < 0 || it.endMs <= it.startMs }
        val invalidWordCount = sortedWords.count { it.startMs < 0 || it.endMs <= it.startMs }
        if (invalidSegmentCount + invalidWordCount > 0) {
            warnings += Warning(
                severity = TranscriptQualityStatus.ISSUE,
                message = "${invalidSegmentCount + invalidWordCount} transcript items have missing or invalid timestamps.",
            )
        }

        val nonMonotonic = sortedSegments.zipWithNext().firstOrNull { (previous, current) ->
            current.startMs < previous.startMs || current.startMs < previous.endMs - 1_000
        }
        if (nonMonotonic != null) {
            warnings += Warning(
                severity = TranscriptQualityStatus.ISSUE,
                message = "Transcript timestamps are out of order near ${formatTimestamp(nonMonotonic.second.startMs)}.",
            )
        }

        timeline.sortedBy { it.startMs }.zipWithNext().forEach { (previous, current) ->
            val gap = current.startMs - previous.endMs
            if (gap >= LARGE_GAP_MS) {
                warnings += Warning(
                    severity = if (gap >= ISSUE_GAP_MS) TranscriptQualityStatus.ISSUE else TranscriptQualityStatus.CHECK,
                    message = "Large timestamp gap: ${formatTimestamp(previous.endMs)}-${formatTimestamp(current.startMs)}. This may be silence, noise, or a missing transcript section.",
                )
            }
        }

        if (audioDurationMs > 0 && transcriptStartMs != null && transcriptStartMs >= LATE_START_MS) {
            warnings += Warning(
                severity = TranscriptQualityStatus.CHECK,
                message = "Transcript starts at ${formatTimestamp(transcriptStartMs)}, not near the beginning of the audio.",
            )
        }

        if (audioDurationMs > 0 && transcriptEndMs != null) {
            val missingTail = audioDurationMs - transcriptEndMs
            if (missingTail >= EARLY_END_MS && transcriptEndMs < (audioDurationMs * 0.85).roundToInt()) {
                warnings += Warning(
                    severity = TranscriptQualityStatus.ISSUE,
                    message = "Transcript ends at ${formatTimestamp(transcriptEndMs)} while audio ends at ${formatTimestamp(audioDurationMs)}.",
                )
            }
        }

        if (sortedWords.isEmpty() && sortedSegments.isNotEmpty()) {
            warnings += Warning(
                severity = TranscriptQualityStatus.CHECK,
                message = "Provider returned transcript segments without word-level timing.",
            )
        } else if (segmentWordEstimate > 0 && sortedWords.size < segmentWordEstimate * 0.5) {
            warnings += Warning(
                severity = TranscriptQualityStatus.CHECK,
                message = "Word-level timing coverage is lower than expected.",
            )
        }

        if (audioDurationMs >= MIN_DENSITY_DURATION_MS && wordsPerMinute < LOW_WORDS_PER_MINUTE) {
            warnings += Warning(
                severity = TranscriptQualityStatus.CHECK,
                message = "Transcript density is low (${wordsPerMinute.roundToInt()} words/min). This may be silence, noise, or missed speech.",
            )
        }

        if (audioDurationMs >= MIN_DENSITY_DURATION_MS && speakerCount <= 1) {
            warnings += Warning(
                severity = TranscriptQualityStatus.CHECK,
                message = "Only one speaker label was detected. Check diarization if this was a multi-person conversation.",
            )
        }

        val confidences = sortedWords.mapNotNull { it.confidence }
        if (confidences.size >= 20) {
            val mean = confidences.average()
            val lowShare = confidences.count { it < LOW_CONFIDENCE_MEAN } / confidences.size.toDouble()
            if (mean < LOW_CONFIDENCE_MEAN || lowShare >= LOW_CONFIDENCE_SHARE) {
                warnings += Warning(
                    severity = TranscriptQualityStatus.CHECK,
                    message = "Provider confidence is low in ${percent(lowShare)} of timed words.",
                )
            }
        }

        val status = when {
            warnings.any { it.severity == TranscriptQualityStatus.ISSUE } -> TranscriptQualityStatus.ISSUE
            warnings.isNotEmpty() -> TranscriptQualityStatus.CHECK
            else -> TranscriptQualityStatus.GOOD
        }

        return TranscriptQualityReportEntity(
            recordingId = recordingId,
            status = status,
            provider = provider,
            uploadMode = uploadQuality.storedValue,
            audioDurationMs = audioDurationMs,
            transcriptStartMs = transcriptStartMs,
            transcriptEndMs = transcriptEndMs,
            segmentCount = sortedSegments.size,
            wordCount = wordCount,
            speakerCount = speakerCount,
            wordsPerMinute = wordsPerMinute,
            warningCount = warnings.size,
            warningsText = warnings.joinToString("\n") { it.message },
            recommendation = recommendation(status),
        )
    }

    private fun recommendation(status: TranscriptQualityStatus): String = when (status) {
        TranscriptQualityStatus.GOOD -> "No integrity issues found. This does not guarantee perfect wording, but no missing chunks, broken timestamps, or suspicious truncation were detected."
        TranscriptQualityStatus.CHECK -> "Open the warning locations and spot-check the transcript before relying on it."
        TranscriptQualityStatus.ISSUE -> "Retranscribe with AssemblyAI + Original upload, or inspect the warning locations manually before relying on this transcript."
    }

    private fun formatTimestamp(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    private fun percent(value: Double): String = "${(value * 100).roundToInt()}%"

    private data class TimelineItem(val startMs: Long, val endMs: Long)
    private data class Warning(val severity: TranscriptQualityStatus, val message: String)
}

package com.andyluu.debrief.enhance

import com.andyluu.debrief.data.SuspectSpanEntity
import com.andyluu.debrief.data.TranscriptWordEntity
import kotlin.math.max
import kotlin.math.min

object SuspectSpanDetector {
    fun detect(
        recordingId: String,
        words: List<TranscriptWordEntity>,
        durationMs: Long,
    ): List<SuspectSpanEntity> {
        val flagged = words
            .filter { (it.confidence ?: 1.0) < EnhanceConstants.FLAG_CONFIDENCE }
            .sortedBy { it.startMs }
        if (flagged.isEmpty()) return emptyList()

        val groups = mutableListOf<List<TranscriptWordEntity>>()
        var current = mutableListOf<TranscriptWordEntity>()
        flagged.forEach { word ->
            val previous = current.lastOrNull()
            val shouldSplit = previous != null && word.startMs - previous.endMs > EnhanceConstants.MERGE_GAP_MS
            if (shouldSplit) {
                groups += current
                current = mutableListOf()
            }
            current += word
        }
        if (current.isNotEmpty()) groups += current

        val endOfRecording = max(durationMs, words.maxOfOrNull { it.endMs } ?: 0L)
        return groups.mapNotNull { group ->
            val confidences = group.map { it.confidence ?: 1.0 }
            val minConfidence = confidences.minOrNull() ?: 1.0
            if (group.size < 3 && minConfidence >= EnhanceConstants.STRONG_CONFIDENCE) return@mapNotNull null

            val rawStart = group.first().startMs
            val rawEnd = group.last().endMs
            val paddedStart = max(0L, rawStart - EnhanceConstants.PAD_MS)
            val paddedEnd = min(endOfRecording, rawEnd + EnhanceConstants.PAD_MS)
            val capped = capSpan(paddedStart, paddedEnd, rawStart, rawEnd, endOfRecording)
            val nearbyWords = words.filter { it.startMs <= capped.second && it.endMs >= capped.first }
            val meanConfidence = confidences.average()
            val score = ((rawEnd - rawStart).coerceAtLeast(1L) / 1000.0) * (1.0 - meanConfidence)
            SuspectSpanEntity(
                id = recordingId + "-suspect-" + rawStart + "-" + rawEnd,
                recordingId = recordingId,
                startMs = capped.first,
                endMs = capped.second,
                originalText = nearbyWords.joinToString(" ") { it.text }.trim().ifBlank {
                    group.joinToString(" ") { it.text }.trim()
                },
                flaggedWordCount = group.size,
                minConfidence = minConfidence,
                meanConfidence = meanConfidence,
                score = score,
            )
        }.sortedWith(compareByDescending<SuspectSpanEntity> { it.score }.thenBy { it.startMs })
    }

    private fun capSpan(
        startMs: Long,
        endMs: Long,
        rawStartMs: Long,
        rawEndMs: Long,
        durationMs: Long,
    ): Pair<Long, Long> {
        if (endMs - startMs <= EnhanceConstants.MAX_SPAN_MS) return startMs to endMs
        val center = (rawStartMs + rawEndMs) / 2
        val half = EnhanceConstants.MAX_SPAN_MS / 2
        val start = (center - half).coerceAtLeast(0L)
        val end = (start + EnhanceConstants.MAX_SPAN_MS).coerceAtMost(durationMs)
        return (end - EnhanceConstants.MAX_SPAN_MS).coerceAtLeast(0L) to end
    }
}

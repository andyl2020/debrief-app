package com.andyluu.debrief.ai

import com.andyluu.debrief.data.ConversationSetEntity
import com.andyluu.debrief.data.TranscriptSegmentEntity
import com.andyluu.debrief.data.TranscriptWordEntity

object SetDetector {
    fun detect(
        recordingId: String,
        durationMs: Long,
        segments: List<TranscriptSegmentEntity>,
        words: List<TranscriptWordEntity>,
        gapMinutes: Int,
    ): List<ConversationSetEntity> {
        val points = if (words.isNotEmpty()) {
            words.sortedBy { it.startMs }.map { TimePoint(it.startMs, it.endMs) }
        } else {
            segments.sortedBy { it.startMs }.map { TimePoint(it.startMs, it.endMs) }
        }
        if (points.isEmpty()) return emptyList()
        val thresholdMs = gapMinutes.coerceIn(1, 10) * 60_000L
        val starts = buildList {
            add(points.first().startMs.coerceAtLeast(0))
            points.zipWithNext().forEach { (previous, next) ->
                if (next.startMs - previous.endMs >= thresholdMs) add(next.startMs)
            }
        }.distinct().sorted()
        val recordingEnd = maxOf(durationMs, points.last().endMs, starts.last() + 1)
        return starts.mapIndexed { index, start ->
            val end = (starts.getOrNull(index + 1)?.minus(1) ?: recordingEnd).coerceAtLeast(start + 1)
            val speakers = segments.asSequence()
                .filter { it.endMs >= start && it.startMs <= end }
                .map { it.speakerId }
                .distinct()
                .toList()
            ConversationSetEntity(
                id = "$recordingId-set-${index + 1}-$start",
                recordingId = recordingId,
                orderIndex = index,
                startMs = start,
                endMs = end,
                title = "Set ${index + 1}",
                speakerIds = speakers.joinToString("|"),
            )
        }
    }

    private data class TimePoint(val startMs: Long, val endMs: Long)
}


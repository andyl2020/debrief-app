package com.andyluu.debrief.transcription

import android.content.Context
import com.andyluu.debrief.data.TranscriptSegmentEntity
import com.andyluu.debrief.data.TranscriptWordEntity
import java.io.File

data class TranscriptionResult(
    val segments: List<TranscriptSegmentEntity>,
    val words: List<TranscriptWordEntity>,
)

interface TranscriptionProvider {
    suspend fun transcribe(
        context: Context,
        recordingId: String,
        audioFile: File,
        mimeType: String,
        apiKey: String,
        keyterms: List<String>,
    ): TranscriptionResult
}

class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal fun segmentsFromWords(
    recordingId: String,
    words: List<TranscriptWordEntity>,
): List<TranscriptSegmentEntity> {
    if (words.isEmpty()) return emptyList()
    val segments = mutableListOf<TranscriptSegmentEntity>()
    var group = mutableListOf<TranscriptWordEntity>()

    fun flush() {
        if (group.isEmpty()) return
        segments += TranscriptSegmentEntity(
            recordingId = recordingId,
            speakerId = group.first().speakerId,
            startMs = group.first().startMs,
            endMs = group.last().endMs,
            text = group.joinToString(" ") { it.text }.trim(),
        )
        group = mutableListOf()
    }

    words.sortedBy { it.startMs }.forEach { word ->
        val previous = group.lastOrNull()
        val split = previous != null && (
            previous.speakerId != word.speakerId ||
                word.startMs - previous.endMs > 1_500 ||
                group.size >= 40 ||
                word.endMs - group.first().startMs > 30_000
            )
        if (split) flush()
        group += word
        if (word.text.endsWith('.') || word.text.endsWith('?') || word.text.endsWith('!')) flush()
    }
    flush()
    return segments.filter { it.text.isNotBlank() }
}

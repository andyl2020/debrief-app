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

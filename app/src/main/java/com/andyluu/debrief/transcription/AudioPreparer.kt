package com.andyluu.debrief.transcription

import android.content.Context
import android.net.Uri
import com.andyluu.debrief.data.TranscriptionAudioQuality
import java.io.File
import java.io.IOException
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import okio.source

internal class PreparedAudio(
    val body: RequestBody,
    val mimeType: String,
    private val temporaryFile: File? = null,
) {
    fun cleanup() {
        temporaryFile?.delete()
    }
}

internal object AudioPreparer {
    suspend fun prepare(
        context: Context,
        source: Uri,
        recordingId: String,
        sourceMimeType: String?,
        sourceSizeBytes: Long,
        quality: TranscriptionAudioQuality,
    ): PreparedAudio = when (quality) {
        TranscriptionAudioQuality.ORIGINAL -> {
            val mimeType = sourceMimeType?.takeIf(String::isNotBlank)
                ?: context.contentResolver.getType(source)
                ?: "application/octet-stream"
            PreparedAudio(
                body = ContentUriRequestBody(context, source, mimeType.toMediaTypeOrNull(), sourceSizeBytes),
                mimeType = mimeType,
            )
        }
        TranscriptionAudioQuality.BALANCED,
        TranscriptionAudioQuality.DATA_SAVER,
        -> {
            val file = AudioCompressor.compress(
                context = context,
                source = source,
                recordingId = recordingId,
                bitrate = requireNotNull(quality.bitrate),
            )
            PreparedAudio(
                body = file.asRequestBody("audio/mp4".toMediaTypeOrNull()),
                mimeType = "audio/mp4",
                temporaryFile = file,
            )
        }
    }
}

private class ContentUriRequestBody(
    context: Context,
    private val source: Uri,
    private val mediaType: MediaType?,
    private val sizeBytes: Long,
) : RequestBody() {
    private val resolver = context.applicationContext.contentResolver

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = sizeBytes.takeIf { it > 0 } ?: -1

    override fun writeTo(sink: BufferedSink) {
        val stream = resolver.openInputStream(source)
            ?: throw IOException("The original recording could not be opened")
        stream.source().use { sink.writeAll(it) }
    }
}

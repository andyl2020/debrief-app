package com.andyluu.debrief.transcription

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object AudioCompressor {
    suspend fun compress(context: Context, source: Uri, recordingId: String): File {
        val output = File(context.cacheDir, "debrief_${recordingId.take(16)}.m4a")
        if (output.exists()) output.delete()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder().setBitrate(64_000).build()
                    ).build()
                val monoMixer = ChannelMixingAudioProcessor().apply {
                    putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1))
                    putChannelMixingMatrix(ChannelMixingMatrix.create(2, 1))
                }
                val transformer = Transformer.Builder(context)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    }).build()
                continuation.invokeOnCancellation { transformer.cancel() }
                val edited = EditedMediaItem.Builder(MediaItem.fromUri(source))
                    .setRemoveVideo(true)
                    .setEffects(Effects(listOf(monoMixer), emptyList()))
                    .build()
                transformer.start(edited, output.absolutePath)
            }
        }
        if (!output.exists() || output.length() == 0L) throw TranscriptionException("Audio compression produced an empty file")
        return output
    }
}

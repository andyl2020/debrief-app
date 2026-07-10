package com.andyluu.debrief.enhance

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object AudioClipper {
    suspend fun clip(
        context: Context,
        source: Uri,
        recordingId: String,
        spanId: String,
        startMs: Long,
        endMs: Long,
    ): File {
        require(endMs > startMs) { "Clip end must be after start" }
        require(endMs - startMs <= EnhanceConstants.SELECTION_CHUNK_MS + 10_000L) { "Clip is too long" }
        val output = File(context.cacheDir, "debrief_${recordingId.take(12)}_${spanId.take(24)}.m4a")
        if (output.exists()) output.delete()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder().setBitrate(64_000).build()
                    )
                    .build()
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
                    })
                    .build()
                continuation.invokeOnCancellation { transformer.cancel() }
                val item = MediaItem.Builder()
                    .setUri(source)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs.coerceAtLeast(0L))
                            .setEndPositionMs(endMs)
                            .build()
                    )
                    .build()
                transformer.start(EditedMediaItem.Builder(item).setRemoveVideo(true).build(), output.absolutePath)
            }
        }
        if (!output.exists() || output.length() == 0L) throw AiEnhanceException("Audio clip extraction produced an empty file")
        return output
    }
}

package com.andyluu.debrief.share

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class)
class ShareAudioExporter(private val context: Context) {
    suspend fun export(
        source: Uri,
        output: File,
        startMs: Long,
        endMs: Long,
        muteRanges: List<ShareMuteRange>,
    ): File {
        require(endMs > startMs) { "The selected set has invalid boundaries." }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        if (muteRanges.isEmpty()) {
            runCatching { copySamples(source, output, startMs, endMs) }
                .onFailure { output.delete() }
                .getOrNull()
        }
        if (!output.exists() || output.length() == 0L) {
            transform(source, output, startMs, endMs, muteRanges)
        }
        probe(output, endMs - startMs)
        return output
    }

    private suspend fun copySamples(source: Uri, output: File, startMs: Long, endMs: Long) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, source, null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("The source has no audio track.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            val maximum = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
            } else {
                256 * 1024
            }
            val buffer = ByteBuffer.allocateDirect(maximum)
            val info = MediaCodec.BufferInfo()
            val startUs = startMs * 1_000L
            val endUs = endMs * 1_000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var wrote = false
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0L || sampleTime >= endUs) break
                if (sampleTime < startUs) {
                    extractor.advance()
                    continue
                }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, sampleTime - startUs, extractor.sampleFlags)
                muxer.writeSampleData(outputTrack, buffer, info)
                wrote = true
                extractor.advance()
            }
            check(wrote) { "The selected set did not contain readable audio samples." }
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private suspend fun transform(
        source: Uri,
        output: File,
        startMs: Long,
        endMs: Long,
        muteRanges: List<ShareMuteRange>,
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val processor = muteRanges.takeIf { it.isNotEmpty() }?.let(::RedactionSilenceAudioProcessor)
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(128_000).build())
                .build()
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (processor != null && processor.mutedFrames <= 0L) {
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Redaction silence verification failed."))
                        } else if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
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
            val mediaItem = MediaItem.Builder()
                .setUri(source)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs.coerceAtLeast(0L))
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()
            val edited = EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true)
                .apply {
                    if (processor != null) setEffects(Effects(listOf(processor), emptyList<Effect>()))
                }
                .build()
            transformer.start(edited, output.absolutePath)
        }
    }

    private fun probe(file: File, expectedDurationMs: Long) {
        check(file.isFile && file.length() > 0L) { "Share audio export is empty." }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") {
                "Share audio export has no audio track."
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            check(duration > 0L && kotlin.math.abs(duration - expectedDurationMs) <= 2_000L) {
                "Share audio export duration could not be verified."
            }
        } finally {
            retriever.release()
        }
    }
}

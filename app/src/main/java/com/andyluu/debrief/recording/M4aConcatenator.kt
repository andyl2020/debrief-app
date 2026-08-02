package com.andyluu.debrief.recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

internal object M4aConcatenator {
    fun isReadableAudio(file: File): Boolean = runCatching {
        MediaExtractor().useExtractor { extractor ->
            extractor.setDataSource(file.absolutePath)
            hasAudioSample(extractor) && file.length() > 0
        }
    }.getOrDefault(false)

    fun isReadableAudio(fileDescriptor: FileDescriptor): Boolean = runCatching {
        MediaExtractor().useExtractor { extractor ->
            extractor.setDataSource(fileDescriptor)
            hasAudioSample(extractor)
        }
    }.getOrDefault(false)

    fun concatenate(
        inputs: List<File>,
        output: File,
        onPartWritten: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        require(inputs.isNotEmpty()) { "No recording parts were available." }
        output.parentFile?.mkdirs()
        if (output.exists()) check(output.delete()) { "Could not replace the temporary joined recording." }

        try {
            concatenate(
                inputs = inputs,
                createMuxer = { MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) },
                onPartWritten = onPartWritten,
            )
        } catch (error: Throwable) {
            runCatching { output.delete() }
            throw error
        }
    }

    fun concatenate(
        inputs: List<File>,
        output: FileDescriptor,
        onPartWritten: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        require(inputs.isNotEmpty()) { "No recording parts were available." }
        concatenate(
            inputs = inputs,
            createMuxer = { MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) },
            onPartWritten = onPartWritten,
        )
    }

    private fun concatenate(
        inputs: List<File>,
        createMuxer: () -> MediaMuxer,
        onPartWritten: (completed: Int, total: Int) -> Unit,
    ) {
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var writesCompleted = false
        try {
            var outputTrack = -1
            var timelineOffsetUs = 0L
            val buffer = ByteBuffer.allocateDirect(512 * 1024)
            val info = MediaCodec.BufferInfo()

            inputs.forEachIndexed { index, input ->
                MediaExtractor().useExtractor { extractor ->
                    extractor.setDataSource(input.absolutePath)
                    val inputTrack = findAudioTrack(extractor)
                    require(inputTrack >= 0) { "A recording part did not contain audio." }
                    extractor.selectTrack(inputTrack)
                    val format = extractor.getTrackFormat(inputTrack)
                    if (muxer == null) {
                        muxer = createMuxer()
                        outputTrack = muxer!!.addTrack(format)
                        muxer!!.start()
                        muxerStarted = true
                    }

                    val sampleDurationUs = estimatedSampleDurationUs(format)
                    var lastTimestampUs = -1L
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val sourceTimeUs = extractor.sampleTime.coerceAtLeast(0)
                        info.set(
                            0,
                            size,
                            timelineOffsetUs + sourceTimeUs,
                            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                                MediaCodec.BUFFER_FLAG_KEY_FRAME
                            } else {
                                0
                            },
                        )
                        muxer!!.writeSampleData(outputTrack, buffer, info)
                        lastTimestampUs = sourceTimeUs
                        extractor.advance()
                    }
                    if (lastTimestampUs >= 0) {
                        timelineOffsetUs += lastTimestampUs + sampleDurationUs
                    }
                }
                onPartWritten(index + 1, inputs.size)
            }
            require(muxer != null) { "No readable audio was found in the recording parts." }
            writesCompleted = true
        } finally {
            try {
                if (muxerStarted) {
                    if (writesCompleted) muxer?.stop() else runCatching { muxer?.stop() }
                }
            } finally {
                runCatching { muxer?.release() }
            }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: -1

    private fun hasAudioSample(extractor: MediaExtractor): Boolean {
        val audioTrack = findAudioTrack(extractor)
        if (audioTrack < 0) return false
        extractor.selectTrack(audioTrack)
        return extractor.sampleTime >= 0
    }

    private fun estimatedSampleDurationUs(format: MediaFormat): Long {
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            48_000
        }
        return (1_024_000_000L / sampleRate.coerceAtLeast(1)).coerceAtLeast(1)
    }

    private inline fun <T> MediaExtractor.useExtractor(block: (MediaExtractor) -> T): T {
        try {
            return block(this)
        } finally {
            release()
        }
    }
}

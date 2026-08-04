package com.andyluu.debrief.share

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
internal class RedactionSilenceAudioProcessor(
    ranges: List<ShareMuteRange>,
) : BaseAudioProcessor() {
    private val ranges = ranges.sortedBy(ShareMuteRange::startMs)
    private var framesProcessed = 0L
    private var rangeIndex = 0
    var mutedFrames: Long = 0L
        private set

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        val completeBytes = inputBuffer.remaining() - inputBuffer.remaining() % bytesPerFrame
        val output = replaceOutputBuffer(completeBytes)
        repeat(completeBytes / bytesPerFrame) {
            val timestampMs = framesProcessed * 1_000L / inputAudioFormat.sampleRate
            while (rangeIndex < ranges.size && timestampMs >= ranges[rangeIndex].endMs) rangeIndex += 1
            val muted = rangeIndex < ranges.size && timestampMs >= ranges[rangeIndex].startMs
            repeat(bytesPerFrame) {
                val value = inputBuffer.get()
                output.put(if (muted) 0 else value)
            }
            if (muted) mutedFrames += 1
            framesProcessed += 1
        }
        output.flip()
    }

    override fun onFlush() {
        framesProcessed = 0L
        rangeIndex = 0
        mutedFrames = 0L
    }

    fun expectedMutedFrames(sampleRate: Int): Long = ranges.sumOf { range ->
        ((range.endMs - range.startMs).coerceAtLeast(0L) * sampleRate / 1_000L)
    }
}

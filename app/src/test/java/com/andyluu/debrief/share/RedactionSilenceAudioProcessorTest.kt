package com.andyluu.debrief.share

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RedactionSilenceAudioProcessorTest {
    @Test
    fun replacesOnlyProtectedPcmFramesWithSilence() {
        val processor = RedactionSilenceAudioProcessor(
            listOf(ShareMuteRange(2, 5), ShareMuteRange(7, 9)),
        )
        processor.configure(AudioProcessor.AudioFormat(1_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder())
        (1..10).forEach { input.putShort(it.toShort()) }
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val samples = ShortArray(10) { output.short }

        assertArrayEquals(
            shortArrayOf(1, 2, 0, 0, 0, 6, 7, 0, 0, 10),
            samples,
        )
        assertEquals(5L, processor.mutedFrames)
    }

    @Test
    fun flushRestartsTimelineForTransformerRetries() {
        val processor = RedactionSilenceAudioProcessor(listOf(ShareMuteRange(0, 1)))
        processor.configure(AudioProcessor.AudioFormat(1_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.queueInput(singleSample(42))
        assertEquals(0, processor.output.order(ByteOrder.nativeOrder()).short.toInt())

        processor.flush()
        processor.queueInput(singleSample(42))

        assertEquals(0, processor.output.order(ByteOrder.nativeOrder()).short.toInt())
        assertEquals(2L, processor.mutedFrames)
    }

    private fun singleSample(value: Short): ByteBuffer =
        ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).apply {
            putShort(value)
            flip()
        }
}

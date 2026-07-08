package com.andyluu.debrief.transcription

import com.andyluu.debrief.data.TranscriptionAudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioQualityTest {
    @Test
    fun unknownOrMissingPreferenceDefaultsToOriginal() {
        assertEquals(TranscriptionAudioQuality.ORIGINAL, TranscriptionAudioQuality.fromStoredValue(null))
        assertEquals(TranscriptionAudioQuality.ORIGINAL, TranscriptionAudioQuality.fromStoredValue("future-value"))
    }

    @Test
    fun compressedModesUseDocumentedBitrates() {
        assertNull(TranscriptionAudioQuality.ORIGINAL.bitrate)
        assertEquals(96_000, TranscriptionAudioQuality.BALANCED.bitrate)
        assertEquals(64_000, TranscriptionAudioQuality.DATA_SAVER.bitrate)
    }
}

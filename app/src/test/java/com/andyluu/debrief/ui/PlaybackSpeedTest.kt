package com.andyluu.debrief.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedTest {
    @Test
    fun speedOptionsIncludeNormalAndEveryRequestedRate() {
        assertEquals(listOf(1f, 1.2f, 1.5f, 2f, 3f, 4f), PLAYBACK_SPEED_OPTIONS)
    }

    @Test
    fun speedLabelsAreCompactAndUnambiguous() {
        assertEquals(listOf("1×", "1.2×", "1.5×", "2×", "3×", "4×"), PLAYBACK_SPEED_OPTIONS.map(::formatPlaybackSpeed))
    }
}

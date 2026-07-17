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
    @Test
    fun skipIntervalsDefaultToFiveAndCycleThroughRequestedOptions() {
        assertEquals(5_000L, DEFAULT_PLAYBACK_SKIP_MS)
        assertEquals(listOf(5_000L, 1_000L, 3_000L), PLAYBACK_SKIP_INTERVALS_MS)
        assertEquals(1_000L, nextPlaybackSkipInterval(5_000L))
        assertEquals(3_000L, nextPlaybackSkipInterval(1_000L))
        assertEquals(5_000L, nextPlaybackSkipInterval(3_000L))
    }

    @Test
    fun skipIntervalLabelsUseSeconds() {
        assertEquals("1 second", formatPlaybackSkipInterval(1_000L))
        assertEquals("3 seconds", formatPlaybackSkipInterval(3_000L))
        assertEquals("5 seconds", formatPlaybackSkipInterval(5_000L))
    }
}

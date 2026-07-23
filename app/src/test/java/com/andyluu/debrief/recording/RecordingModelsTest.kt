package com.andyluu.debrief.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RecordingModelsTest {
    @Test
    fun runningDurationAddsOnlyActiveElapsedTime() {
        val state = RecordingState(
            phase = RecordingPhase.RECORDING,
            elapsedBeforeRunningMs = 12_000,
            runningSinceElapsedMs = 100_000,
        )

        assertEquals(17_500, state.elapsedMs(nowElapsedMs = 105_500))
    }

    @Test
    fun pausedDurationDoesNotKeepAdvancing() {
        val state = RecordingState(
            phase = RecordingPhase.PAUSED,
            elapsedBeforeRunningMs = 42_000,
            runningSinceElapsedMs = 100_000,
            pauseReason = RecordingPauseReason.CALL,
        )

        assertEquals(42_000, state.elapsedMs(nowElapsedMs = 900_000))
        assertTrue(state.isSessionActive)
        assertFalse(state.canStart)
    }

    @Test
    fun recordingNamesAreStableAndSortable() {
        val name = RecordingNames.newDisplayName(LocalDateTime.of(2026, 7, 23, 14, 5, 9))

        assertEquals("Debrief 2026-07-23 14.05.09.m4a", name)
        assertEquals("rec-123-part-0007.m4a", RecordingNames.partFileName("rec-123", 7))
    }
}

package com.andyluu.debrief.recording

import android.media.AudioDeviceInfo
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

    @Test
    fun recordingNamesAreSanitizedAndKeepTheRealAudioExtension() {
        assertEquals(
            "Run club - Granville.m4a",
            RecordingNames.normalizeDisplayName(""" Run club / Granville.mp3 """),
        )
        assertEquals(
            "Conversation.v2.wav",
            RecordingNames.normalizeDisplayName("Conversation.v2", "wav"),
        )
        assertEquals(
            "Conversation",
            RecordingNames.editableBase("Conversation.m4a"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyRecordingNameIsRejected() {
        RecordingNames.normalizeDisplayName("   ")
    }

    @Test
    fun externalMicrophoneTypesExcludeSystemAndTelephonySources() {
        assertTrue(isExternalMicrophoneType(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertTrue(isExternalMicrophoneType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertTrue(isExternalMicrophoneType(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertFalse(isExternalMicrophoneType(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertFalse(isExternalMicrophoneType(AudioDeviceInfo.TYPE_TELEPHONY))
        assertFalse(isExternalMicrophoneType(AudioDeviceInfo.TYPE_REMOTE_SUBMIX))
    }

    @Test
    fun usbMicrophonesWinDeterministicallyAndLabelsStayClear() {
        assertTrue(
            externalMicrophonePriority(AudioDeviceInfo.TYPE_USB_HEADSET) >
                externalMicrophonePriority(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        )
        assertEquals(
            RecordingInputRoute.EXTERNAL,
            microphoneRouteForDeviceType(AudioDeviceInfo.TYPE_USB_DEVICE),
        )
        assertEquals(
            "USB PnP Microphone",
            microphoneDisplayName(RecordingInputRoute.EXTERNAL, "USB PnP Microphone"),
        )
        assertEquals(
            "Phone microphone",
            microphoneDisplayName(RecordingInputRoute.INTERNAL, "Built-in Mic"),
        )
    }
}

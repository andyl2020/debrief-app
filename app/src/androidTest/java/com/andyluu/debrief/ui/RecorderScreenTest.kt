package com.andyluu.debrief.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.andyluu.debrief.recording.RecordingPauseReason
import com.andyluu.debrief.recording.RecordingPhase
import com.andyluu.debrief.recording.RecordingState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RecorderScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun idleRecorderStartsFromOneLargeButton() {
        var started = false
        compose.setContent {
            DebriefTheme {
                RecorderContent(
                    state = RecordingState(),
                    folderLinked = true,
                    onStart = { started = true },
                    onPause = {},
                    onResume = {},
                    onStop = {},
                    onRetry = {},
                    onPickFolder = {},
                    onClearMessage = {},
                    onOpenLibrary = {},
                    onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Start recording").performClick()
        assertTrue(started)
        compose.onNodeWithText("Offline • 128 kbps AAC • screen-off recording supported").assertIsDisplayed()
    }

    @Test
    fun activeRecorderShowsPauseAndStopControls() {
        compose.setContent {
            DebriefTheme {
                RecorderContent(
                    state = RecordingState(
                        phase = RecordingPhase.RECORDING,
                        sessionId = "rec-1",
                        displayName = "Debrief test.m4a",
                        elapsedBeforeRunningMs = 65_000,
                    ),
                    folderLinked = true,
                    onStart = {},
                    onPause = {},
                    onResume = {},
                    onStop = {},
                    onRetry = {},
                    onPickFolder = {},
                    onClearMessage = {},
                    onOpenLibrary = {},
                    onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Pause recording").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Stop and save recording").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Debrief test.m4a").assertIsDisplayed()
    }

    @Test
    fun callPauseExplainsAutomaticResume() {
        compose.setContent {
            DebriefTheme {
                RecorderContent(
                    state = RecordingState(
                        phase = RecordingPhase.PAUSED,
                        sessionId = "rec-1",
                        pauseReason = RecordingPauseReason.CALL,
                        statusMessage = "Paused for a call. Debrief will resume automatically.",
                    ),
                    folderLinked = true,
                    onStart = {},
                    onPause = {},
                    onResume = {},
                    onStop = {},
                    onRetry = {},
                    onPickFolder = {},
                    onClearMessage = {},
                    onOpenLibrary = {},
                    onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithText("Paused for call").assertIsDisplayed()
        compose.onNodeWithText("Paused for a call. Debrief will resume automatically.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop and save recording").assertIsDisplayed()
    }
}

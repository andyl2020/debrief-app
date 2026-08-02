package com.andyluu.debrief.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.andyluu.debrief.recording.RecordingPauseReason
import com.andyluu.debrief.recording.RecordingInputRoute
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
        compose.onNodeWithContentDescription("Using phone microphone").assertIsDisplayed()
        compose.onNodeWithText("Offline • 128 kbps AAC • screen-off recording supported").assertIsDisplayed()
    }

    @Test
    fun externalMicrophoneRouteIsClearlyIdentified() {
        compose.setContent {
            DebriefTheme {
                RecorderContent(
                    state = RecordingState(
                        phase = RecordingPhase.RECORDING,
                        sessionId = "rec-usb",
                        inputRoute = RecordingInputRoute.EXTERNAL,
                        inputDeviceName = "USB PnP Microphone",
                    ),
                    folderLinked = true,
                    onStart = {}, onPause = {}, onResume = {}, onStop = {},
                    onRetry = {}, onPickFolder = {}, onClearMessage = {},
                    onOpenLibrary = {}, onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Using external microphone: USB PnP Microphone").assertIsDisplayed()
        compose.onNodeWithText("External · USB PnP Microphone").assertIsDisplayed()
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
        compose.onNodeWithContentDescription("Delete recording").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Stop and save recording").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Debrief test").assertIsDisplayed()
        compose.onNodeWithText(".m4a").assertIsDisplayed()
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

    @Test
    fun recordingNameCanBeEditedWhileRecording() {
        var editedName = ""
        compose.setContent {
            DebriefTheme {
                RecorderContent(
                    state = RecordingState(
                        phase = RecordingPhase.RECORDING,
                        sessionId = "rec-1",
                        displayName = "Original.m4a",
                    ),
                    folderLinked = true,
                    recordingName = "Original",
                    onStart = {},
                    onPause = {},
                    onResume = {},
                    onStop = {},
                    onRetry = {},
                    onPickFolder = {},
                    onClearMessage = {},
                    onOpenLibrary = {},
                    onOpenSettings = {},
                    onNameChange = { editedName = it },
                )
            }
        }

        compose.onNodeWithText("Original").performTextReplacement("Networking")
        compose.runOnIdle { assertTrue(editedName == "Networking") }
    }

    @Test
    fun tappingDeleteRequiresConfirmationBeforeDiscarding() {
        var discarded = false
        compose.setContent {
            DebriefTheme {
                RecorderContent(
                    state = RecordingState(
                        phase = RecordingPhase.RECORDING,
                        sessionId = "rec-delete",
                        displayName = "Private conversation.m4a",
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
                    onDelete = { discarded = true },
                )
            }
        }

        compose.onNodeWithContentDescription("Delete recording").performClick()
        compose.onNodeWithText("Delete this recording?").assertIsDisplayed()
        compose.runOnIdle { assertTrue(!discarded) }
        compose.onNodeWithText("Delete recording").performClick()
        compose.runOnIdle { assertTrue(discarded) }
    }

    @Test
    fun holdingDeleteDiscardsWithoutOpeningConfirmation() {
        var discarded = false
        compose.setContent {
            DebriefTheme {
                RecorderContent(
                    state = RecordingState(
                        phase = RecordingPhase.PAUSED,
                        sessionId = "rec-hold-delete",
                        displayName = "Discard me.m4a",
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
                    onDelete = { discarded = true },
                )
            }
        }

        compose.onNodeWithContentDescription("Delete recording").performTouchInput { longClick() }
        compose.runOnIdle { assertTrue(discarded) }
        assertTrue(compose.onAllNodesWithText("Delete this recording?").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun longRecordingSaveShowsDeterminateProgress() {
        compose.setContent {
            DebriefTheme {
                RecorderContent(
                    state = RecordingState(
                        phase = RecordingPhase.FINALIZING,
                        sessionId = "rec-long",
                        displayName = "Six hour recording.m4a",
                        saveProgress = 0.42f,
                        statusMessage = "Saving audio part 17 of 40...",
                    ),
                    folderLinked = true,
                    onStart = {}, onPause = {}, onResume = {}, onStop = {},
                    onRetry = {}, onPickFolder = {}, onClearMessage = {},
                    onOpenLibrary = {}, onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Save progress 42 percent").assertIsDisplayed()
        compose.onNodeWithText("42%").assertIsDisplayed()
        compose.onNodeWithText("Saving audio part 17 of 40...").assertIsDisplayed()
    }
}

package com.andyluu.debrief.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.andyluu.debrief.data.RecordingEntity
import com.andyluu.debrief.data.RecordingStatus
import org.junit.Rule
import org.junit.Test

class LibrarySelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longPressEntersSelectionAndChecksRecording() {
        val recording = RecordingEntity(
            id = "recording-1",
            documentUri = "content://recording-1",
            displayName = "field.mp3",
            mimeType = "audio/mpeg",
            sizeBytes = 1_000,
            lastModified = 1,
            status = RecordingStatus.NEW,
        )
        compose.setContent {
            DebriefTheme {
                var selectionMode by remember { mutableStateOf(false) }
                var selected by remember { mutableStateOf(false) }
                RecordingCard(
                    recording = recording,
                    selectionMode = selectionMode,
                    selected = selected,
                    onOpen = {},
                    onToggleSelection = {
                        selectionMode = true
                        selected = !selected
                    },
                )
            }
        }

        compose.onNodeWithText("field.mp3").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Select field.mp3").assertIsOn()
    }
}

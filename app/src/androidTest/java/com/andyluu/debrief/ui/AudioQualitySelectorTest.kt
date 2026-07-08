package com.andyluu.debrief.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.andyluu.debrief.data.TranscriptionAudioQuality
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AudioQualitySelectorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun originalIsAvailableAndBalancedSelectionIsImmediate() {
        var selectedValue = TranscriptionAudioQuality.ORIGINAL
        compose.setContent {
            DebriefTheme {
                var selected by remember { mutableStateOf(TranscriptionAudioQuality.ORIGINAL) }
                AudioQualitySelector(selected) {
                    selected = it
                    selectedValue = it
                }
            }
        }

        compose.onNodeWithText("Original · best accuracy").assertIsSelected()
        compose.onNodeWithText("Balanced · 96 kbps mono").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(TranscriptionAudioQuality.BALANCED, selectedValue) }
    }
}

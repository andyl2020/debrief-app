package com.andyluu.debrief.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReviewToolbarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun enhanceActionIsBetweenReloadAndCommentAndInvokesCallback() {
        var enhanceClicks = 0
        compose.setContent {
            DebriefTheme {
                ReviewToolbarActions(
                    enhanceRunning = false,
                    suspectCount = 3,
                    onReload = {},
                    onRunEnhance = { enhanceClicks++ },
                    onAddComment = {},
                    onOpenChapters = {},
                )
            }
        }

        val reloadX = compose.onNodeWithContentDescription("Reload transcript").fetchSemanticsNode().boundsInRoot.left
        val aiNode = compose.onNodeWithContentDescription("Run AI Enhance").assertIsEnabled()
        val aiX = aiNode.fetchSemanticsNode().boundsInRoot.left
        val commentX = compose.onNodeWithContentDescription("Add comment").fetchSemanticsNode().boundsInRoot.left
        val chaptersX = compose.onNodeWithContentDescription("Open chapters").fetchSemanticsNode().boundsInRoot.left

        assertTrue(reloadX < aiX)
        assertTrue(aiX < commentX)
        assertTrue(commentX < chaptersX)
        aiNode.performClick()
        compose.runOnIdle { assertEquals(1, enhanceClicks) }
    }

    @Test
    fun enhanceActionIsDisabledWhileRunning() {
        compose.setContent {
            DebriefTheme {
                ReviewToolbarActions(
                    enhanceRunning = true,
                    suspectCount = 0,
                    onReload = {},
                    onRunEnhance = {},
                    onAddComment = {},
                    onOpenChapters = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Run AI Enhance").assertIsNotEnabled()
    }

    @Test
    fun playbackSpeedMenuOffersEveryRateAndSelectsImmediately() {
        var selectedSpeed = 1f
        compose.setContent {
            DebriefTheme {
                PlaybackSpeedControl(selectedSpeed) { selectedSpeed = it }
            }
        }

        compose.onNodeWithContentDescription("Playback speed, 1×").performClick()
        listOf("1.2×", "1.5×", "2×", "3×", "4×").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("3×").performClick()

        compose.runOnIdle { assertEquals(3f, selectedSpeed) }
    }
}

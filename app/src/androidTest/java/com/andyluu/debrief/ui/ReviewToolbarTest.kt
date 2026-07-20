package com.andyluu.debrief.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
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
                    showEnhance = true,
                    enhanceRunning = false,
                    suspectCount = 3,
                    onReload = {},
                    onRunEnhance = { enhanceClicks++ },
                    onAddComment = {},
                    onCommentLongPress = {},
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
                    showEnhance = true,
                    enhanceRunning = true,
                    suspectCount = 0,
                    onReload = {},
                    onRunEnhance = {},
                    onAddComment = {},
                    onCommentLongPress = {},
                    onOpenChapters = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Run AI Enhance").assertIsNotEnabled()
    }

    @Test
    fun enhanceActionIsHiddenWhenAdvancedToggleIsOff() {
        compose.setContent {
            DebriefTheme {
                ReviewToolbarActions(
                    showEnhance = false,
                    enhanceRunning = false,
                    suspectCount = 3,
                    onReload = {},
                    onRunEnhance = {},
                    onAddComment = {},
                    onCommentLongPress = {},
                    onOpenChapters = {},
                )
            }
        }

        assertEquals(0, compose.onAllNodesWithContentDescription("Run AI Enhance").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Reload transcript").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add comment").assertIsDisplayed()
    }

    @Test
    fun commentButtonTapAndLongPressAreSeparateActions() {
        var commentClicks = 0
        var setControlOpens = 0
        compose.setContent {
            DebriefTheme {
                ReviewToolbarActions(
                    showEnhance = false,
                    enhanceRunning = false,
                    suspectCount = 0,
                    onReload = {},
                    onRunEnhance = {},
                    onAddComment = { commentClicks++ },
                    onCommentLongPress = { setControlOpens++ },
                    onOpenChapters = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Add comment").performClick()
        compose.onNodeWithContentDescription("Add comment").performTouchInput { longClick() }

        compose.runOnIdle {
            assertEquals(1, commentClicks)
            assertEquals(1, setControlOpens)
        }
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

    @Test
    fun playbackSkipButtonsTapAndLongPress() {
        var backClicks = 0
        var forwardClicks = 0
        var interval = DEFAULT_PLAYBACK_SKIP_MS
        compose.setContent {
            DebriefTheme {
                Row {
                    PlaybackSkipButton(
                        forward = false,
                        intervalMs = interval,
                        onClick = { backClicks++ },
                        onLongClick = { interval = nextPlaybackSkipInterval(interval) },
                    )
                    PlaybackSkipButton(
                        forward = true,
                        intervalMs = interval,
                        onClick = { forwardClicks++ },
                        onLongClick = { interval = nextPlaybackSkipInterval(interval) },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Skip back 3 seconds. Long press to change skip interval.").performClick()
        compose.onNodeWithContentDescription("Skip forward 3 seconds. Long press to change skip interval.").performClick()
        compose.onNodeWithContentDescription("Skip forward 3 seconds. Long press to change skip interval.").performTouchInput { longClick() }

        compose.runOnIdle {
            assertEquals(1, backClicks)
            assertEquals(1, forwardClicks)
            assertEquals(1_000L, interval)
        }
    }
}

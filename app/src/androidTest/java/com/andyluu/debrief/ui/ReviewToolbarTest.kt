package com.andyluu.debrief.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReviewToolbarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aiActionIsBetweenReloadAndCommentAndInvokesCallback() {
        var aiClicks = 0
        compose.setContent {
            DebriefTheme {
                ReviewToolbarActions(
                    aiRunning = false,
                    onReload = {},
                    onRunAi = { aiClicks++ },
                    onAddComment = {},
                )
            }
        }

        val reloadX = compose.onNodeWithContentDescription("Reload transcript").fetchSemanticsNode().boundsInRoot.left
        val aiNode = compose.onNodeWithContentDescription("Run AI pass").assertIsEnabled()
        val aiX = aiNode.fetchSemanticsNode().boundsInRoot.left
        val commentX = compose.onNodeWithContentDescription("Add comment").fetchSemanticsNode().boundsInRoot.left

        assertTrue(reloadX < aiX)
        assertTrue(aiX < commentX)
        aiNode.performClick()
        compose.runOnIdle { assertEquals(1, aiClicks) }
    }

    @Test
    fun aiActionIsDisabledWhileRunning() {
        compose.setContent {
            DebriefTheme {
                ReviewToolbarActions(true, {}, {}, {})
            }
        }

        compose.onNodeWithContentDescription("Run AI pass").assertIsNotEnabled()
    }
}

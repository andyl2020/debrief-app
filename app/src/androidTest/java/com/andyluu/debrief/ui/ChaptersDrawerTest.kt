package com.andyluu.debrief.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.andyluu.debrief.data.CommentEntity
import com.andyluu.debrief.data.ConversationSetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChaptersDrawerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun setsAndCommentsAppearInPlaybackOrderAndJumpToTimestamp() {
        var soughtTo = -1L
        val set = ConversationSetEntity(
            id = "set-1",
            recordingId = "recording",
            orderIndex = 0,
            startMs = 2_000,
            endMs = 8_000,
            title = "Introductions",
            summary = "The speakers introduce themselves.",
        )
        val comment = CommentEntity(
            id = "comment-1",
            recordingId = "recording",
            timestampMs = 5_000,
            text = "Follow up on this",
        )

        compose.setContent {
            DebriefTheme {
                ChaptersDrawerContent(
                    recording = null,
                    ai = null,
                    sets = listOf(set),
                    comments = listOf(comment),
                    suggestions = emptyList(),
                    aliases = emptyMap(),
                    positionMs = 3_000,
                    onClose = {},
                    onSkip = {},
                    onUndoRename = {},
                    onConfirmSuggestion = {},
                    onSeek = { soughtTo = it },
                    onEditSet = {},
                    onDeleteSet = {},
                    onMerge = {},
                    onSplit = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Chapters").assertIsDisplayed()
        val setY = compose.onNodeWithText("Introductions").fetchSemanticsNode().boundsInRoot.top
        val commentY = compose.onNodeWithText("Follow up on this").fetchSemanticsNode().boundsInRoot.top
        assertTrue(setY < commentY)

        compose.onNodeWithContentDescription("Go to comment at 0:05").performClick()
        compose.runOnIdle { assertEquals(5_000L, soughtTo) }
    }

    @Test
    fun setRowsExposeEditAndDeleteActions() {
        var edited: ConversationSetEntity? = null
        var deleted: ConversationSetEntity? = null
        val set = ConversationSetEntity(
            id = "set-1",
            recordingId = "recording",
            orderIndex = 0,
            startMs = 2_000,
            endMs = 8_000,
            title = "Introductions",
        )

        compose.setContent {
            DebriefTheme {
                ChaptersDrawerContent(
                    recording = null,
                    ai = null,
                    sets = listOf(set),
                    comments = emptyList(),
                    suggestions = emptyList(),
                    aliases = emptyMap(),
                    positionMs = 3_000,
                    onClose = {},
                    onSkip = {},
                    onUndoRename = {},
                    onConfirmSuggestion = {},
                    onSeek = {},
                    onEditSet = { edited = it },
                    onDeleteSet = { deleted = it },
                    onMerge = {},
                    onSplit = { _, _ -> },
                )
            }
        }

        compose.onNodeWithContentDescription("Edit set Introductions").performClick()
        compose.onNodeWithContentDescription("Delete set Introductions").performClick()

        compose.runOnIdle {
            assertEquals(set, edited)
            assertEquals(set, deleted)
        }
    }
}

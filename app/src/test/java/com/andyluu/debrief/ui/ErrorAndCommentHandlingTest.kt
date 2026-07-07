package com.andyluu.debrief.ui

import com.andyluu.debrief.data.CommentEntity
import com.andyluu.debrief.data.TranscriptSegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ErrorAndCommentHandlingTest {
    @Test
    fun securityErrorsGiveRecoveryInstructionsWithoutInternalDetails() {
        val message = userMessage("Couldn't save.", SecurityException("content://private/path"))

        assertTrue(message.contains("Re-link"))
        assertTrue(!message.contains("content://"))
    }

    @Test
    fun ioErrorsKeepTheActionContext() {
        val message = userMessage("Couldn't add the comment.", IOException("disk failed"))

        assertTrue(message.startsWith("Couldn't add the comment."))
        assertTrue(message.contains("try again"))
    }

    @Test
    fun commentsInTranscriptGapsRemainVisibleWithPreviousSegment() {
        val segments = listOf(
            segment(1_000, 5_000),
            segment(20_000, 25_000),
        )
        val gapComment = comment(12_000, "Between transcript lines")
        val leading = comment(500, "Before speech")

        assertEquals(listOf(leading), leadingComments(listOf(leading, gapComment), segments))
        assertEquals(listOf(gapComment), commentsForSegment(listOf(leading, gapComment), segments, 0, 30_000))
    }

    @Test
    fun commentsAfterFinalTranscriptLineRemainVisible() {
        val segments = listOf(segment(1_000, 5_000))
        val trailing = comment(18_000, "After the final line")

        assertEquals(listOf(trailing), commentsForSegment(listOf(trailing), segments, 0, 20_000))
    }

    private fun segment(start: Long, end: Long) = TranscriptSegmentEntity(
        recordingId = "recording",
        speakerId = "Speaker A",
        startMs = start,
        endMs = end,
        text = "Transcript",
    )

    private fun comment(timestamp: Long, text: String) = CommentEntity(
        id = text,
        recordingId = "recording",
        timestampMs = timestamp,
        text = text,
    )
}


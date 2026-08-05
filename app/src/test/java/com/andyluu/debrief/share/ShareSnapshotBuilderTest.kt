package com.andyluu.debrief.share

import com.andyluu.debrief.data.CommentEntity
import com.andyluu.debrief.data.ConversationSetEntity
import com.andyluu.debrief.data.RedactionEntity
import com.andyluu.debrief.data.TranscriptSegmentEntity
import com.andyluu.debrief.data.TranscriptWordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareSnapshotBuilderTest {
    private val set = ConversationSetEntity(
        id = "set-1",
        recordingId = "recording-1",
        orderIndex = 0,
        startMs = 1_000,
        endMs = 3_000,
        title = "Conversation",
    )

    @Test
    fun snapshotIncludesOnlyInBoundaryWordsAndPermanentlyMasksRedactions() {
        val segment = TranscriptSegmentEntity(
            recordingId = "recording-1",
            speakerId = "S1",
            startMs = 500,
            endMs = 3_500,
            text = "outside-before hello private friend outside-after",
        )
        val words = listOf(
            word("outside-before", 600, 800),
            word("hello", 1_100, 1_400),
            word("private", 1_500, 1_800),
            word("friend", 1_900, 2_200),
            word("outside-after", 3_100, 3_300),
        )

        val payload = buildShareMetadata(
            set = set,
            setTitle = "Conversation",
            segments = listOf(segment),
            words = words,
            comments = emptyList(),
            redactions = listOf(RedactionEntity("redaction-1", "recording-1", 1_450, 1_850, "private")),
            aliases = mapOf("S1" to "Andy"),
        )

        assertEquals(1, payload.segments.size)
        assertEquals("Andy", payload.segments.single().speaker)
        assertEquals("hello [redacted] friend", payload.segments.single().text)
        assertEquals(100L, payload.segments.single().startMs)
        assertEquals(1_200L, payload.segments.single().endMs)
        assertFalse(payload.segments.single().text.contains("private"))
        assertFalse(payload.segments.single().text.contains("outside"))
    }

    @Test
    fun commentsUseStartInclusiveEndExclusiveBoundaries() {
        val payload = buildShareMetadata(
            set = set,
            setTitle = "Conversation",
            segments = listOf(segment(1_000, 3_000, "inside")),
            words = emptyList(),
            comments = listOf(
                comment("before", 999),
                comment("at-start", 1_000),
                comment("before-end", 2_999),
                comment("at-end", 3_000),
            ),
            redactions = emptyList(),
            aliases = emptyMap(),
        )

        assertEquals(listOf("at-start", "before-end"), payload.comments.map { it.text })
        assertEquals(listOf(0L, 1_999L), payload.comments.map { it.timestampMs })
    }

    @Test
    fun partialUntimedSegmentIsDroppedInsteadOfLeakingOutsideText() {
        val payload = buildShareMetadata(
            set = set,
            setTitle = "Conversation",
            segments = listOf(segment(500, 1_500, "outside words and inside words")),
            words = emptyList(),
            comments = emptyList(),
            redactions = emptyList(),
            aliases = emptyMap(),
        )

        assertTrue(payload.segments.isEmpty())
    }

    @Test
    fun untimedSegmentWithAnyStoredRedactionFailsClosed() {
        val payload = buildShareMetadata(
            set = set,
            setTitle = "Conversation",
            segments = listOf(segment(1_100, 1_900, "call me at 555 0100")),
            words = emptyList(),
            comments = emptyList(),
            redactions = listOf(RedactionEntity("redaction-1", "recording-1", 1_400, 1_600, "555")),
            aliases = emptyMap(),
        )

        assertEquals("[redacted]", payload.segments.single().text)
        assertFalse(payload.segments.single().text.contains("555"))
    }

    private fun word(text: String, startMs: Long, endMs: Long) = TranscriptWordEntity(
        recordingId = "recording-1",
        speakerId = "S1",
        startMs = startMs,
        endMs = endMs,
        text = text,
    )

    private fun segment(startMs: Long, endMs: Long, text: String) = TranscriptSegmentEntity(
        recordingId = "recording-1",
        speakerId = "S1",
        startMs = startMs,
        endMs = endMs,
        text = text,
    )

    private fun comment(text: String, timestampMs: Long) = CommentEntity(
        id = text,
        recordingId = "recording-1",
        timestampMs = timestampMs,
        text = text,
    )
}

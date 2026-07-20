package com.andyluu.debrief.ui

import com.andyluu.debrief.data.CommentEntity
import com.andyluu.debrief.data.ConversationSetEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterEntriesTest {
    @Test
    fun entriesCombineSetsAndCommentsChronologically() {
        val entries = buildChapterEntries(
            sets = listOf(set("later", 10_000, ""), set("first", 1_000, "Opening")),
            comments = listOf(comment("middle", 5_000), comment("same-time", 1_000)),
        )

        assertEquals(listOf("set:first", "comment:same-time", "comment:middle", "set:later"), entries.map { it.id })
        assertEquals("Set 1", entries.last().title)
    }

    @Test
    fun timestampInputSupportsReviewTimeFormats() {
        assertEquals(83_000L, parseTimestampInput("1:23"))
        assertEquals(3_723_000L, parseTimestampInput("1:02:03"))
        assertEquals(45_000L, parseTimestampInput("45"))
        assertEquals(null, parseTimestampInput("1:99"))
        assertEquals(null, parseTimestampInput("1:02:99"))
        assertEquals(null, parseTimestampInput("bad"))
    }

    @Test
    fun nextSetNumberAvoidsReusingDeletedSetNames() {
        val sets = listOf(
            set("one", 1_000, "Set 1"),
            set("three", 3_000, "Set 3"),
        )

        assertEquals(4, nextManualSetNumber(sets))
    }

    @Test
    fun openSetDoesNotAutofillTranscriptRange() {
        val open = openSet("open", 10_000, "Set 1")

        assertEquals(false, open.overlapsRange(10_000, 20_000))
        assertEquals(false, open.containsPosition(15_000))
    }

    @Test
    fun closedSetCoversOnlyExplicitStartToEndRange() {
        val closed = set("closed", 10_000, "Set 1", endMs = 20_000)

        assertEquals(false, closed.overlapsRange(1_000, 9_999))
        assertEquals(true, closed.overlapsRange(12_000, 14_000))
        assertEquals(false, closed.overlapsRange(20_001, 30_000))
        assertEquals(true, closed.containsPosition(15_000))
        assertEquals(false, closed.containsPosition(25_000))
    }

    private fun set(id: String, startMs: Long, title: String, endMs: Long = startMs + 1_000) = ConversationSetEntity(
        id = id,
        recordingId = "recording",
        orderIndex = 0,
        startMs = startMs,
        endMs = endMs,
        title = title,
    )

    private fun openSet(id: String, startMs: Long, title: String) = set(id, startMs, title, endMs = startMs)

    private fun comment(id: String, timestampMs: Long) = CommentEntity(
        id = id,
        recordingId = "recording",
        timestampMs = timestampMs,
        text = id,
    )
}

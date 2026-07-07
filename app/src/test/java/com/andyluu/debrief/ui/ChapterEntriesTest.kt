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
        assertEquals("Untitled chapter", entries.last().title)
    }

    private fun set(id: String, startMs: Long, title: String) = ConversationSetEntity(
        id = id,
        recordingId = "recording",
        orderIndex = 0,
        startMs = startMs,
        endMs = startMs + 1_000,
        title = title,
    )

    private fun comment(id: String, timestampMs: Long) = CommentEntity(
        id = id,
        recordingId = "recording",
        timestampMs = timestampMs,
        text = id,
    )
}

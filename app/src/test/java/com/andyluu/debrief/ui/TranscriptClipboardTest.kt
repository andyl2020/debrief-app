package com.andyluu.debrief.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptClipboardTest {
    @Test
    fun `formats the complete transcript with timestamps and speakers`() {
        val result = formatTranscriptForClipboard(
            listOf(
                ClipboardTranscriptLine(1_000, "Andy", "Hey good to meet you."),
                ClipboardTranscriptLine(3_726_000, "Speaker B", "Likewise."),
            ),
        )

        assertEquals(
            "[0:01] Andy: Hey good to meet you.\n\n[1:02:06] Speaker B: Likewise.",
            result,
        )
    }

    @Test
    fun `keeps redacted display text private`() {
        assertEquals(
            "[0:00] Speaker A: [redacted]",
            formatTranscriptForClipboard(
                listOf(ClipboardTranscriptLine(0, "Speaker A", "[redacted]")),
            ),
        )
    }
}

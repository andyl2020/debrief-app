package com.andyluu.debrief.ui

internal data class ClipboardTranscriptLine(
    val timestampMs: Long,
    val speaker: String,
    val text: String,
)

/** Plain text that remains readable in messages, notes, email, and documents. */
internal fun formatTranscriptForClipboard(lines: List<ClipboardTranscriptLine>): String =
    lines.joinToString("\n\n") { line ->
        "[${formatTimestamp(line.timestampMs)}] ${line.speaker}: ${line.text.trim()}"
    }

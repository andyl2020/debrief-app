package com.andyluu.debrief.ui

import com.andyluu.debrief.data.RedactionEntity
import com.andyluu.debrief.data.TranscriptWordEntity

internal const val REDACTION_AUDIO_PAD_MS = 150L
internal const val REDACTION_LABEL = "[redacted]"

internal data class RedactionSelection(
    val segmentId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val hasExistingRedactions: Boolean = false,
)

internal data class RedactionRange(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

internal data class RedactedWordChoice(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

internal fun redactionActiveAt(
    positionMs: Long,
    redactions: List<RedactionEntity>,
    padMs: Long = REDACTION_AUDIO_PAD_MS,
): Boolean = redactions.any { positionMs in (it.startMs - padMs)..(it.endMs + padMs) }

internal fun List<RedactionEntity>.overlappingRedactions(startMs: Long, endMs: Long): List<RedactionEntity> =
    filter { it.startMs < endMs && it.endMs > startMs }

internal fun wordsForSegment(
    words: List<TranscriptWordEntity>,
    segmentStartMs: Long,
    segmentEndMs: Long,
): List<TranscriptWordEntity> =
    words.filter { it.endMs >= segmentStartMs && it.startMs <= segmentEndMs }.sortedBy { it.startMs }

internal fun redactedTranscriptText(
    text: String,
    words: List<TranscriptWordEntity>,
    redactions: List<RedactionEntity>,
    segmentStartMs: Long,
    segmentEndMs: Long,
): String {
    val overlapping = redactions.overlappingRedactions(segmentStartMs, segmentEndMs)
    if (overlapping.isEmpty()) return text
    if (overlapping.any { it.startMs <= segmentStartMs && it.endMs >= segmentEndMs }) return REDACTION_LABEL
    val ranges = wordTextRanges(text, words)
    if (ranges.isEmpty()) return REDACTION_LABEL
    val redactedRanges = ranges.filter { range -> overlapping.any { it.startMs < range.endMs && it.endMs > range.startMs } }
    if (redactedRanges.isEmpty()) return text
    if (redactedRanges.size == ranges.size) return REDACTION_LABEL

    val builder = StringBuilder()
    var cursor = 0
    var lastWasRedacted = false
    ranges.forEach { range ->
        val shouldRedact = redactedRanges.any { it.textStart == range.textStart && it.textEnd == range.textEnd }
        if (shouldRedact) {
            if (!lastWasRedacted) {
                builder.append(text.substring(cursor, range.textStart).trimEnd())
                if (builder.isNotEmpty() && !builder.last().isWhitespace()) builder.append(' ')
                builder.append(REDACTION_LABEL)
            }
            cursor = range.textEnd
            lastWasRedacted = true
        } else {
            val between = text.substring(cursor, range.textStart)
            builder.append(if (lastWasRedacted) between.afterRedaction() else between)
            builder.append(text.substring(range.textStart, range.textEnd))
            cursor = range.textEnd
            lastWasRedacted = false
        }
    }
    val suffix = text.substring(cursor)
    builder.append(if (lastWasRedacted) suffix.afterRedaction() else suffix)
    return builder.toString().replace(Regex("""\s{2,}"""), " ").trim()
}

internal fun segmentRedactionSelection(
    segmentId: Long,
    startMs: Long,
    endMs: Long,
    text: String,
    hasExistingRedactions: Boolean = false,
): RedactionSelection =
    RedactionSelection(segmentId, startMs, endMs, text, hasExistingRedactions)

internal fun redactionRangesForWholeSegment(
    words: List<TranscriptWordEntity>,
    segmentStartMs: Long,
    segmentEndMs: Long,
    fallbackText: String,
): List<RedactionRange> {
    val segmentWords = wordsForSegment(words, segmentStartMs, segmentEndMs)
    if (segmentWords.isEmpty()) return listOf(RedactionRange(segmentStartMs, segmentEndMs, fallbackText))
    return segmentWords.map { word -> RedactionRange(word.startMs, word.endMs, word.text) }
}

internal fun redactedWordChoices(
    words: List<TranscriptWordEntity>,
    redactions: List<RedactionEntity>,
    segmentStartMs: Long,
    segmentEndMs: Long,
): List<RedactedWordChoice> {
    val overlapping = redactions.overlappingRedactions(segmentStartMs, segmentEndMs)
    if (overlapping.isEmpty()) return emptyList()
    val segmentWords = wordsForSegment(words, segmentStartMs, segmentEndMs)
    if (segmentWords.isEmpty()) return emptyList()
    val wholeSegmentRedacted = overlapping.any { it.startMs <= segmentStartMs && it.endMs >= segmentEndMs }
    return segmentWords.mapIndexedNotNull { index, word ->
        val wordRedacted = wholeSegmentRedacted || overlapping.any { it.startMs < word.endMs && it.endMs > word.startMs }
        if (wordRedacted) RedactedWordChoice(index + 1, word.startMs, word.endMs, word.text) else null
    }
}

internal fun redactionRangesAfterRemovingWord(
    words: List<TranscriptWordEntity>,
    redactions: List<RedactionEntity>,
    segmentStartMs: Long,
    segmentEndMs: Long,
    removedWord: RedactedWordChoice,
): List<RedactionRange> {
    val overlapping = redactions.overlappingRedactions(segmentStartMs, segmentEndMs)
    if (overlapping.isEmpty()) return emptyList()
    val segmentWords = wordsForSegment(words, segmentStartMs, segmentEndMs)
    if (segmentWords.isEmpty()) return emptyList()
    val wholeSegmentRedacted = overlapping.any { it.startMs <= segmentStartMs && it.endMs >= segmentEndMs }
    return segmentWords
        .filterNot { it.startMs == removedWord.startMs && it.endMs == removedWord.endMs }
        .filter { word -> wholeSegmentRedacted || overlapping.any { it.startMs < word.endMs && it.endMs > word.startMs } }
        .map { word -> RedactionRange(word.startMs, word.endMs, word.text) }
}

private data class TimedTextRange(
    val textStart: Int,
    val textEnd: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

private fun wordTextRanges(text: String, words: List<TranscriptWordEntity>): List<TimedTextRange> {
    if (text.isBlank() || words.isEmpty()) return emptyList()
    val tokenMatches = Regex("""\S+""").findAll(text).toList()
    if (tokenMatches.isEmpty()) return emptyList()
    return tokenMatches.zip(words).map { (match, word) ->
        TimedTextRange(
            textStart = match.range.first,
            textEnd = match.range.last + 1,
            startMs = word.startMs,
            endMs = word.endMs,
            text = match.value,
        )
    }
}

private fun String.prependIfNeeded(): String =
    if (isNotEmpty() && first().isWhitespace()) this else if (isNotEmpty()) " $this" else this

private fun String.afterRedaction(): String {
    val stripped = dropWhile(Char::isWhitespace)
    return if (isNotEmpty() && first().isWhitespace()) " $stripped" else stripped.prependIfNeeded()
}

package com.andyluu.debrief.ui

import com.andyluu.debrief.data.ConversationSetEntity

internal fun ConversationSetEntity.isOpenManualSet(): Boolean = endMs <= startMs

internal fun ConversationSetEntity.effectiveEndMs(fallbackDurationMs: Long): Long =
    if (isOpenManualSet()) fallbackDurationMs.coerceAtLeast(startMs) else endMs

internal fun ConversationSetEntity.overlapsRange(startMs: Long, endMs: Long, fallbackDurationMs: Long): Boolean =
    this.startMs <= endMs && effectiveEndMs(fallbackDurationMs) >= startMs

internal fun parseTimestampInput(value: String): Long? {
    val parts = value.trim().split(':')
    if (parts.isEmpty() || parts.size > 3 || parts.any { it.isBlank() }) return null
    val numbers = parts.map { it.toLongOrNull() ?: return null }
    if (numbers.any { it < 0 }) return null
    val totalSeconds = when (numbers.size) {
        1 -> numbers[0]
        2 -> {
            val minutes = numbers[0]
            val seconds = numbers[1]
            if (seconds > 59) return null
            minutes * 60 + seconds
        }
        else -> {
            val hours = numbers[0]
            val minutes = numbers[1]
            val seconds = numbers[2]
            if (minutes > 59 || seconds > 59) return null
            hours * 3600 + minutes * 60 + seconds
        }
    }
    return totalSeconds * 1_000L
}

internal fun nextManualSetNumber(sets: List<ConversationSetEntity>): Int {
    val titleNumbers = sets.mapNotNull { set ->
        Regex("""^Set\s+(\d+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(set.title.trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }
    return ((titleNumbers.maxOrNull() ?: sets.size) + 1).coerceAtLeast(1)
}

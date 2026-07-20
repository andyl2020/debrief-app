package com.andyluu.debrief.ui

import com.andyluu.debrief.data.ConversationSetEntity

internal fun ConversationSetEntity.isOpenManualSet(): Boolean = endMs <= startMs

internal fun ConversationSetEntity.effectiveEndMs(fallbackDurationMs: Long): Long =
    if (isOpenManualSet()) fallbackDurationMs.coerceAtLeast(startMs) else endMs

internal fun ConversationSetEntity.overlapsRange(startMs: Long, endMs: Long, fallbackDurationMs: Long): Boolean =
    this.startMs <= endMs && effectiveEndMs(fallbackDurationMs) >= startMs

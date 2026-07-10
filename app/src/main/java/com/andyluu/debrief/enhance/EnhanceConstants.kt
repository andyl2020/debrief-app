package com.andyluu.debrief.enhance

object EnhanceConstants {
    const val FLAG_CONFIDENCE = 0.60
    const val STRONG_CONFIDENCE = 0.45
    const val MERGE_GAP_MS = 2_000L
    const val PAD_MS = 5_000L
    const val MAX_SPAN_MS = 30_000L
    const val AUTO_CLIP_CAP = 40
    const val SELECTION_CHUNK_MS = 120_000L
    const val SELECTION_SOFT_CAP_MS = 15 * 60_000L
    const val GEMINI_CALL_SPACING_MS = 6_000L
}

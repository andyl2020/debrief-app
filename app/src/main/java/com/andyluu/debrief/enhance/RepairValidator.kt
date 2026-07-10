package com.andyluu.debrief.enhance

object RepairValidator {
    fun validateTextEdit(
        edit: EnhanceEdit,
        nearbyText: String,
        keyterms: List<String>,
    ): Boolean {
        val original = edit.original.trim()
        val repaired = edit.repaired?.trim().orEmpty()
        if (original.isBlank()) return false
        if (edit.type !in setOf("fix", "merge", "inaudible")) return false
        if (edit.confidence !in setOf("high", "medium", "low")) return false
        if (edit.span.end <= edit.span.start) return false
        if (edit.type == "inaudible") return true
        if (repaired.isBlank()) return false
        if (repaired.equals(original, ignoreCase = true)) return false
        val originalWords = wordCount(original).coerceAtLeast(1)
        val repairedWords = wordCount(repaired)
        val ratio = repairedWords / originalWords.toDouble()
        if (ratio !in 0.4..2.5) return false
        if (!properNounsAreSupported(original, repaired, nearbyText, keyterms)) return false
        return true
    }

    fun validateAudioResult(result: EnhanceAudioResult): Boolean {
        if (result.confidence !in setOf("high", "medium", "low")) return false
        if (result.verdict == "inaudible") return true
        return !result.heard.isNullOrBlank()
    }

    private fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    private fun properNounsAreSupported(
        original: String,
        repaired: String,
        nearbyText: String,
        keyterms: List<String>,
    ): Boolean {
        val context = (original + " " + nearbyText + " " + keyterms.joinToString(" ")).lowercase()
        return properNouns(repaired).all { noun -> noun.lowercase() in context }
    }

    private fun properNouns(text: String): Set<String> =
        Regex("\\b[A-Z][a-zA-Z]{2,}\\b").findAll(text)
            .map { it.value.trim() }
            .filterNot { it in commonSentenceWords }
            .toSet()

    private val commonSentenceWords = setOf(
        "I", "I'm", "We", "You", "They", "The", "This", "That", "And", "But", "So", "Then",
        "Speaker", "Yeah", "Okay", "Sure", "Right",
    )
}

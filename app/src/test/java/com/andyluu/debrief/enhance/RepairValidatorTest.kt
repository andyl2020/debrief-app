package com.andyluu.debrief.enhance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairValidatorTest {
    @Test
    fun acceptsConservativeSupportedFix() {
        val edit = edit(original = "grand bill station", repaired = "Granville Station")

        assertTrue(
            RepairValidator.validateTextEdit(
                edit,
                nearbyText = "meet at grand bill station around ten",
                keyterms = listOf("Granville Station"),
            )
        )
    }

    @Test
    fun rejectsUnsupportedProperNoun() {
        val edit = edit(original = "some station", repaired = "Waterfront Station")

        assertFalse(
            RepairValidator.validateTextEdit(
                edit,
                nearbyText = "meet at some station",
                keyterms = emptyList(),
            )
        )
    }

    @Test
    fun acceptsInaudibleWithoutReplacement() {
        assertTrue(
            RepairValidator.validateTextEdit(
                edit(original = "muffled words", repaired = null, type = "inaudible"),
                nearbyText = "",
                keyterms = emptyList(),
            )
        )
    }

    private fun edit(
        original: String,
        repaired: String?,
        type: String = "fix",
    ) = EnhanceEdit(
        utteranceIds = listOf("u_1"),
        span = EnhanceSpan(1.0, 2.0),
        original = original,
        repaired = repaired,
        type = type,
        confidence = "high",
        needsAudio = false,
        reason = "test",
    )
}

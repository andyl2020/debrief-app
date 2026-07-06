package com.andyluu.debrief.transcription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun aggregatesDeepgramUsageAcrossBreakdownRows() {
        val payload = json.parseToJsonElement(
            """{"results":[{"requests":2,"total_hours":1.25},{"requests":3,"hours":0.5}]}"""
        ).jsonObject

        val (requests, hours) = parseDeepgramUsage(payload)

        assertEquals(5L, requests)
        assertEquals(1.75, hours, 0.0001)
    }

    @Test
    fun aggregatesSpendAndOnlyUsdBalances() {
        val billing = json.parseToJsonElement(
            """{"results":[{"dollars":0.25},{"dollars":1.75}]}"""
        ).jsonObject
        val balances = json.parseToJsonElement(
            """{"balances":[{"amount":12.5,"units":"USD"},{"amount":2,"units":"credits"}]}"""
        ).jsonObject

        assertEquals(2.0, parseDeepgramBilling(billing), 0.0001)
        assertEquals(12.5, parseDeepgramBalances(balances), 0.0001)
    }
}

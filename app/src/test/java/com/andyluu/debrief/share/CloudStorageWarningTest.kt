package com.andyluu.debrief.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CloudStorageWarningTest {
    private val zone = ZoneId.of("America/Vancouver")
    private val now = ZonedDateTime.of(2026, 8, 4, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun remainsQuietBelowNineGigabytes() {
        assertNull(cloudStorageWarning(8_999_999_999L, 10_000_000_000L, now, zone))
    }

    @Test
    fun warnsAtNineGigabytes() {
        val warning = cloudStorageWarning(9_000_000_000L, 10_000_000_000L, now, zone)!!

        assertEquals(CloudStorageWarningLevel.NEAR_LIMIT, warning.level)
        val deadline = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(warning.deadlineMillis), zone)
        assertEquals(31, deadline.dayOfMonth)
        assertEquals(23, deadline.hour)
        assertEquals(59, deadline.minute)
    }

    @Test
    fun escalatesAtTheConfiguredReference() {
        val warning = cloudStorageWarning(10_000_000_000L, 10_000_000_000L, now, zone)!!

        assertEquals(CloudStorageWarningLevel.EXCEEDED, warning.level)
        assertTrue(warning.deadlineMillis > now)
    }
}

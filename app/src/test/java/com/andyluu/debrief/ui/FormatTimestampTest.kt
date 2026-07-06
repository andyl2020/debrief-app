package com.andyluu.debrief.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTimestampTest {
    @Test fun formatsShortAndLongDurations() {
        assertEquals("0:00", formatTimestamp(0))
        assertEquals("9:07", formatTimestamp(547_000))
        assertEquals("2:03:04", formatTimestamp(7_384_000))
    }
}

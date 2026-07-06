package com.andyluu.debrief.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UsageStoreTest {
    @Test
    fun keyIdIsStableWithoutContainingTheSecret() {
        val first = UsageStore.keyId("secret-api-key")

        assertEquals(first, UsageStore.keyId(" secret-api-key "))
        assertEquals(12, first.length)
        assertNotEquals("secret-api-key", first)
        assertNotEquals(first, UsageStore.keyId("another-key"))
    }
}

package com.andyluu.debrief.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.andyluu.debrief.data.CloudUsageEntity
import org.junit.Rule
import org.junit.Test

class CloudUsageCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun warningAndBillingExplanationAppearAtNineGigabytes() {
        compose.setContent {
            DebriefTheme {
                CloudUsageCard(
                    CloudUsageEntity(
                        currentBytes = 9_000_000_000L,
                        referenceBytes = 10_000_000_000L,
                        activeLinks = 3,
                        measuredAt = System.currentTimeMillis(),
                        source = "test",
                        billingNote = "",
                    )
                )
            }
        }

        compose.onNodeWithText("Near the free allowance.", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Explain Cloudflare storage billing").performClick()
        compose.onNodeWithText("Warnings begin at 9 GB.", substring = true).assertIsDisplayed()
    }
}

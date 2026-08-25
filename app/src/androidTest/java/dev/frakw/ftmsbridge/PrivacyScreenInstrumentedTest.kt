package dev.frakw.ftmsbridge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyScreenInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun privacyScreenShowsLocalPolicyAndActions() {
        var policyOpened = false
        var contactOpened = false
        var privateReportOpened = false
        compose.setContent {
            FtmsBridgeTheme(dynamicColor = false) {
                PrivacyScreen(
                    onBack = {},
                    onOpenPolicy = { policyOpened = true },
                    onContact = { contactOpened = true },
                    onSensitiveReport = { privateReportOpened = true },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.privacy_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.privacy_summary)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.privacy_open_full_policy)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.privacy_contact_issues)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.privacy_contact_private)).performScrollTo().performClick()

        assertTrue(policyOpened)
        assertTrue(contactOpened)
        assertTrue(privateReportOpened)
    }

    @Test
    fun privacyScreenBackActionIsAvailable() {
        var back = false
        compose.setContent {
            FtmsBridgeTheme(dynamicColor = false) {
                PrivacyScreen(
                    onBack = { back = true },
                    onOpenPolicy = {},
                    onContact = {},
                    onSensitiveReport = {},
                )
            }
        }

        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        assertTrue(back)
    }
}

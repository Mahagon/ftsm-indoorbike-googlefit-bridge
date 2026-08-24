package dev.frakw.ftmsbridge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.frakw.ftmsbridge.model.BridgeState
import dev.frakw.ftmsbridge.model.ConnectionState
import dev.frakw.ftmsbridge.model.DiscoveredBike
import dev.frakw.ftmsbridge.model.IndoorBikeSample
import dev.frakw.ftmsbridge.retention.RetentionStatus
import dev.frakw.ftmsbridge.update.UpdateUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ExpressiveUiInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun disconnectedHomePrioritizesConnectionActions() {
        compose.setContent {
            FtmsBridgeTheme(dynamicColor = false) {
                TestHome(BridgeState())
            }
        }

        compose.onNodeWithText(context.getString(R.string.connection_not_connected)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connect_last_bike)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.scan)).assertIsDisplayed()
    }

    @Test
    fun readyHomeShowsMetricsAndStartsWorkout() {
        var started = false
        val bike = DiscoveredBike("Studio Bike", "00:11:22:33:44:55", -48)
        val state = BridgeState(
            connection = ConnectionState.READY,
            bike = bike,
            latest = IndoorBikeSample(Instant.now(), 24.5, 88.0, 210, 4_200, 600),
            distanceMeters = 4_200.0,
        )
        compose.setContent {
            FtmsBridgeTheme(dynamicColor = false) {
                TestHome(state, onStart = { started = true })
            }
        }

        compose.onNodeWithText("24.5").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.start_workout)).performClick()
        assertTrue(started)
    }

    @Test
    fun targetEditorOpensFromRideHome() {
        compose.setContent {
            FtmsBridgeTheme(dynamicColor = false) {
                TestHome(BridgeState())
            }
        }

        compose.onNodeWithText(context.getString(R.string.session_target)).performClick()
        compose.onNodeWithText(context.getString(R.string.minutes)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.distance)).assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun TestHome(state: BridgeState, onStart: () -> Unit = {}) {
        ExpressiveBridgeScreen(
            state = state,
            onConnectSetup = {},
            onScan = {},
            onConnect = {},
            onStart = onStart,
            onStop = {},
            onMonitoringChanged = {},
            onTargetChanged = {},
            onHealthPermissions = {},
            onHistory = {},
            retentionStatus = RetentionStatus(loading = false),
            onRetention = {},
            updateState = UpdateUiState(),
            updatesEnabled = false,
            updateInstallAllowed = true,
            onCheckUpdates = {},
            onStartUpdate = {},
            onDismissUpdate = {},
            onInstallUpdate = {},
            onShareDiagnostics = {},
        )
    }
}

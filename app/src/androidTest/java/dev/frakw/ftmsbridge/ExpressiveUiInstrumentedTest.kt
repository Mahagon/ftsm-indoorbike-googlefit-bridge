package dev.frakw.ftmsbridge

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.frakw.ftmsbridge.metrics.METRIC_CHART_END_TIME_TAG
import dev.frakw.ftmsbridge.metrics.MetricPoint
import dev.frakw.ftmsbridge.metrics.MetricSeries
import dev.frakw.ftmsbridge.metrics.WorkoutMetricSnapshot
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

        compose.onAllNodesWithText(context.getString(R.string.connection_not_connected))[0].assertIsDisplayed()
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

    @Test
    fun privacyOpensFromUtilitiesMenu() {
        var opened = false
        compose.setContent {
            FtmsBridgeTheme(dynamicColor = false) {
                TestHome(BridgeState(), onPrivacy = { opened = true })
            }
        }

        compose.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
        compose.onNodeWithText(context.getString(R.string.privacy_title)).performClick()
        assertTrue(opened)
    }

    @Test
    fun diagnosticsExplainsSharedData() {
        compose.setContent {
            FtmsBridgeTheme(dynamicColor = false) {
                TestHome(BridgeState())
            }
        }

        compose.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
        compose.onNodeWithText(context.getString(R.string.diagnostics)).performClick()
        compose.onNodeWithText(context.getString(R.string.diagnostics_share_privacy)).assertIsDisplayed()
    }

    @Test
    fun fullscreenDashboardFitsPortraitWithoutScrolling() {
        assertFullscreenDashboardFits(DpSize(412.dp, 892.dp))
    }

    @Test
    fun fullscreenDashboardFitsLandscapeWithoutScrolling() {
        assertFullscreenDashboardFits(DpSize(892.dp, 412.dp))
    }

    private fun assertFullscreenDashboardFits(size: DpSize) {
        val now = Instant.now()
        val speed = MetricSeries(
            average = 22.0,
            maximum = 31.0,
            points = listOf(
                MetricPoint(now.minusSeconds(60).toEpochMilli(), 18.0),
                MetricPoint(now.toEpochMilli(), 24.5),
            ),
        )
        val state = BridgeState(
            connection = ConnectionState.RECORDING,
            bike = DiscoveredBike("Studio Bike", "00:11:22:33:44:55", -48),
            latest = IndoorBikeSample(now, 24.5, 88.0, 210, 1_230, 60),
            recordingId = "test-recording",
            startedAt = now.minusSeconds(60),
            distanceMeters = 1_230.0,
            workoutMetrics = WorkoutMetricSnapshot(
                speedKph = speed,
                cadenceRpm = MetricSeries(82.0, 94.0, speed.points),
                powerWatts = MetricSeries(195.0, 235.0, speed.points),
            ),
        )

        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                FtmsBridgeTheme(dynamicColor = false) {
                    ExpressiveFullscreenMetricsScreen(state = state, onExit = {}, onStop = {})
                }
            }
        }

        compose.onNodeWithText("24.5").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cadence)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.power)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.duration)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.distance)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.stop_save)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.exit_fullscreen)).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.average)).assertCountEquals(1)
        compose.onAllNodesWithText(context.getString(R.string.maximum)).assertCountEquals(1)
        val locale = context.resources.configuration.locales[0]
        compose.onAllNodesWithText(String.format(locale, "%.1f km/h", speed.maximum)).assertCountEquals(1)
        compose.onNodeWithText(String.format(locale, "%.1f", 0.0)).assertDoesNotExist()
        compose.onNodeWithText("00:00").assertDoesNotExist()
        compose.onNodeWithTag(METRIC_CHART_END_TIME_TAG).assertIsDisplayed()
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
    }

    @androidx.compose.runtime.Composable
    private fun TestHome(
        state: BridgeState,
        onStart: () -> Unit = {},
        onPrivacy: () -> Unit = {},
    ) {
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
            onPrivacy = onPrivacy,
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

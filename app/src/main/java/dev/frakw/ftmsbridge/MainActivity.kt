package dev.frakw.ftmsbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.frakw.ftmsbridge.history.HistoryRoute
import dev.frakw.ftmsbridge.history.HistoryViewModel
import dev.frakw.ftmsbridge.history.WorkoutDetailRoute
import dev.frakw.ftmsbridge.metrics.MetricChart
import dev.frakw.ftmsbridge.metrics.MetricSeries
import dev.frakw.ftmsbridge.model.BridgeState
import dev.frakw.ftmsbridge.model.ConnectionState
import dev.frakw.ftmsbridge.model.WorkoutTarget
import dev.frakw.ftmsbridge.retention.RetentionScreen
import dev.frakw.ftmsbridge.retention.RetentionStatus
import dev.frakw.ftmsbridge.retention.RetentionViewModel
import dev.frakw.ftmsbridge.retention.RetentionWarningCard
import dev.frakw.ftmsbridge.retention.TrainingRetentionManager
import dev.frakw.ftmsbridge.update.UpdateStatus
import dev.frakw.ftmsbridge.update.UpdateUiState
import dev.frakw.ftmsbridge.update.UpdateViewModel
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val app get() = application as BridgeApplication
    private var enableMonitoringAfterPermission = false
    private var updateViewModel: UpdateViewModel? = null
    private val bluetoothPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val bluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.BLUETOOTH_SCAN] == true &&
            result[Manifest.permission.BLUETOOTH_CONNECT] == true
        ) {
            if (enableMonitoringAfterPermission) {
                app.controller.setMonitoringEnabled(true)
            } else {
                app.controller.reconnectLastBike()
            }
        }
        enableMonitoringAfterPermission = false
    }

    private val healthLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.containsAll(app.healthWriter.permissions)) app.controller.retryHealthSync()
    }

    private val installSourceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (packageManager.canRequestPackageInstalls()) launchUpdateInstaller()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TrainingRetentionManager.schedule(this)
        app.controller.retryHealthSync()
        setContent {
            FtmsBridgeTheme {
                val state by app.controller.state.collectAsStateWithLifecycle()
                val historyViewModel = viewModel { HistoryViewModel(app.database.workouts(), app.healthWriter) }
                val retentionViewModel = viewModel { RetentionViewModel(app.retention) }
                val retentionStatus by retentionViewModel.status.collectAsStateWithLifecycle()
                val updater = viewModel { UpdateViewModel(application) }
                updateViewModel = updater
                val updateState by updater.state.collectAsStateWithLifecycle()
                var destination by rememberSaveable { mutableStateOf(DESTINATION_MAIN) }
                var selectedWorkout by rememberSaveable { mutableStateOf<String?>(null) }
                var fullscreen by rememberSaveable { mutableStateOf(false) }
                var fullscreenHandledRecordingId by rememberSaveable { mutableStateOf<String?>(null) }
                val keepScreenAwake = shouldKeepScreenAwake(state.connection)
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updater.automaticCheck() }
                LaunchedEffect(state.recordingId, state.connection) {
                    val recordingId = state.recordingId
                    if (recordingId == null) {
                        if (fullscreenHandledRecordingId != null) {
                            fullscreen = false
                            fullscreenHandledRecordingId = null
                        }
                        if (!canShowFullscreen(state.connection)) fullscreen = false
                    } else if (recordingId != fullscreenHandledRecordingId) {
                        fullscreenHandledRecordingId = recordingId
                        fullscreen = true
                    }
                }
                DisposableEffect(fullscreen) {
                    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                    if (fullscreen) {
                        insetsController.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        insetsController.hide(WindowInsetsCompat.Type.systemBars())
                    } else {
                        insetsController.show(WindowInsetsCompat.Type.systemBars())
                    }
                    onDispose {
                        if (fullscreen) insetsController.show(WindowInsetsCompat.Type.systemBars())
                    }
                }
                DisposableEffect(keepScreenAwake) {
                    if (keepScreenAwake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose {
                        if (keepScreenAwake) {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                }
                BackHandler(fullscreen) { fullscreen = false }
                BackHandler(!fullscreen && destination != DESTINATION_MAIN) {
                    if (destination == DESTINATION_DETAIL) {
                        destination = DESTINATION_HISTORY
                    } else {
                        destination = DESTINATION_MAIN
                    }
                }
                if (fullscreen) {
                    ExpressiveFullscreenMetricsScreen(
                        state = state,
                        onExit = { fullscreen = false },
                        onStop = app.controller::stopWorkout,
                    )
                } else {
                    when (destination) {
                        DESTINATION_HISTORY -> HistoryRoute(
                            viewModel = historyViewModel,
                            onBack = { destination = DESTINATION_MAIN },
                            onWorkout = {
                                selectedWorkout = it
                                destination = DESTINATION_DETAIL
                            },
                        )

                        DESTINATION_DETAIL -> if (selectedWorkout != null) {
                            WorkoutDetailRoute(
                                workoutId = selectedWorkout!!,
                                viewModel = historyViewModel,
                                onBack = { destination = DESTINATION_HISTORY },
                                onRetrySync = { healthLauncher.launch(app.healthWriter.permissions) },
                            )
                        } else {
                            HistoryRoute(
                                viewModel = historyViewModel,
                                onBack = { destination = DESTINATION_MAIN },
                                onWorkout = {
                                    selectedWorkout = it
                                    destination = DESTINATION_DETAIL
                                },
                            )
                        }

                        DESTINATION_RETENTION -> RetentionScreen(
                            status = retentionStatus,
                            onSave = retentionViewModel::saveHours,
                            onBack = { destination = DESTINATION_MAIN },
                        )

                        else -> ExpressiveBridgeScreen(
                            state = state,
                            onConnectSetup = ::ensureBluetooth,
                            onScan = app.controller::scan,
                            onConnect = app.controller::connect,
                            onStart = {
                                if (hasBluetoothPermissions()) {
                                    healthLauncher.launch(app.healthWriter.permissions)
                                    app.controller.startWorkout()
                                } else {
                                    ensureBluetooth()
                                }
                            },
                            onStop = app.controller::stopWorkout,
                            onMonitoringChanged = ::setMonitoringEnabled,
                            onTargetChanged = app.controller::setNextWorkoutTarget,
                            onHealthPermissions = { healthLauncher.launch(app.healthWriter.permissions) },
                            onPrivacy = { startActivity(Intent(this, PrivacyActivity::class.java)) },
                            onHistory = { destination = DESTINATION_HISTORY },
                            retentionStatus = retentionStatus,
                            onRetention = { destination = DESTINATION_RETENTION },
                            updateState = updateState,
                            updatesEnabled = !BuildConfig.DEBUG,
                            updateInstallAllowed = state.recordingId == null && state.connection != ConnectionState.FINALIZING,
                            onCheckUpdates = updater::manualCheck,
                            onStartUpdate = updater::startDownload,
                            onDismissUpdate = updater::dismiss,
                            onInstallUpdate = ::requestUpdateInstall,
                            onShareDiagnostics = { shareDiagnostics(state) },
                        )
                    }
                }
            }
        }
    }

    private fun hasBluetoothPermissions() = bluetoothPermissions.take(2).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureBluetooth() {
        if (hasBluetoothPermissions()) {
            app.controller.reconnectLastBike()
        } else {
            bluetoothLauncher.launch(bluetoothPermissions)
        }
    }

    private fun setMonitoringEnabled(enabled: Boolean) {
        if (!enabled || hasBluetoothPermissions()) {
            app.controller.setMonitoringEnabled(enabled)
        } else {
            enableMonitoringAfterPermission = true
            bluetoothLauncher.launch(bluetoothPermissions)
        }
    }

    private fun requestUpdateInstall() {
        val updater = updateViewModel ?: return
        val state = app.controller.state.value
        if (state.recordingId != null || state.connection == ConnectionState.FINALIZING) return
        if (updater.readyFile() == null) return
        if (!packageManager.canRequestPackageInstalls()) {
            installSourceLauncher.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            )
            return
        }
        launchUpdateInstaller()
    }

    private fun launchUpdateInstaller() {
        val state = app.controller.state.value
        if (state.recordingId != null || state.connection == ConnectionState.FINALIZING) return
        val file = updateViewModel?.readyFile() ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, UpdateViewModel.APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private fun shareDiagnostics(state: BridgeState) {
        val body = buildString {
            appendLine(getString(R.string.diagnostics_report_title))
            appendLine(getString(R.string.diagnostics_bike, state.bike))
            appendLine(getString(R.string.diagnostics_state, state.connection))
            appendLine(getString(R.string.diagnostics_last_packet, state.rawPacket))
            state.diagnostics.forEach(::appendLine)
        }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_subject))
                    putExtra(Intent.EXTRA_TEXT, body)
                },
                getString(R.string.share_diagnostics_chooser),
            ),
        )
    }

    companion object {
        private const val DESTINATION_MAIN = "main"
        private const val DESTINATION_HISTORY = "history"
        private const val DESTINATION_DETAIL = "detail"
        private const val DESTINATION_RETENTION = "retention"
    }
}

internal fun shouldKeepScreenAwake(connection: ConnectionState): Boolean = connection == ConnectionState.READY || connection == ConnectionState.RECORDING

internal fun canShowFullscreen(connection: ConnectionState): Boolean = connection == ConnectionState.RECORDING

internal fun shouldShowStartWorkout(state: BridgeState): Boolean = state.connection == ConnectionState.READY && state.recordingId == null

@Composable
private fun BridgeScreen(
    state: BridgeState,
    onConnectSetup: () -> Unit,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMonitoringChanged: (Boolean) -> Unit,
    onTargetChanged: (WorkoutTarget?) -> Unit,
    onHealthPermissions: () -> Unit,
    onHistory: () -> Unit,
    retentionStatus: RetentionStatus,
    onRetention: () -> Unit,
    updateState: UpdateUiState,
    updatesEnabled: Boolean,
    updateInstallAllowed: Boolean,
    onCheckUpdates: () -> Unit,
    onStartUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onShareDiagnostics: () -> Unit,
) {
    var diagnostics by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.startedAt) {
        while (state.startedAt != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(stateLabel(state), color = MaterialTheme.colorScheme.primary)
            }
            retentionStatus.warning?.let { message ->
                item { RetentionWarningCard(message) }
            }
            if (updatesEnabled && updateState.status != UpdateStatus.IDLE) {
                item {
                    UpdateCard(
                        state = updateState,
                        installAllowed = updateInstallAllowed,
                        onDownload = onStartUpdate,
                        onLater = onDismissUpdate,
                        onRetry = onCheckUpdates,
                        onInstall = onInstallUpdate,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.background_monitoring), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.reconnect_automatically), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.monitoringEnabled, onCheckedChange = onMonitoringChanged)
                }
            }
            if (state.recordingId == null) {
                item { TargetEditor(state.target, onTargetChanged) }
            }
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (state.connection == ConnectionState.DISCONNECTED || state.connection == ConnectionState.ERROR) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConnectSetup) { Text(stringResource(R.string.connect_last_bike)) }
                        OutlinedButton(onClick = onScan) { Text(stringResource(R.string.scan)) }
                    }
                }
            }
            if (state.connection == ConnectionState.SCANNING) {
                item { Text(stringResource(R.string.nearby_bikes)) }
                items(state.devices, key = { it.address }) { bike ->
                    Card(onClick = { onConnect(bike.address) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(bike.name)
                            Text("${bike.address} · ${bike.signalDbm} dBm", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (state.connection in setOf(ConnectionState.READY, ConnectionState.RECORDING, ConnectionState.FINALIZING)) {
                item {
                    Metrics(
                        duration = state.startedAt?.let {
                            Duration.between(it, Instant.ofEpochMilli(now)).seconds.coerceAtLeast(0)
                        } ?: 0,
                        speed = state.latest?.speedKph,
                        cadence = state.latest?.cadenceRpm,
                        power = state.latest?.powerWatts,
                        distance = state.distanceMeters,
                        target = state.target,
                    )
                }
                item {
                    if (shouldShowStartWorkout(state)) {
                        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.start_workout))
                        }
                    } else if (state.recordingId != null) {
                        Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (state.monitoringEnabled) R.string.finish_now else R.string.stop_save))
                        }
                    }
                }
            }
            item {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onHistory) { Text(stringResource(R.string.history)) }
                        TextButton(onClick = onHealthPermissions) { Text(stringResource(R.string.health_permissions)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (updatesEnabled) {
                            TextButton(onClick = onCheckUpdates) { Text(stringResource(R.string.check_updates)) }
                        }
                        TextButton(onClick = onRetention) { Text(stringResource(R.string.storage)) }
                        TextButton(onClick = { diagnostics = !diagnostics }) { Text(stringResource(R.string.diagnostics)) }
                    }
                }
            }
            if (diagnostics) {
                item {
                    HorizontalDivider()
                    Text(stringResource(R.string.raw_packet), style = MaterialTheme.typography.titleMedium)
                    Text(state.rawPacket ?: stringResource(R.string.no_packet), fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onShareDiagnostics) { Text(stringResource(R.string.share_diagnostics)) }
                }
                items(state.diagnostics) { line -> Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun FullscreenMetricsScreen(
    state: BridgeState,
    onExit: () -> Unit,
    onStop: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.startedAt) {
        while (state.startedAt != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val duration = state.startedAt?.let {
        Duration.between(it, Instant.ofEpochMilli(now)).seconds.coerceAtLeast(0)
    } ?: 0
    val metrics = dashboardMetrics(state, duration)

    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        val landscape = maxWidth > maxHeight
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (state.recordingId == null) R.string.live_data else R.string.workout_in_progress), style = MaterialTheme.typography.titleLarge)
                    Text(stateLabel(state), color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onExit) { Text(stringResource(R.string.exit_fullscreen)) }
            }
            val chartMetrics = listOf(
                Triple(metrics[1], state.workoutMetrics.speedKph, "km/h"),
                Triple(metrics[2], state.workoutMetrics.cadenceRpm, "rpm"),
                Triple(metrics[3], state.workoutMetrics.powerWatts, "W"),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FullscreenSummaryCards(metrics, landscape)
                }
                items(chartMetrics) { (metric, series, unit) ->
                    WorkoutGraphCard(
                        metric = metric,
                        series = series,
                        unit = unit,
                        startedAtMillis = state.startedAt?.toEpochMilli() ?: now,
                        endedAtMillis = now,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.recordingId != null) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.monitoringEnabled) R.string.finish_now else R.string.stop_save))
                }
            }
        }
    }
}

@Composable
private fun FullscreenSummaryCards(metrics: List<DashboardMetric>, landscape: Boolean) {
    if (landscape) {
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.forEach { metric -> FullscreenMetric(metric, Modifier.weight(1f).fillMaxSize()) }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FullscreenMetric(metrics[0], Modifier.weight(1f).fillMaxSize())
                FullscreenMetric(metrics[1], Modifier.weight(1f).fillMaxSize())
            }
            Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FullscreenMetric(metrics[2], Modifier.weight(1f).fillMaxSize())
                FullscreenMetric(metrics[3], Modifier.weight(1f).fillMaxSize())
            }
            FullscreenMetric(metrics[4], Modifier.fillMaxWidth().height(130.dp))
        }
    }
}

@Composable
private fun WorkoutGraphCard(
    metric: DashboardMetric,
    series: MetricSeries?,
    unit: String,
    startedAtMillis: Long,
    endedAtMillis: Long,
    modifier: Modifier,
) {
    val locale = Locale.forLanguageTag(LocalLocale.current.toLanguageTag())
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(metric.label, style = MaterialTheme.typography.titleMedium)
            if (series == null) {
                Text(stringResource(R.string.no_data), modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                GraphStatistic(stringResource(R.string.average), String.format(locale, "%.1f %s", series.average, unit))
                GraphStatistic(stringResource(R.string.maximum), String.format(locale, "%.1f %s", series.maximum, unit))
                MetricChart(series, unit, startedAtMillis, endedAtMillis)
            }
        }
    }
}

@Composable
private fun GraphStatistic(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private data class DashboardMetric(
    val label: String,
    val value: String,
    val target: String? = null,
    val progress: Float? = null,
)

@Composable
private fun dashboardMetrics(state: BridgeState, duration: Long): List<DashboardMetric> {
    val locale = Locale.forLanguageTag(LocalLocale.current.toLanguageTag())
    val durationValue = "%02d:%02d:%02d".format(duration / 3600, duration / 60 % 60, duration % 60)
    val durationTarget = state.target as? WorkoutTarget.Duration
    val distanceTarget = state.target as? WorkoutTarget.Distance
    return listOf(
        DashboardMetric(
            stringResource(R.string.duration),
            durationValue,
            durationTarget?.let(::formatTarget),
            durationTarget?.let { targetProgress(it, duration, state.distanceMeters) },
        ),
        DashboardMetric(stringResource(R.string.speed), state.latest?.speedKph?.let { String.format(locale, "%.1f km/h", it) } ?: "—"),
        DashboardMetric(stringResource(R.string.cadence), state.latest?.cadenceRpm?.let { String.format(locale, "%.0f rpm", it) } ?: "—"),
        DashboardMetric(stringResource(R.string.power), state.latest?.powerWatts?.let { "$it W" } ?: "—"),
        DashboardMetric(
            stringResource(R.string.distance),
            String.format(locale, "%.2f km", state.distanceMeters / 1_000.0),
            distanceTarget?.let(::formatTarget),
            distanceTarget?.let { targetProgress(it, duration, state.distanceMeters) },
        ),
    )
}

@Composable
private fun FullscreenMetric(metric: DashboardMetric, modifier: Modifier) {
    Card(modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(metric.label, style = MaterialTheme.typography.titleMedium)
            Text(
                metric.value,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            metric.target?.let { target ->
                Text(
                    if (metric.progress == 1f) {
                        stringResource(R.string.target_reached_value, target)
                    } else {
                        stringResource(R.string.target_value, target)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                LinearProgressIndicator(
                    progress = { metric.progress ?: 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun UpdateCard(
    state: UpdateUiState,
    installAllowed: Boolean,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.app_update), style = MaterialTheme.typography.titleMedium)
            state.release?.let { release ->
                Text(stringResource(R.string.update_available, release.title))
                if (release.notes.isNotBlank()) {
                    Text(release.notes, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                }
            }
            state.message?.let {
                Text(it, color = if (state.status == UpdateStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            when (state.status) {
                UpdateStatus.CHECKING -> Text(stringResource(R.string.checking_updates))

                UpdateStatus.DOWNLOADING -> Text(stringResource(R.string.downloading_progress, state.progress?.let { "$it%" } ?: ""))

                UpdateStatus.AVAILABLE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownload) { Text(stringResource(R.string.update)) }
                    TextButton(onClick = onLater) { Text(stringResource(R.string.later)) }
                }

                UpdateStatus.READY -> {
                    Button(onClick = onInstall, enabled = installAllowed) { Text(stringResource(R.string.install)) }
                    if (!installAllowed) Text(stringResource(R.string.finish_before_install))
                }

                UpdateStatus.UP_TO_DATE -> TextButton(onClick = onLater) { Text(stringResource(R.string.dismiss)) }

                UpdateStatus.ERROR -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = if (state.release == null) onRetry else onDownload) { Text(stringResource(R.string.retry)) }
                    TextButton(onClick = onLater) { Text(stringResource(R.string.dismiss)) }
                }

                UpdateStatus.IDLE -> Unit
            }
        }
    }
}

@Composable
private fun Metrics(
    duration: Long,
    speed: Double?,
    cadence: Double?,
    power: Int?,
    distance: Double,
    target: WorkoutTarget?,
) {
    val locale = Locale.forLanguageTag(LocalLocale.current.toLanguageTag())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val durationValue = "%02d:%02d:%02d".format(duration / 3600, duration / 60 % 60, duration % 60)
        if (target is WorkoutTarget.Duration) {
            ProgressMetric(stringResource(R.string.duration), durationValue, formatTarget(target), targetProgress(target, duration, distance) ?: 0f)
        } else {
            Metric(stringResource(R.string.duration), durationValue)
        }
        Metric(stringResource(R.string.speed), speed?.let { String.format(locale, "%.1f km/h", it) } ?: "—")
        Metric(stringResource(R.string.cadence), cadence?.let { String.format(locale, "%.0f rpm", it) } ?: "—")
        Metric(stringResource(R.string.power), power?.let { "$it W" } ?: "—")
        val distanceValue = String.format(locale, "%.2f km", distance / 1000.0)
        if (target is WorkoutTarget.Distance) {
            ProgressMetric(stringResource(R.string.distance), distanceValue, formatTarget(target), targetProgress(target, duration, distance) ?: 0f)
        } else {
            Metric(stringResource(R.string.distance), distanceValue)
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun stateLabel(state: BridgeState): String = when (state.connection) {
    ConnectionState.DISCONNECTED ->
        stringResource(R.string.connection_not_connected)

    ConnectionState.SCANNING -> stringResource(R.string.connection_scanning)

    ConnectionState.CONNECTING -> stringResource(R.string.connection_connecting)

    ConnectionState.READY -> stringResource(R.string.connection_ready, state.bike?.name.orEmpty())

    ConnectionState.RECORDING -> stringResource(R.string.connection_recording, state.bike?.name.orEmpty())

    ConnectionState.FINALIZING -> stringResource(R.string.connection_saving)

    ConnectionState.ERROR -> stringResource(R.string.connection_problem)
}

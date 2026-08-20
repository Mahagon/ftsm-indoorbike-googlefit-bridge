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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.frakw.ftmsbridge.history.HistoryRoute
import dev.frakw.ftmsbridge.history.HistoryViewModel
import dev.frakw.ftmsbridge.history.WorkoutDetailRoute
import dev.frakw.ftmsbridge.model.BridgeState
import dev.frakw.ftmsbridge.model.ConnectionState
import dev.frakw.ftmsbridge.model.WorkoutTarget
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
        setContent {
            FtmsBridgeTheme {
                val state by app.controller.state.collectAsStateWithLifecycle()
                val historyViewModel = viewModel { HistoryViewModel(app.database.workouts()) }
                val updater = viewModel { UpdateViewModel(application) }
                updateViewModel = updater
                val updateState by updater.state.collectAsStateWithLifecycle()
                var destination by rememberSaveable { mutableStateOf(DESTINATION_MAIN) }
                var selectedWorkout by rememberSaveable { mutableStateOf<String?>(null) }
                val keepScreenAwake = shouldKeepScreenAwake(state.connection)
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updater.automaticCheck() }
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
                BackHandler(destination != DESTINATION_MAIN) {
                    if (destination == DESTINATION_DETAIL) {
                        destination = DESTINATION_HISTORY
                    } else {
                        destination = DESTINATION_MAIN
                    }
                }
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

                    else -> BridgeScreen(
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
                        onHistory = { destination = DESTINATION_HISTORY },
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
            appendLine("FTMS Bike Bridge diagnostics")
            appendLine("Bike: ${state.bike}")
            appendLine("State: ${state.connection}")
            appendLine("Last packet: ${state.rawPacket}")
            state.diagnostics.forEach(::appendLine)
        }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "FTMS diagnostics")
                    putExtra(Intent.EXTRA_TEXT, body)
                },
                "Share diagnostics",
            ),
        )
    }

    companion object {
        private const val DESTINATION_MAIN = "main"
        private const val DESTINATION_HISTORY = "history"
        private const val DESTINATION_DETAIL = "detail"
    }
}

internal fun shouldKeepScreenAwake(connection: ConnectionState): Boolean = connection == ConnectionState.READY || connection == ConnectionState.RECORDING

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
                Text("FTMS Bike Bridge", style = MaterialTheme.typography.headlineMedium)
                Text(stateLabel(state), color = MaterialTheme.colorScheme.primary)
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
                        Text("Background monitoring", style = MaterialTheme.typography.titleMedium)
                        Text("Reconnect and record automatically", style = MaterialTheme.typography.bodySmall)
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
                        Button(onClick = onConnectSetup) { Text("Connect last bike") }
                        OutlinedButton(onClick = onScan) { Text("Scan") }
                    }
                }
            }
            if (state.connection == ConnectionState.SCANNING) {
                item { Text("Nearby FTMS bikes") }
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
                    if (state.monitoringEnabled && state.recordingId == null) {
                        Text("Recording starts automatically when bike data arrives")
                    } else if (state.recordingId == null) {
                        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                            Text("Start workout")
                        }
                    } else {
                        Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.monitoringEnabled) "Finish now" else "Stop and save")
                        }
                    }
                }
            }
            item {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onHistory) { Text("History") }
                        TextButton(onClick = onHealthPermissions) { Text("Health permissions") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (updatesEnabled) {
                            TextButton(onClick = onCheckUpdates) { Text("Check updates") }
                        }
                        TextButton(onClick = { diagnostics = !diagnostics }) { Text("Diagnostics") }
                    }
                }
            }
            if (diagnostics) {
                item {
                    HorizontalDivider()
                    Text("Raw packet", style = MaterialTheme.typography.titleMedium)
                    Text(state.rawPacket ?: "No packet received", fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onShareDiagnostics) { Text("Share diagnostic log") }
                }
                items(state.diagnostics) { line -> Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
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
            Text("App update", style = MaterialTheme.typography.titleMedium)
            state.release?.let { release ->
                Text("${release.title} is available")
                if (release.notes.isNotBlank()) {
                    Text(release.notes, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                }
            }
            state.message?.let {
                Text(it, color = if (state.status == UpdateStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            when (state.status) {
                UpdateStatus.CHECKING -> Text("Checking for updates…")

                UpdateStatus.DOWNLOADING -> Text("Downloading… ${state.progress?.let { "$it%" } ?: ""}")

                UpdateStatus.AVAILABLE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownload) { Text("Update") }
                    TextButton(onClick = onLater) { Text("Later") }
                }

                UpdateStatus.READY -> {
                    Button(onClick = onInstall, enabled = installAllowed) { Text("Install") }
                    if (!installAllowed) Text("Finish the active workout before installing.")
                }

                UpdateStatus.UP_TO_DATE -> TextButton(onClick = onLater) { Text("Dismiss") }

                UpdateStatus.ERROR -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = if (state.release == null) onRetry else onDownload) { Text("Retry") }
                    TextButton(onClick = onLater) { Text("Dismiss") }
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val durationValue = "%02d:%02d:%02d".format(duration / 3600, duration / 60 % 60, duration % 60)
        if (target is WorkoutTarget.Duration) {
            ProgressMetric("Duration", durationValue, formatTarget(target), targetProgress(target, duration, distance) ?: 0f)
        } else {
            Metric("Duration", durationValue)
        }
        Metric("Speed", speed?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "—")
        Metric("Cadence", cadence?.let { String.format(Locale.US, "%.0f rpm", it) } ?: "—")
        Metric("Power", power?.let { "$it W" } ?: "—")
        val distanceValue = String.format(Locale.US, "%.2f km", distance / 1000.0)
        if (target is WorkoutTarget.Distance) {
            ProgressMetric("Distance", distanceValue, formatTarget(target), targetProgress(target, duration, distance) ?: 0f)
        } else {
            Metric("Distance", distanceValue)
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

private fun stateLabel(state: BridgeState): String = when (state.connection) {
    ConnectionState.DISCONNECTED ->
        "Not connected"

    ConnectionState.SCANNING -> "Scanning…"

    ConnectionState.CONNECTING -> "Connecting…"

    ConnectionState.READY -> "Ready · ${state.bike?.name.orEmpty()}"

    ConnectionState.RECORDING -> "Recording · ${state.bike?.name.orEmpty()}"

    ConnectionState.FINALIZING -> "Saving workout…"

    ConnectionState.ERROR -> "Connection problem"
}

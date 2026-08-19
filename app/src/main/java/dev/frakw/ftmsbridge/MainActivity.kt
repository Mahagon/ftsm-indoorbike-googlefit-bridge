package dev.frakw.ftmsbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.frakw.ftmsbridge.model.BridgeState
import dev.frakw.ftmsbridge.model.ConnectionState
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val app get() = application as BridgeApplication
    private var enableMonitoringAfterPermission = false
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val state by app.controller.state.collectAsStateWithLifecycle()
                val keepScreenAwake = shouldKeepScreenAwake(state.connection)
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
                BridgeScreen(
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
                    onHealthPermissions = { healthLauncher.launch(app.healthWriter.permissions) },
                    onShareDiagnostics = { shareDiagnostics(state) },
                )
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
    onHealthPermissions: () -> Unit,
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onHealthPermissions) { Text("Health permissions") }
                    TextButton(onClick = { diagnostics = !diagnostics }) { Text("Diagnostics") }
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
private fun Metrics(duration: Long, speed: Double?, cadence: Double?, power: Int?, distance: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Metric("Duration", "%02d:%02d:%02d".format(duration / 3600, duration / 60 % 60, duration % 60))
        Metric("Speed", speed?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "—")
        Metric("Cadence", cadence?.let { String.format(Locale.US, "%.0f rpm", it) } ?: "—")
        Metric("Power", power?.let { "$it W" } ?: "—")
        Metric("Distance", String.format(Locale.US, "%.2f km", distance / 1000.0))
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
        if (state.reconnectDeadline != null) "Bike disconnected · waiting up to 5 minutes" else "Not connected"

    ConnectionState.SCANNING -> "Scanning…"

    ConnectionState.CONNECTING -> "Connecting…"

    ConnectionState.READY -> "Ready · ${state.bike?.name.orEmpty()}"

    ConnectionState.RECORDING -> "Recording · ${state.bike?.name.orEmpty()}"

    ConnectionState.FINALIZING -> "Saving workout…"

    ConnectionState.ERROR -> "Connection problem"
}

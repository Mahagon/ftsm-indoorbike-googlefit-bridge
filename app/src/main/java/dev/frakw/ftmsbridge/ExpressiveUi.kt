package dev.frakw.ftmsbridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeExtendedFloatingActionButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.frakw.ftmsbridge.metrics.MetricChart
import dev.frakw.ftmsbridge.model.BridgeState
import dev.frakw.ftmsbridge.model.ConnectionState
import dev.frakw.ftmsbridge.model.WorkoutTarget
import dev.frakw.ftmsbridge.retention.RetentionStatus
import dev.frakw.ftmsbridge.retention.RetentionWarning
import dev.frakw.ftmsbridge.update.UpdateStatus
import dev.frakw.ftmsbridge.update.UpdateUiState
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveBridgeScreen(
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
    var targetSheet by remember { mutableStateOf(false) }
    var diagnosticsSheet by remember { mutableStateOf(false) }
    var utilitiesExpanded by remember { mutableStateOf(false) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                        Text(
                            expressiveStateLabel(state),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onHistory) {
                        Icon(Icons.Rounded.History, contentDescription = stringResource(R.string.history))
                    }
                    Box {
                        IconButton(onClick = { utilitiesExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = utilitiesExpanded, onDismissRequest = { utilitiesExpanded = false }) {
                            UtilityItem(Icons.Rounded.HealthAndSafety, R.string.health_permissions) {
                                utilitiesExpanded = false
                                onHealthPermissions()
                            }
                            if (updatesEnabled) {
                                UtilityItem(Icons.Rounded.SystemUpdate, R.string.check_updates) {
                                    utilitiesExpanded = false
                                    onCheckUpdates()
                                }
                            }
                            UtilityItem(Icons.Rounded.Storage, R.string.storage) {
                                utilitiesExpanded = false
                                onRetention()
                            }
                            UtilityItem(Icons.Rounded.BugReport, R.string.diagnostics) {
                                utilitiesExpanded = false
                                diagnosticsSheet = true
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            when {
                shouldShowStartWorkout(state) -> RideActionFab(false, onStart)
                state.recordingId != null -> RideActionFab(true, onStop, state.monitoringEnabled)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 116.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ConnectionHero(state, onConnectSetup, onScan)
            }
            if (state.connection == ConnectionState.SCANNING) {
                item {
                    Text(stringResource(R.string.nearby_bikes), style = MaterialTheme.typography.titleLarge)
                }
                items(state.devices, key = { it.address }) { bike ->
                    ElevatedCard(onClick = { onConnect(bike.address) }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.DirectionsBike, contentDescription = null)
                            Column(Modifier.weight(1f)) {
                                Text(bike.name, style = MaterialTheme.typography.titleMedium)
                                Text("${bike.address} · ${bike.signalDbm} dBm", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            retentionStatus.warning?.let { warning ->
                item { RetentionAlert(warning, onRetention) }
            }
            if (updatesEnabled && updateState.status != UpdateStatus.IDLE) {
                item {
                    ExpressiveUpdateCard(
                        state = updateState,
                        installAllowed = updateInstallAllowed,
                        onDownload = onStartUpdate,
                        onLater = onDismissUpdate,
                        onRetry = onCheckUpdates,
                        onInstall = onInstallUpdate,
                    )
                }
            }
            item { MonitoringCard(state.monitoringEnabled, onMonitoringChanged) }
            if (state.recordingId == null) {
                item { TargetActionCard(state.target) { targetSheet = true } }
            }
            if (state.connection in setOf(ConnectionState.READY, ConnectionState.RECORDING, ConnectionState.FINALIZING)) {
                item { RideMetricGrid(state, duration) }
            }
        }
    }

    if (targetSheet) {
        ModalBottomSheet(onDismissRequest = { targetSheet = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                TargetEditor(state.target) {
                    onTargetChanged(it)
                    targetSheet = false
                }
            }
        }
    }
    if (diagnosticsSheet) {
        DiagnosticsSheet(state, onShareDiagnostics) { diagnosticsSheet = false }
    }
}

@Composable
private fun UtilityItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null) },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionHero(state: BridgeState, onConnectSetup: () -> Unit, onScan: () -> Unit) {
    val busy = state.connection in setOf(ConnectionState.SCANNING, ConnectionState.CONNECTING, ConnectionState.FINALIZING)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLargeIncreased,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    LoadingIndicator(Modifier.size(52.dp), color = MaterialTheme.colorScheme.primary)
                } else {
                    FilledTonalIconButton(onClick = {}, enabled = false, modifier = Modifier.size(52.dp)) {
                        Icon(
                            if (state.connection == ConnectionState.ERROR) Icons.Rounded.Warning else Icons.Rounded.Bluetooth,
                            contentDescription = null,
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(expressiveStateLabel(state), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    state.bike?.let { Text(it.name, style = MaterialTheme.typography.bodyLarge) }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.connection == ConnectionState.DISCONNECTED || state.connection == ConnectionState.ERROR) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onConnectSetup, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Bluetooth, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.connect_last_bike))
                    }
                    FilledTonalButton(onClick = onScan) {
                        Icon(Icons.Rounded.BluetoothSearching, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.scan))
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitoringCard(enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.DirectionsBike, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.background_monitoring), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.reconnect_automatically), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = onChanged)
        }
    }
}

@Composable
private fun TargetActionCard(target: WorkoutTarget?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.session_target), style = MaterialTheme.typography.titleMedium)
                Text(
                    target?.let(::formatTarget) ?: stringResource(R.string.target_next_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(stringResource(if (target == null) R.string.set_target else R.string.update_target), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RideActionFab(stopping: Boolean, onClick: () -> Unit, monitoring: Boolean = false) {
    LargeExtendedFloatingActionButton(
        onClick = onClick,
        containerColor = if (stopping) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (stopping) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Icon(if (stopping) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(
                when {
                    !stopping -> R.string.start_workout
                    monitoring -> R.string.finish_now
                    else -> R.string.stop_save
                },
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RideMetricGrid(state: BridgeState, duration: Long) {
    val locale = Locale.forLanguageTag(LocalLocale.current.toLanguageTag())
    val durationValue = "%02d:%02d:%02d".format(duration / 3600, duration / 60 % 60, duration % 60)
    val durationTarget = state.target as? WorkoutTarget.Duration
    val distanceTarget = state.target as? WorkoutTarget.Distance
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricTile(
            label = stringResource(R.string.speed),
            value = state.latest?.speedKph?.let { String.format(locale, "%.1f", it) } ?: "—",
            unit = "km/h",
            modifier = Modifier.fillMaxWidth().height(156.dp),
            prominent = true,
        )
        Row(Modifier.fillMaxWidth().height(126.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                stringResource(R.string.cadence),
                state.latest?.cadenceRpm?.let { String.format(locale, "%.0f", it) } ?: "—",
                "rpm",
                Modifier.weight(1f).fillMaxHeight(),
            )
            MetricTile(
                stringResource(R.string.power),
                state.latest?.powerWatts?.toString() ?: "—",
                "W",
                Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(Modifier.fillMaxWidth().height(138.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                stringResource(R.string.duration),
                durationValue,
                null,
                Modifier.weight(1f).fillMaxHeight(),
                target = durationTarget?.let(::formatTarget),
                progress = durationTarget?.let { targetProgress(it, duration, state.distanceMeters) },
            )
            MetricTile(
                stringResource(R.string.distance),
                String.format(locale, "%.2f", state.distanceMeters / 1_000.0),
                "km",
                Modifier.weight(1f).fillMaxHeight(),
                target = distanceTarget?.let(::formatTarget),
                progress = distanceTarget?.let { targetProgress(it, duration, state.distanceMeters) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MetricTile(
    label: String,
    value: String,
    unit: String?,
    modifier: Modifier,
    prominent: Boolean = false,
    target: String? = null,
    progress: Float? = null,
) {
    Card(
        modifier = modifier.semantics {
            if (progress != null) progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(progress, 0f..1f)
        },
        colors = CardDefaults.cardColors(
            containerColor = if (prominent) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = if (prominent) MaterialTheme.shapes.extraLarge else MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    value,
                    style = if (prominent) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    textAlign = TextAlign.Center,
                )
                unit?.let { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 5.dp)) }
            }
            if (target != null && progress != null) {
                Text(
                    stringResource(if (progress >= 1f) R.string.target_reached_value else R.string.target_value, target),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
                LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun RetentionAlert(warning: RetentionWarning, onClick: () -> Unit) {
    val message = when (warning) {
        RetentionWarning.BACKUP_BLOCKED -> R.string.retention_warning_backup_blocked
        RetentionWarning.BACKUP_TARGET -> R.string.retention_warning_backup_target
        RetentionWarning.PROTECTED_LIMIT -> R.string.retention_warning_protected
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Column {
                Text(stringResource(R.string.backup_warning), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressiveUpdateCard(
    state: UpdateUiState,
    installAllowed: Boolean,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.app_update), style = MaterialTheme.typography.titleLarge)
            }
            state.release?.let { release ->
                Text(stringResource(R.string.update_available, release.title), style = MaterialTheme.typography.titleMedium)
                if (release.notes.isNotBlank()) Text(release.notes, style = MaterialTheme.typography.bodySmall, maxLines = 4)
            }
            state.message?.let {
                Text(it, color = if (state.status == UpdateStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            when (state.status) {
                UpdateStatus.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.checking_updates))
                }

                UpdateStatus.DOWNLOADING -> {
                    Text(stringResource(R.string.downloading_progress, state.progress?.let { "$it%" } ?: ""))
                    state.progress?.let { LinearWavyProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
                }

                UpdateStatus.AVAILABLE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownload) { Text(stringResource(R.string.update)) }
                    TextButton(onClick = onLater) { Text(stringResource(R.string.later)) }
                }

                UpdateStatus.READY -> {
                    Button(onClick = onInstall, enabled = installAllowed) { Text(stringResource(R.string.install)) }
                    if (!installAllowed) Text(stringResource(R.string.finish_before_install), style = MaterialTheme.typography.bodySmall)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsSheet(state: BridgeState, onShare: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.headlineSmall)
                    FilledTonalIconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.dismiss))
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.raw_packet), style = MaterialTheme.typography.titleMedium)
                        Text(state.rawPacket ?: stringResource(R.string.no_packet), fontFamily = FontFamily.Monospace)
                    }
                }
            }
            item {
                OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share_diagnostics))
                }
            }
            if (state.diagnostics.isNotEmpty()) item { HorizontalDivider() }
            items(state.diagnostics) { line ->
                Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveFullscreenMetricsScreen(state: BridgeState, onExit: () -> Unit, onStop: () -> Unit) {
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
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(stringResource(R.string.workout_in_progress), style = MaterialTheme.typography.headlineSmall)
                    Text(expressiveStateLabel(state), color = MaterialTheme.colorScheme.primary)
                }
                FilledTonalIconButton(onClick = onExit) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.exit_fullscreen))
                }
            }
            if (landscape) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FullscreenSpeedGraphCard(
                        state = state,
                        now = now,
                        modifier = Modifier.weight(1.45f).fillMaxHeight(),
                    )
                    FullscreenMetricGrid(
                        state = state,
                        duration = duration,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FullscreenSpeedGraphCard(
                        state = state,
                        now = now,
                        modifier = Modifier.fillMaxWidth().weight(1.25f),
                    )
                    FullscreenMetricGrid(
                        state = state,
                        duration = duration,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
            if (state.recordingId != null) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (state.monitoringEnabled) R.string.finish_now else R.string.stop_save))
                }
            }
        }
    }
}

@Composable
private fun FullscreenMetricGrid(state: BridgeState, duration: Long, modifier: Modifier = Modifier) {
    val locale = Locale.forLanguageTag(LocalLocale.current.toLanguageTag())
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                stringResource(R.string.cadence),
                state.latest?.cadenceRpm?.let { String.format(locale, "%.0f", it) } ?: "—",
                "rpm",
                Modifier.weight(1f).fillMaxHeight(),
            )
            MetricTile(
                stringResource(R.string.power),
                state.latest?.powerWatts?.toString() ?: "—",
                "W",
                Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                stringResource(R.string.duration),
                "%02d:%02d:%02d".format(duration / 3600, duration / 60 % 60, duration % 60),
                null,
                Modifier.weight(1f).fillMaxHeight(),
            )
            MetricTile(
                stringResource(R.string.distance),
                String.format(locale, "%.2f", state.distanceMeters / 1_000.0),
                "km",
                Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun FullscreenSpeedGraphCard(state: BridgeState, now: Long, modifier: Modifier = Modifier) {
    val locale = Locale.forLanguageTag(LocalLocale.current.toLanguageTag())
    val currentSpeed = state.latest?.speedKph?.let { String.format(locale, "%.1f", it) } ?: "—"
    val series = state.workoutMetrics.speedKph
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.speed), style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(currentSpeed, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("km/h", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            if (series == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_data))
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    FullscreenGraphStatistic(
                        label = stringResource(R.string.average),
                        value = String.format(locale, "%.1f km/h", series.average),
                        horizontalAlignment = Alignment.Start,
                    )
                    FullscreenGraphStatistic(
                        label = stringResource(R.string.maximum),
                        value = String.format(locale, "%.1f km/h", series.maximum),
                        horizontalAlignment = Alignment.End,
                    )
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    MetricChart(
                        series = series,
                        unit = "km/h",
                        startedAtMillis = state.startedAt?.toEpochMilli() ?: now,
                        endedAtMillis = now,
                        chartHeight = maxOf(maxHeight - 20.dp, 24.dp),
                        showOriginLabels = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun FullscreenGraphStatistic(
    label: String,
    value: String,
    horizontalAlignment: Alignment.Horizontal,
) {
    Column(horizontalAlignment = horizontalAlignment) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun expressiveStateLabel(state: BridgeState): String = when (state.connection) {
    ConnectionState.DISCONNECTED -> stringResource(R.string.connection_not_connected)
    ConnectionState.SCANNING -> stringResource(R.string.connection_scanning)
    ConnectionState.CONNECTING -> stringResource(R.string.connection_connecting)
    ConnectionState.READY -> stringResource(R.string.connection_ready, state.bike?.name.orEmpty())
    ConnectionState.RECORDING -> stringResource(R.string.connection_recording, state.bike?.name.orEmpty())
    ConnectionState.FINALIZING -> stringResource(R.string.connection_saving)
    ConnectionState.ERROR -> stringResource(R.string.connection_problem)
}

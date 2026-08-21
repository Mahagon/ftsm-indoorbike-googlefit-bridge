package dev.frakw.ftmsbridge.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.frakw.ftmsbridge.R
import dev.frakw.ftmsbridge.data.WorkoutEntity
import dev.frakw.ftmsbridge.data.target
import dev.frakw.ftmsbridge.formatTarget
import dev.frakw.ftmsbridge.health.HealthConnectVerificationIssue
import dev.frakw.ftmsbridge.targetReached
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun HistoryRoute(
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
    onWorkout: (String) -> Unit,
) {
    val page by viewModel.page.collectAsStateWithLifecycle()
    HistoryScreen(page, viewModel::loadMore, onBack, onWorkout)
}

@Composable
fun WorkoutDetailRoute(
    workoutId: String,
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
    onRetrySync: () -> Unit,
) {
    val detailsFlow = remember(workoutId) { viewModel.details(workoutId) }
    val details by detailsFlow.collectAsStateWithLifecycle(initialValue = null)
    val verification by viewModel.healthVerification.collectAsStateWithLifecycle()
    WorkoutDetailScreen(details, verification, onBack, onRetrySync) { viewModel.verifyHealthConnect(workoutId) }
}

@Composable
private fun HistoryScreen(
    page: WorkoutPage,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
    onWorkout: (String) -> Unit,
) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ScreenHeader(stringResource(R.string.exercise_history), onBack) }
            if (page.isLoading) {
                item { Text(stringResource(R.string.loading_history)) }
            } else if (page.workouts.isEmpty()) {
                item { Text(stringResource(R.string.no_workouts), style = MaterialTheme.typography.bodyLarge) }
            }
            items(page.workouts, key = { it.id }) { workout ->
                Card(onClick = { onWorkout(workout.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(formatDateTime(workout.startedAtMillis), style = MaterialTheme.typography.titleMedium)
                        SummaryRow(stringResource(R.string.duration), formatDuration(workout))
                        SummaryRow(stringResource(R.string.distance), formatDistance(workout.distanceMeters))
                        Text(syncLabel(workout), color = syncColor(workout))
                    }
                }
            }
            if (page.hasMore) {
                item {
                    Button(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.load_more)) }
                }
            }
        }
    }
}

@Composable
private fun WorkoutDetailScreen(
    details: WorkoutDetails?,
    verification: HealthVerificationState,
    onBack: () -> Unit,
    onRetrySync: () -> Unit,
    onVerifyHealthConnect: () -> Unit,
) {
    val locale = Locale.forLanguageTag(LocalLocale.current.toLanguageTag())
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ScreenHeader(stringResource(R.string.workout_details), onBack) }
            if (details == null) {
                item { Text(stringResource(R.string.loading_workout)) }
            } else {
                val workout = details.workout
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DetailRow(stringResource(R.string.started), formatDateTime(workout.startedAtMillis))
                            DetailRow(stringResource(R.string.ended), workout.endedAtMillis?.let(::formatDateTime) ?: "—")
                            DetailRow(stringResource(R.string.duration), formatDuration(workout))
                            DetailRow(stringResource(R.string.distance), formatDistance(workout.distanceMeters))
                            workout.caloriesKcal?.let {
                                DetailRow(stringResource(R.string.calories), String.format(locale, "%.0f kcal", it))
                            }
                            workout.target()?.let { target ->
                                DetailRow(stringResource(R.string.target), formatTarget(target))
                                DetailRow(
                                    stringResource(R.string.target_result),
                                    stringResource(if (targetReached(workout) == true) R.string.reached else R.string.not_reached),
                                )
                            }
                        }
                    }
                }
                if (details.hasSamples) {
                    item { MetricCard(stringResource(R.string.speed), details.speedKph, "km/h") }
                    item { MetricCard(stringResource(R.string.cadence), details.cadenceRpm, "rpm") }
                    item { MetricCard(stringResource(R.string.power), details.powerWatts, "W") }
                } else {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.detailed_metrics_unavailable),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.health_connect), style = MaterialTheme.typography.titleMedium)
                            Text(syncLabel(workout), color = syncColor(workout))
                            workout.syncError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (!workout.synced) {
                                Button(onClick = onRetrySync) { Text(stringResource(R.string.retry_save)) }
                            }
                            Button(
                                onClick = onVerifyHealthConnect,
                                enabled = verification !is HealthVerificationState.Loading,
                            ) {
                                Text(
                                    stringResource(
                                        if (verification is HealthVerificationState.Loading) {
                                            R.string.verifying_health_connect
                                        } else {
                                            R.string.verify_health_connect
                                        },
                                    ),
                                )
                            }
                            HealthVerificationResult(workout.id, verification)
                            Text(stringResource(R.string.health_google_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthVerificationResult(
    workoutId: String,
    state: HealthVerificationState,
) {
    val locale = Locale.forLanguageTag(LocalLocale.current.toLanguageTag())
    when (state) {
        is HealthVerificationState.Result -> if (state.workoutId == workoutId) {
            val result = state.verification
            Text(
                stringResource(
                    if (result.verified) R.string.health_connect_verified else R.string.health_connect_mismatch,
                ),
                color = if (result.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            DetailRow(stringResource(R.string.health_session_records), result.sessionCount.toString())
            DetailRow(stringResource(R.string.health_exercise_type), result.exerciseType?.toString() ?: "—")
            DetailRow(stringResource(R.string.health_record_version), result.sessionVersion?.toString() ?: "—")
            DetailRow(stringResource(R.string.health_distance_records), result.distanceRecordCount.toString())
            DetailRow(
                stringResource(R.string.health_distance_comparison),
                String.format(
                    locale,
                    "%.2f / %.2f km",
                    result.storedDistanceMeters / 1_000.0,
                    result.expectedDistanceMeters / 1_000.0,
                ),
            )
            if (result.issues.isNotEmpty()) {
                result.issues.forEach { issue ->
                    Text(stringResource(issue.labelResource()), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        is HealthVerificationState.Error -> if (state.workoutId == workoutId) {
            Text(stringResource(R.string.health_connect_verification_failed, state.message), color = MaterialTheme.colorScheme.error)
        }

        HealthVerificationState.Idle,
        is HealthVerificationState.Loading,
        -> Unit
    }
}

private fun HealthConnectVerificationIssue.labelResource(): Int = when (this) {
    HealthConnectVerificationIssue.SESSION_MISSING -> R.string.health_issue_session_missing
    HealthConnectVerificationIssue.SESSION_DUPLICATED -> R.string.health_issue_session_duplicated
    HealthConnectVerificationIssue.SESSION_TYPE_MISMATCH -> R.string.health_issue_session_type
    HealthConnectVerificationIssue.SESSION_INTERVAL_MISMATCH -> R.string.health_issue_session_interval
    HealthConnectVerificationIssue.SESSION_VERSION_MISMATCH -> R.string.health_issue_session_version
    HealthConnectVerificationIssue.DISTANCE_MISSING -> R.string.health_issue_distance_missing
    HealthConnectVerificationIssue.DISTANCE_UNEXPECTED -> R.string.health_issue_distance_unexpected
    HealthConnectVerificationIssue.DISTANCE_TOTAL_MISMATCH -> R.string.health_issue_distance_total
    HealthConnectVerificationIssue.DISTANCE_VERSION_MISMATCH -> R.string.health_issue_distance_version
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun MetricCard(label: String, summary: MetricSummary?, unit: String) {
    val locale = Locale.forLanguageTag(LocalLocale.current.toLanguageTag())
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (summary == null) {
                Text(stringResource(R.string.no_data))
            } else {
                DetailRow(stringResource(R.string.average), String.format(locale, "%.1f %s", summary.average, unit))
                DetailRow(stringResource(R.string.maximum), String.format(locale, "%.1f %s", summary.maximum, unit))
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) = DetailRow(label, value)

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun syncLabel(workout: WorkoutEntity): String = stringResource(
    syncLabelResource(workout),
)

internal fun syncLabelResource(workout: WorkoutEntity): Int = when {
    workout.synced -> R.string.saved_health_connect
    workout.syncError != null -> R.string.save_failed
    else -> R.string.save_pending
}

@Composable
private fun syncColor(workout: WorkoutEntity) = when {
    workout.synced -> MaterialTheme.colorScheme.primary
    workout.syncError != null -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun formatDuration(workout: WorkoutEntity): String {
    val end = workout.endedAtMillis ?: return "—"
    val seconds = Duration.ofMillis((end - workout.startedAtMillis).coerceAtLeast(0)).seconds
    return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}

private fun formatDistance(meters: Double) = String.format(Locale.getDefault(), "%.2f km", meters / 1000.0)

private fun formatDateTime(millis: Long): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withLocale(Locale.getDefault())
    .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.frakw.ftmsbridge.data.WorkoutEntity
import dev.frakw.ftmsbridge.data.target
import dev.frakw.ftmsbridge.formatTarget
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
    WorkoutDetailScreen(details, onBack, onRetrySync)
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
            item { ScreenHeader("Exercise history", onBack) }
            if (page.isLoading) {
                item { Text("Loading history…") }
            } else if (page.workouts.isEmpty()) {
                item { Text("No completed workouts yet.", style = MaterialTheme.typography.bodyLarge) }
            }
            items(page.workouts, key = { it.id }) { workout ->
                Card(onClick = { onWorkout(workout.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(formatDateTime(workout.startedAtMillis), style = MaterialTheme.typography.titleMedium)
                        SummaryRow("Duration", formatDuration(workout))
                        SummaryRow("Distance", formatDistance(workout.distanceMeters))
                        Text(syncLabel(workout), color = syncColor(workout))
                    }
                }
            }
            if (page.hasMore) {
                item {
                    Button(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text("Load more") }
                }
            }
        }
    }
}

@Composable
private fun WorkoutDetailScreen(
    details: WorkoutDetails?,
    onBack: () -> Unit,
    onRetrySync: () -> Unit,
) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ScreenHeader("Workout details", onBack) }
            if (details == null) {
                item { Text("Loading workout…") }
            } else {
                val workout = details.workout
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DetailRow("Started", formatDateTime(workout.startedAtMillis))
                            DetailRow("Ended", workout.endedAtMillis?.let(::formatDateTime) ?: "—")
                            DetailRow("Duration", formatDuration(workout))
                            DetailRow("Distance", formatDistance(workout.distanceMeters))
                            workout.target()?.let { target ->
                                DetailRow("Target", formatTarget(target))
                                DetailRow("Target result", if (targetReached(workout) == true) "Reached" else "Not reached")
                            }
                        }
                    }
                }
                if (details.hasSamples) {
                    item { MetricCard("Speed", details.speedKph, "km/h") }
                    item { MetricCard("Cadence", details.cadenceRpm, "rpm") }
                    item { MetricCard("Power", details.powerWatts, "W") }
                } else {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Text(
                                "Detailed metrics are unavailable for this workout.",
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Health Connect", style = MaterialTheme.typography.titleMedium)
                            Text(syncLabel(workout), color = syncColor(workout))
                            workout.syncError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (!workout.synced) {
                                Button(onClick = onRetrySync) { Text("Retry sync") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun MetricCard(label: String, summary: MetricSummary?, unit: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (summary == null) {
                Text("No data")
            } else {
                DetailRow("Average", String.format(Locale.getDefault(), "%.1f %s", summary.average, unit))
                DetailRow("Maximum", String.format(Locale.getDefault(), "%.1f %s", summary.maximum, unit))
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

internal fun syncLabel(workout: WorkoutEntity): String = when {
    workout.synced -> "Synced"
    workout.syncError != null -> "Failed"
    else -> "Pending"
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

package dev.frakw.ftmsbridge.retention

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import java.util.Locale

class RetentionViewModel internal constructor(
    private val manager: TrainingRetentionManager,
) : ViewModel() {
    val status = manager.status

    fun saveHours(hours: Int) = manager.setHours(hours)
}

internal fun parseRetentionHours(value: String): Int? = value.trim().toIntOrNull()
    ?.takeIf { it in TrainingRetentionPreferences.MIN_HOURS..TrainingRetentionPreferences.MAX_HOURS }

@Composable
fun RetentionScreen(
    status: RetentionStatus,
    onSave: (Int) -> Unit,
    onBack: () -> Unit,
) {
    var hoursText by rememberSaveable(status.configuredHours) { mutableStateOf(status.configuredHours.toString()) }
    var saved by remember { mutableStateOf(false) }
    val parsedHours = parseRetentionHours(hoursText)
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Training history retention", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) { Text("Back") }
            }
            Text(
                "Oldest workouts are removed after they have synced to Health Connect. " +
                    "Active, unsynced, and the newest completed workout are always protected.",
            )
            OutlinedTextField(
                value = hoursText,
                onValueChange = {
                    hoursText = it.filter(Char::isDigit)
                    saved = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Retain training history (hours)") },
                supportingText = {
                    Text("Default: 36 hours, approximately 20 MiB. Allowed: 1–10,000 hours.")
                },
                isError = hoursText.isNotBlank() && parsedHours == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            if ((parsedHours ?: 0) > TrainingRetentionPreferences.DEFAULT_HOURS) {
                Text(
                    "This setting may exceed the recommended 20 MiB cloud-backup target.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    parsedHours?.let(onSave)
                    saved = parsedHours != null
                },
                enabled = parsedHours != null && parsedHours != status.configuredHours,
            ) {
                Text("Save retention")
            }
            if (saved) Text("Retention saved. Cleanup will run in the background.")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Current storage", style = MaterialTheme.typography.titleMedium)
                    Text(String.format(Locale.US, "%.1f training hours retained", status.retainedHours))
                    Text(formatStorage(status.databaseBytes))
                    if (status.loading) Text("Calculating…", style = MaterialTheme.typography.bodySmall)
                }
            }
            status.warning?.let { RetentionWarningCard(it) }
            Text(
                "Android backs up automatically when the device is eligible. The app backup limit is 25 MB; " +
                    "20 MiB is used here as a safer warning target.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun RetentionWarningCard(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Backup storage warning", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(message)
        }
    }
}

internal fun formatStorage(bytes: Long): String = String.format(Locale.US, "%.1f MiB used", bytes / 1024.0 / 1024.0)

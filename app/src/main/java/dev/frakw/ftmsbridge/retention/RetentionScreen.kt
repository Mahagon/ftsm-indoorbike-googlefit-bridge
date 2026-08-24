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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import dev.frakw.ftmsbridge.R
import java.util.Locale

class RetentionViewModel internal constructor(
    private val manager: TrainingRetentionManager,
) : ViewModel() {
    val status = manager.status

    fun saveHours(hours: Int) = manager.setHours(hours)
}

internal fun parseRetentionHours(value: String): Int? = value.trim().toIntOrNull()
    ?.takeIf { it in TrainingRetentionPreferences.MIN_HOURS..TrainingRetentionPreferences.MAX_HOURS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetentionScreen(
    status: RetentionStatus,
    onSave: (Int) -> Unit,
    onBack: () -> Unit,
) {
    var hoursText by rememberSaveable(status.configuredHours) { mutableStateOf(status.configuredHours.toString()) }
    var saved by remember { mutableStateOf(false) }
    val parsedHours = parseRetentionHours(hoursText)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.training_retention)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.retention_description),
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(
                value = hoursText,
                onValueChange = {
                    hoursText = it.filter(Char::isDigit)
                    saved = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.retain_hours)) },
                supportingText = {
                    Text(stringResource(R.string.retention_default))
                },
                isError = hoursText.isNotBlank() && parsedHours == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            if ((parsedHours ?: 0) > TrainingRetentionPreferences.DEFAULT_HOURS) {
                Text(
                    stringResource(R.string.retention_large_warning),
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
                Text(stringResource(R.string.save_retention))
            }
            if (saved) Text(stringResource(R.string.retention_saved))
            Card(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Rounded.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.current_storage), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.training_hours_retained, status.retainedHours))
                        Text(stringResource(R.string.storage_used, status.databaseBytes / 1024.0 / 1024.0))
                        if (status.loading) Text(stringResource(R.string.calculating), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            status.warning?.let { RetentionWarningCard(it) }
            Text(
                stringResource(R.string.backup_explanation),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun RetentionWarningCard(message: RetentionWarning) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.backup_warning), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        when (message) {
                            RetentionWarning.BACKUP_BLOCKED -> R.string.retention_warning_backup_blocked
                            RetentionWarning.BACKUP_TARGET -> R.string.retention_warning_backup_target
                            RetentionWarning.PROTECTED_LIMIT -> R.string.retention_warning_protected
                        },
                    ),
                )
            }
        }
    }
}

internal fun formatStorage(bytes: Long): String = String.format(Locale.US, "%.1f MiB used", bytes / 1024.0 / 1024.0)

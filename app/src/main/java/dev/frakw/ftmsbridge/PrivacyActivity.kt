package dev.frakw.ftmsbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FtmsBridgeTheme {
                PrivacyScreen(
                    onBack = ::finish,
                    onOpenPolicy = { openUrl(PRIVACY_POLICY_URL) },
                    onContact = { openUrl(ISSUES_URL) },
                    onSensitiveReport = { openUrl(PRIVATE_REPORT_URL) },
                )
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    companion object {
        const val PRIVACY_POLICY_URL =
            "https://github.com/Mahagon/ftsm-indoorbike-googlefit-bridge/blob/main/PRIVACY.md"
        const val ISSUES_URL = "https://github.com/Mahagon/ftsm-indoorbike-googlefit-bridge/issues"
        const val PRIVATE_REPORT_URL =
            "https://github.com/Mahagon/ftsm-indoorbike-googlefit-bridge/security/advisories/new"
    }
}

private data class PrivacySection(val title: String, val body: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivacyScreen(
    onBack: () -> Unit,
    onOpenPolicy: () -> Unit,
    onContact: () -> Unit,
    onSensitiveReport: () -> Unit,
) {
    val sections = listOf(
        PrivacySection(stringResource(R.string.privacy_bluetooth_title), stringResource(R.string.privacy_bluetooth_body)),
        PrivacySection(stringResource(R.string.privacy_workouts_title), stringResource(R.string.privacy_workouts_body)),
        PrivacySection(stringResource(R.string.privacy_health_title), stringResource(R.string.privacy_health_body)),
        PrivacySection(stringResource(R.string.privacy_backup_title), stringResource(R.string.privacy_backup_body)),
        PrivacySection(stringResource(R.string.privacy_updates_title), stringResource(R.string.privacy_updates_body)),
        PrivacySection(stringResource(R.string.privacy_diagnostics_title), stringResource(R.string.privacy_diagnostics_body)),
        PrivacySection(stringResource(R.string.privacy_deletion_title), stringResource(R.string.privacy_deletion_body)),
        PrivacySection(stringResource(R.string.privacy_security_title), stringResource(R.string.privacy_security_body)),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.privacy_summary), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.privacy_last_updated),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(sections) { section ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium)
                    Text(section.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.privacy_contact_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.privacy_contact_body), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onOpenPolicy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.privacy_open_full_policy))
                    }
                    OutlinedButton(onClick = onContact, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.privacy_contact_issues))
                    }
                    OutlinedButton(onClick = onSensitiveReport, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.privacy_contact_private))
                    }
                }
            }
        }
    }
}

package com.soldnearby.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soldnearby.app.BuildConfig
import com.soldnearby.app.R
import com.soldnearby.app.data.AppSettings

private const val BUY_ME_A_COFFEE_URL = "https://buymeacoffee.com/1900xd"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Recent sales only", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Show only the last 12 months of sales instead of all bundled years. " +
                            "The app itself only ever holds a rolling 10-year window of sales data, " +
                            "updated periodically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.recentOnly,
                    onCheckedChange = { onSettingsChange(settings.copy(recentOnly = it)) }
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Recent sales heatmap", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Shows how many sales there have been nearby, as a translucent overlay. " +
                            "Red means more sales in that area; blue means fewer. Always covers " +
                            "the last 2 years, whatever \"Recent sales only\" is set to.\n\n" +
                            "It counts sales, not properties, so it won't simply match the dots: " +
                            "every flat in a block shares one postcode and so draws a single dot, " +
                            "but each of its sales still counts towards the colour.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.heatmapEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(heatmapEnabled = it)) }
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Dark mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Use a dark theme regardless of your device's system setting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.darkModeEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(darkModeEnabled = it)) }
                )
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Data sources", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.attribution_text), style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(12.dp))
            val uriHandler = LocalUriHandler.current
            val sourceLandRegistryUrl = stringResource(R.string.source_land_registry_url)
            val sourceOnspdUrl = stringResource(R.string.source_onspd_url)
            Text(
                stringResource(R.string.source_land_registry_label),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.clickable {
                    uriHandler.openUri(sourceLandRegistryUrl)
                }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.source_onspd_label),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.clickable {
                    uriHandler.openUri(sourceOnspdUrl)
                }
            )

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.government_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Support this app", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Buy me a coffee",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.clickable { uriHandler.openUri(BUY_ME_A_COFFEE_URL) }
            )

            Spacer(Modifier.height(16.dp))
            Text(
                "SoldNearbyUK ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

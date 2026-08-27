package com.menouer.capitalalgiers.ui.properties

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.menouer.capitalalgiers.game.OwnedAssetSummary
import com.menouer.rules_engine.model.AssetId

/**
 * Full-screen-ish overlay listing everything the active player owns, with
 * inline build/sell/mortgage/unmortgage actions (GameRules.md §13-16, §18).
 * Plain [Dialog] rather than navigation-compose, matching the rest of M3's
 * "tiny linear screen flow, don't add a nav library" approach
 * (CapitalAlgiersApp.kt) — this is a modal overlay on top of the board, not
 * a new destination.
 *
 * Every action button is always shown when structurally possible (e.g. you
 * own the asset) and left for the engine to accept or reject — see
 * GameSessionViewModel's "Building & mortgages" section for why validation
 * isn't duplicated here.
 */
@Composable
fun PropertyManagerDialog(
    assets: List<OwnedAssetSummary>,
    lastRejection: String?,
    onBuild: (AssetId) -> Unit,
    onSellBuilding: (AssetId) -> Unit,
    onMortgage: (AssetId) -> Unit,
    onUnmortgage: (AssetId) -> Unit,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "My properties",
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = onClose) { Text("Close") }
                }

                if (lastRejection != null) {
                    Text(
                        text = "Rejected: $lastRejection",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (assets.isEmpty()) {
                    Text(
                        "You don't own anything yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false).padding(top = 12.dp)) {
                        items(assets, key = { it.assetId }) { asset ->
                            OwnedAssetRow(
                                asset = asset,
                                onBuild = { onBuild(asset.assetId) },
                                onSellBuilding = { onSellBuilding(asset.assetId) },
                                onMortgage = { onMortgage(asset.assetId) },
                                onUnmortgage = { onUnmortgage(asset.assetId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnedAssetRow(
    asset: OwnedAssetSummary,
    onBuild: () -> Unit,
    onSellBuilding: () -> Unit,
    onMortgage: () -> Unit,
    onUnmortgage: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(asset.displayName, style = MaterialTheme.typography.titleSmall)

            val statusLine = buildString {
                if (asset.group != null) append(asset.group)
                if (asset.hasHotel) {
                    if (isNotEmpty()) append(" \u2014 ")
                    append("Hotel")
                } else if (asset.houses > 0) {
                    if (isNotEmpty()) append(" \u2014 ")
                    append("${asset.houses} house(s)")
                }
                if (asset.mortgaged) {
                    if (isNotEmpty()) append(" \u2014 ")
                    append("MORTGAGED")
                }
            }
            if (statusLine.isNotEmpty()) {
                Text(statusLine, style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Building is only meaningful for properties (group != null / houseCost != null);
                // stations/utilities never carry houses (GameRules.md §13).
                if (asset.houseCost != null && !asset.mortgaged) {
                    Button(onClick = onBuild) { Text("Build (${asset.houseCost} \u062F\u062C)") }
                }
                if (asset.houses > 0 || asset.hasHotel) {
                    Button(onClick = onSellBuilding) { Text("Sell building") }
                }
                if (!asset.mortgaged) {
                    Button(onClick = onMortgage) { Text("Mortgage (+${asset.mortgageValue} \u062F\u062C)") }
                } else {
                    Button(onClick = onUnmortgage) { Text("Unmortgage (-${asset.unmortgageCost} \u062F\u062C)") }
                }
            }
        }
    }
}
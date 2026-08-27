package com.menouer.capitalalgiers.ui.trade

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.menouer.capitalalgiers.game.CounterpartyTradeContext
import com.menouer.capitalalgiers.game.TradeAssetOption
import com.menouer.capitalalgiers.game.TradeBuilderContext
import com.menouer.capitalalgiers.game.TradeParty
import com.menouer.economy_data.Deck
import com.menouer.rules_engine.model.AssetId
import com.menouer.rules_engine.model.PlayerId

/**
 * Builds a GameRules.md §17 trade proposal step by step: pick who to trade
 * with, then what each side offers. No re-validation of trade rules here
 * (buildings-encumbered assets are already excluded from the option lists
 * by GameSessionViewModel — a plain state read, not a rule judgment); an
 * otherwise-invalid combination (e.g. insufficient cash) surfaces via
 * [lastRejection], rendered inline and left OPEN rather than dismissed, the
 * same way PropertyManagerDialog handles a rejected build/mortgage — closing
 * on any outcome would hide the reason just as it appears.
 */
@Composable
fun TradeProposalDialog(
    context: TradeBuilderContext,
    lastRejection: String?,
    counterpartyContextFor: (PlayerId) -> CounterpartyTradeContext?,
    onPropose: (
        toPlayerId: PlayerId,
        offeredCash: Int,
        requestedCash: Int,
        offeredAssets: Set<AssetId>,
        requestedAssets: Set<AssetId>,
        offeredGoojf: List<Deck>,
        requestedGoojf: List<Deck>
    ) -> Boolean,
    onClose: () -> Unit
) {
    var counterparty by remember { mutableStateOf<TradeParty?>(null) }
    var offeredCashText by remember { mutableStateOf("0") }
    var requestedCashText by remember { mutableStateOf("0") }
    val offeredAssets = remember { mutableStateOf(setOf<AssetId>()) }
    val requestedAssets = remember { mutableStateOf(setOf<AssetId>()) }
    val offeredGoojf = remember { mutableStateOf(setOf<Deck>()) }
    val requestedGoojf = remember { mutableStateOf(setOf<Deck>()) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Propose a trade",
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

                Text(
                    "Trade with:",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    context.counterparties.forEach { party ->
                        Button(onClick = {
                            counterparty = party
                            offeredAssets.value = emptySet()
                            requestedAssets.value = emptySet()
                            offeredGoojf.value = emptySet()
                            requestedGoojf.value = emptySet()
                        }) {
                            Text(party.name + if (counterparty?.playerId == party.playerId) " \u2713" else "")
                        }
                    }
                }

                val chosen = counterparty
                if (chosen != null) {
                    val toContext = counterpartyContextFor(chosen.playerId)
                    if (toContext != null) {
                        LazyColumn(modifier = Modifier.padding(top = 12.dp).weight(1f, fill = false)) {
                            item {
                                TradeSideEditor(
                                    title = "${context.fromName} offers (balance ${context.fromBalance} \u062F\u062C)",
                                    cashText = offeredCashText,
                                    onCashChange = { offeredCashText = it },
                                    assets = context.fromTradeableAssets,
                                    selectedAssets = offeredAssets.value,
                                    onToggleAsset = { id -> offeredAssets.value = toggle(offeredAssets.value, id) },
                                    goojfDecks = context.fromGoojfDecks.distinct(),
                                    selectedGoojf = offeredGoojf.value,
                                    onToggleGoojf = { deck -> offeredGoojf.value = toggle(offeredGoojf.value, deck) }
                                )
                            }
                            item {
                                TradeSideEditor(
                                    title = "${chosen.name} offers (balance ${toContext.toBalance} \u062F\u062C)",
                                    cashText = requestedCashText,
                                    onCashChange = { requestedCashText = it },
                                    assets = toContext.toTradeableAssets,
                                    selectedAssets = requestedAssets.value,
                                    onToggleAsset = { id -> requestedAssets.value = toggle(requestedAssets.value, id) },
                                    goojfDecks = toContext.toGoojfDecks.distinct(),
                                    selectedGoojf = requestedGoojf.value,
                                    onToggleGoojf = { deck -> requestedGoojf.value = toggle(requestedGoojf.value, deck) }
                                )
                            }
                        }

                        Button(
                            onClick = {
                                val accepted = onPropose(
                                    chosen.playerId,
                                    offeredCashText.toIntOrNull() ?: 0,
                                    requestedCashText.toIntOrNull() ?: 0,
                                    offeredAssets.value,
                                    requestedAssets.value,
                                    offeredGoojf.value.toList(),
                                    requestedGoojf.value.toList()
                                )
                                if (accepted) onClose()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) { Text("Propose") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TradeSideEditor(
    title: String,
    cashText: String,
    onCashChange: (String) -> Unit,
    assets: List<TradeAssetOption>,
    selectedAssets: Set<AssetId>,
    onToggleAsset: (AssetId) -> Unit,
    goojfDecks: List<Deck>,
    selectedGoojf: Set<Deck>,
    onToggleGoojf: (Deck) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(
                value = cashText,
                onValueChange = { onCashChange(it.filter { c -> c.isDigit() }) },
                label = { Text("Cash (\u062F\u062C)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            if (assets.isNotEmpty()) {
                Text("Properties", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                assets.forEach { asset ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = asset.assetId in selectedAssets,
                            onCheckedChange = { onToggleAsset(asset.assetId) }
                        )
                        Text(asset.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (goojfDecks.isNotEmpty()) {
                Text(
                    "Get Out of Jail Free cards",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp)
                )
                goojfDecks.forEach { deck ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = deck in selectedGoojf,
                            onCheckedChange = { onToggleGoojf(deck) }
                        )
                        Text(deckLabel(deck), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun <T> toggle(set: Set<T>, item: T): Set<T> = if (item in set) set - item else set + item

private fun deckLabel(deck: Deck): String = when (deck) {
    Deck.CHANCE -> "Chance"
    Deck.CAPITAL_CHEST -> "Community Chest"
}
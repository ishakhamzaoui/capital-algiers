package com.menouer.capitalalgiers.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menouer.capitalalgiers.game.AuctionOffer
import com.menouer.capitalalgiers.game.GameSessionUiState
import com.menouer.capitalalgiers.game.PendingTradeSummary
import com.menouer.capitalalgiers.game.PurchaseOffer
import com.menouer.capitalalgiers.game.describe
import com.menouer.economy_data.BoardSpace
import com.menouer.economy_data.Deck
import com.menouer.economy_data.SpaceType
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.PlayerState
import com.menouer.rules_engine.model.TurnPhase

/** Distinguishing background tint per space type, purely for at-a-glance scanning (no group colors yet — that's M9's job). */
private fun colorFor(type: SpaceType): Color = when (type) {
    SpaceType.GO -> Color(0xFFBEE7BE)
    SpaceType.PROPERTY -> Color(0xFFF5F5F5)
    SpaceType.COMMUNITY_CHEST -> Color(0xFFCCE0FF)
    SpaceType.TAX -> Color(0xFFFFD8A8)
    SpaceType.STATION -> Color(0xFFE0D4F7)
    SpaceType.CHANCE -> Color(0xFFFFF3B0)
    SpaceType.JAIL -> Color(0xFFFFC9C9)
    SpaceType.UTILITY -> Color(0xFFD4F1F4)
    SpaceType.FREE_PARKING -> Color(0xFFE9ECEF)
    SpaceType.GO_TO_JAIL -> Color(0xFFFF8787)
}

private val tokenColors = listOf(
    Color(0xFFE03131), Color(0xFF1971C2), Color(0xFF2F9E44), Color(0xFFF08C00)
)

/**
 * Bare-bones, throwaway-quality board screen per DevelopmentRoadmap.md M3
 * ("do not invest in visuals here, that's M6/M9"). Uses developerName
 * (Latin) as the on-screen label — RTL Arabic UI is explicitly out of scope
 * for this milestone (SRS §5/§6 belong to M6/M9).
 *
 * Session 2 adds the [TurnPanel]: roll, auto-resolved landing, buy/decline,
 * and end turn. This pass adds real interactive auction bidding
 * ([AuctionPanel]) in place of the earlier all-pass stand-in — everything
 * needed to actually play through a turn end to end, including a declined
 * purchase.
 */
@Composable
fun BoardScreen(
    uiState: GameSessionUiState,
    lastRejection: String?,
    purchaseOffer: PurchaseOffer?,
    auctionOffer: AuctionOffer?,
    onRollDice: () -> Unit,
    onBuy: () -> Unit,
    onDecline: () -> Unit,
    onPlaceBid: (Int) -> Unit,
    onPassAuction: () -> Unit,
    onManageProperties: () -> Unit,
    onProposeTrade: () -> Unit,
    tradeSummary: PendingTradeSummary?,
    onAcceptTrade: () -> Unit,
    onDeclineTrade: () -> Unit,
    onEndTurn: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gameState = uiState.gameState

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Capital Algiers", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onExit) { Text("Exit to setup") }
        }

        Scoreboard(
            players = gameState.players,
            playerNames = uiState.playerNames,
            activePlayerId = gameState.activePlayerId,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        Box(modifier = Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                gridItems(gameState.config.spaces.sortedBy { it.index }, key = { it.index }) { space ->
                    BoardSpaceCell(
                        space = space,
                        occupants = gameState.players.filter { it.position == space.index },
                        allPlayers = gameState.players
                    )
                }
            }
        }

        TurnPanel(
            uiState = uiState,
            lastRejection = lastRejection,
            purchaseOffer = purchaseOffer,
            auctionOffer = auctionOffer,
            onRollDice = onRollDice,
            onBuy = onBuy,
            onDecline = onDecline,
            onPlaceBid = onPlaceBid,
            onPassAuction = onPassAuction,
            onManageProperties = onManageProperties,
            onProposeTrade = onProposeTrade,
            tradeSummary = tradeSummary,
            onAcceptTrade = onAcceptTrade,
            onDeclineTrade = onDeclineTrade,
            onEndTurn = onEndTurn,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
    }
}

@Composable
private fun TurnPanel(
    uiState: GameSessionUiState,
    lastRejection: String?,
    purchaseOffer: PurchaseOffer?,
    auctionOffer: AuctionOffer?,
    onRollDice: () -> Unit,
    onBuy: () -> Unit,
    onDecline: () -> Unit,
    onPlaceBid: (Int) -> Unit,
    onPassAuction: () -> Unit,
    onManageProperties: () -> Unit,
    onProposeTrade: () -> Unit,
    tradeSummary: PendingTradeSummary?,
    onAcceptTrade: () -> Unit,
    onDeclineTrade: () -> Unit,
    onEndTurn: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gameState = uiState.gameState
    val activeName = uiState.playerNames[gameState.activePlayerId] ?: gameState.activePlayerId

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            when (gameState.phase) {
                TurnPhase.AWAITING_ROLL ->
                    PhaseRow("$activeName's turn — roll the dice") { Button(onClick = onRollDice) { Text("Roll") } }

                TurnPhase.AWAITING_JAIL_DECISION ->
                    PhaseRow("$activeName is in jail — attempt to roll doubles") {
                        Button(onClick = onRollDice) { Text("Roll") }
                    }

                TurnPhase.RESOLVING_LANDING ->
                    Text("Resolving landing\u2026", style = MaterialTheme.typography.bodyMedium)

                TurnPhase.AWAITING_PURCHASE_DECISION -> {
                    if (purchaseOffer != null) {
                        Text(
                            "$activeName can buy ${purchaseOffer.displayName} for ${purchaseOffer.price} \u062F\u062C",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(onClick = onBuy) { Text("Buy") }
                            Button(onClick = onDecline) { Text("Decline (go to auction)") }
                        }
                    }
                }

                TurnPhase.IN_AUCTION -> {
                    if (auctionOffer != null) {
                        AuctionPanel(auctionOffer = auctionOffer, onPlaceBid = onPlaceBid, onPassAuction = onPassAuction)
                    }
                }

                TurnPhase.AWAITING_OPTIONAL_ACTIONS ->
                    PhaseRow("$activeName may act, or end the turn") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onManageProperties) { Text("Manage properties") }
                            Button(onClick = onProposeTrade) { Text("Propose trade") }
                            Button(onClick = onEndTurn) { Text("End turn") }
                        }
                    }

                TurnPhase.IN_TRADE -> {
                    if (tradeSummary != null) {
                        TradeResponsePanel(summary = tradeSummary, onAccept = onAcceptTrade, onDecline = onDeclineTrade)
                    }
                }

                TurnPhase.GAME_OVER -> {
                    val winner = gameState.nonBankruptPlayers.firstOrNull()
                    val winnerName = winner?.let { uiState.playerNames[it.id] ?: it.id } ?: "No one"
                    Text("Game over \u2014 $winnerName wins!", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (lastRejection != null) {
                Text(
                    text = "Rejected: $lastRejection",
                    color = Color(0xFFC92A2A),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            val logLines = uiState.recentEvents.mapNotNull { it.describe(gameState.config, uiState.playerNames) }
            if (logLines.isNotEmpty()) {
                Text(
                    text = "What just happened",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp)
                )
                LazyColumn(modifier = Modifier.heightIn(max = 140.dp)) {
                    items(logLines) { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseRow(label: String, actions: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        actions()
    }
}

/**
 * GameRules.md §7's auction procedure: opening bids follow the configured
 * minimum, each new bid must clear the configured increment over the
 * current highest, and eligible players may bid or pass in turn (here,
 * hotseat "turn" = seating order among whoever hasn't passed yet — see
 * GameSessionViewModel.pendingAuctionOffer). The bid field is keyed on
 * (assetId, currentBidderId, highestBid) so it resets to the new minimum
 * every time control passes to a different player or the price moves,
 * rather than carrying over a stale typed amount.
 */
@Composable
private fun AuctionPanel(
    auctionOffer: AuctionOffer,
    onPlaceBid: (Int) -> Unit,
    onPassAuction: () -> Unit
) {
    var bidText by remember(auctionOffer.assetId, auctionOffer.currentBidderId, auctionOffer.highestBid) {
        mutableStateOf(auctionOffer.minimumValidBid.toString())
    }

    Column {
        Text(
            text = "Auction: ${auctionOffer.displayName}",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = if (auctionOffer.highestBidderName != null) {
                "Highest bid: ${auctionOffer.highestBid} \u062F\u062C (${auctionOffer.highestBidderName})"
            } else {
                "No bids yet"
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "${auctionOffer.currentBidderName}'s turn to bid or pass (minimum ${auctionOffer.minimumValidBid} \u062F\u062C)",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = bidText,
                onValueChange = { bidText = it.filter { c -> c.isDigit() } },
                label = { Text("Bid amount") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { bidText.toIntOrNull()?.let(onPlaceBid) }) { Text("Bid") }
            Button(onClick = onPassAuction) { Text("Pass") }
        }
    }
}

/**
 * GameRules.md §17: the counterparty accepts or declines exactly as
 * proposed — no counter-offer negotiation in V1's engine (proposeTrade
 * takes a whole TradeProposal, there's no partial-modify path). Both sides
 * see the same summary on this hotseat device; only onAccept/onDecline
 * matter, since MultiplayerProtocol.md's "only the toPlayer may accept" is
 * a networked-lobby concept this local prototype doesn't need to enforce.
 */
@Composable
private fun TradeResponsePanel(
    summary: PendingTradeSummary,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Column {
        Text(
            text = "${summary.fromName} proposes a trade with ${summary.toName}",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = "${summary.fromName} offers: " + describeTradeSide(
                cash = summary.offeredCash,
                assets = summary.offeredAssetNames,
                goojf = summary.offeredGoojf
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = "${summary.fromName} wants: " + describeTradeSide(
                cash = summary.requestedCash,
                assets = summary.requestedAssetNames,
                goojf = summary.requestedGoojf
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = onAccept) { Text("Accept") }
            Button(onClick = onDecline) { Text("Decline") }
        }
    }
}

private fun describeTradeSide(cash: Int, assets: List<String>, goojf: List<Deck>): String {
    val parts = mutableListOf<String>()
    if (cash > 0) parts += "$cash \u062F\u062C"
    parts += assets
    parts += goojf.map { deck ->
        when (deck) {
            Deck.CHANCE -> "Chance GOOJF card"
            Deck.CAPITAL_CHEST -> "Community Chest GOOJF card"
        }
    }
    return if (parts.isEmpty()) "nothing" else parts.joinToString(", ")
}

@Composable
private fun Scoreboard(
    players: List<PlayerState>,
    playerNames: Map<PlayerId, String>,
    activePlayerId: PlayerId,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            players.forEachIndexed { index, player ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val label = playerNames[player.id] ?: player.id
                    val marker = if (player.id == activePlayerId) "\u25B6 " else "   "
                    Text(
                        text = "$marker$label" + if (player.bankrupt) " (bankrupt)" else "",
                        color = tokenColors[index % tokenColors.size],
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "${player.balance} \u062F\u062C",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardSpaceCell(
    space: BoardSpace,
    occupants: List<PlayerState>,
    allPlayers: List<PlayerState>
) {
    Column(
        modifier = Modifier
            .background(colorFor(space.type))
            .border(1.dp, Color.Black.copy(alpha = 0.2f))
            .padding(4.dp)
            .size(width = 84.dp, height = 84.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "${space.index}",
            fontSize = 10.sp,
            color = Color.Gray
        )
        Text(
            text = space.developerName,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 3
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            occupants.forEach { player ->
                val colorIndex = allPlayers.indexOf(player)
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(tokenColors[colorIndex % tokenColors.size], CircleShape)
                )
            }
        }
    }
}
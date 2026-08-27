package com.menouer.capitalalgiers.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menouer.capitalalgiers.game.GameSessionViewModel
import com.menouer.capitalalgiers.ui.board.BoardScreen
import com.menouer.capitalalgiers.ui.properties.PropertyManagerDialog
import com.menouer.capitalalgiers.ui.results.MatchResultsScreen
import com.menouer.capitalalgiers.ui.setup.SetupScreen
import com.menouer.capitalalgiers.ui.trade.TradeProposalDialog
import com.menouer.rules_engine.model.TurnPhase

/**
 * The app's entire screen graph: no game yet -> SetupScreen; game over ->
 * MatchResultsScreen; otherwise -> BoardScreen, with optional
 * PropertyManagerDialog / TradeProposalDialog overlays. A plain phase-check
 * switch rather than navigation-compose, since M3 is explicitly a
 * throwaway-quality prototype with a tiny, linear screen flow — adding a
 * navigation library isn't earning its cost here.
 *
 * pendingPurchaseOffer()/pendingAuctionOffer()/activePlayerJailOptions()/
 * ownedAssetSummaries()/tradeBuilderContext()/pendingTradeSummary()/
 * finalStandings() and lastRejection are read directly from the ViewModel on
 * every recomposition (cheap, pure derivations) rather than being folded
 * into GameSessionUiState itself, since they're presentation concerns
 * derived FROM the state rather than part of the authoritative state the
 * engine owns.
 *
 * showPropertyManager/showTradeProposal are pure UI navigation state (which
 * overlay is showing), not game state — they never affect GameState or
 * TurnPhase, so they live here as local Compose state rather than in the
 * ViewModel. Both are force-closed once the engine moves phase away from
 * where they make sense (IN_TRADE for the proposal editor, GAME_OVER for
 * either overlay), so a stale editor never lingers over a screen it no
 * longer applies to.
 */
@Composable
fun CapitalAlgiersApp(viewModel: GameSessionViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val lastRejection by viewModel.lastRejection.collectAsState()
    var showPropertyManager by remember { mutableStateOf(false) }
    var showTradeProposal by remember { mutableStateOf(false) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        val current = uiState
        if (current == null) {
            SetupScreen(
                onStartGame = { names -> viewModel.startNewGame(names) },
                modifier = Modifier.padding(innerPadding)
            )
        } else if (current.gameState.phase == TurnPhase.GAME_OVER) {
            showPropertyManager = false
            showTradeProposal = false
            val standings = viewModel.finalStandings()
            if (standings != null) {
                MatchResultsScreen(
                    standings = standings,
                    onNewGame = { viewModel.exitToSetup() },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        } else {
            if (current.gameState.phase == TurnPhase.IN_TRADE) {
                showTradeProposal = false
            }

            BoardScreen(
                uiState = current,
                lastRejection = lastRejection,
                purchaseOffer = viewModel.pendingPurchaseOffer(),
                auctionOffer = viewModel.pendingAuctionOffer(),
                jailOptions = viewModel.activePlayerJailOptions(),
                onRollDice = { viewModel.rollDice() },
                onPayFine = { viewModel.payJailFineVoluntarily() },
                onUseGoojfCard = { viewModel.useGetOutOfJailCard() },
                onBuy = { viewModel.buyPendingAsset() },
                onDecline = { viewModel.declinePendingAsset() },
                onPlaceBid = { amount -> viewModel.placeBid(amount) },
                onPassAuction = { viewModel.passCurrentBidder() },
                onManageProperties = { showPropertyManager = true },
                onProposeTrade = { showTradeProposal = true },
                tradeSummary = viewModel.pendingTradeSummary(),
                onAcceptTrade = { viewModel.respondToTrade(accept = true) },
                onDeclineTrade = { viewModel.respondToTrade(accept = false) },
                onEndTurn = { viewModel.endTurn() },
                onExit = { viewModel.exitToSetup() },
                modifier = Modifier.padding(innerPadding)
            )

            if (showPropertyManager) {
                PropertyManagerDialog(
                    assets = viewModel.ownedAssetSummaries(),
                    lastRejection = lastRejection,
                    onBuild = { assetId -> viewModel.buildOnAsset(assetId) },
                    onSellBuilding = { assetId -> viewModel.sellBuildingOnAsset(assetId) },
                    onMortgage = { assetId -> viewModel.mortgageAsset(assetId) },
                    onUnmortgage = { assetId -> viewModel.unmortgageAsset(assetId) },
                    onClose = { showPropertyManager = false }
                )
            }

            if (showTradeProposal) {
                val builderContext = viewModel.tradeBuilderContext()
                if (builderContext != null) {
                    TradeProposalDialog(
                        context = builderContext,
                        lastRejection = lastRejection,
                        counterpartyContextFor = { playerId -> viewModel.counterpartyTradeContext(playerId) },
                        onPropose = { toPlayerId, offeredCash, requestedCash, offeredAssets, requestedAssets, offeredGoojf, requestedGoojf ->
                            viewModel.proposeTrade(
                                toPlayerId,
                                offeredCash,
                                requestedCash,
                                offeredAssets,
                                requestedAssets,
                                offeredGoojf,
                                requestedGoojf
                            )
                        },
                        onClose = { showTradeProposal = false }
                    )
                }
            }
        }
    }
}
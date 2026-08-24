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
import com.menouer.capitalalgiers.ui.setup.SetupScreen

/**
 * The app's entire screen graph: no game yet -> SetupScreen; game started ->
 * BoardScreen, with an optional PropertyManagerDialog overlay. A plain
 * null-check switch rather than navigation-compose, since M3 is explicitly a
 * throwaway-quality prototype with a tiny, linear screen flow — adding a
 * navigation library isn't earning its cost here.
 *
 * pendingPurchaseOffer()/pendingAuctionOffer()/ownedAssetSummaries() and
 * lastRejection are read directly from the ViewModel on every recomposition
 * (cheap, pure derivations) rather than being folded into GameSessionUiState
 * itself, since they're presentation concerns derived FROM the state rather
 * than part of the authoritative state the engine owns.
 *
 * showPropertyManager is pure UI navigation state (which overlay is showing),
 * not game state — it never affects GameState or TurnPhase, so it lives here
 * as local Compose state rather than in the ViewModel.
 */
@Composable
fun CapitalAlgiersApp(viewModel: GameSessionViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val lastRejection by viewModel.lastRejection.collectAsState()
    var showPropertyManager by remember { mutableStateOf(false) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        val current = uiState
        if (current == null) {
            SetupScreen(
                onStartGame = { names -> viewModel.startNewGame(names) },
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            BoardScreen(
                uiState = current,
                lastRejection = lastRejection,
                purchaseOffer = viewModel.pendingPurchaseOffer(),
                auctionOffer = viewModel.pendingAuctionOffer(),
                onRollDice = { viewModel.rollDice() },
                onBuy = { viewModel.buyPendingAsset() },
                onDecline = { viewModel.declinePendingAsset() },
                onPlaceBid = { amount -> viewModel.placeBid(amount) },
                onPassAuction = { viewModel.passCurrentBidder() },
                onManageProperties = { showPropertyManager = true },
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
        }
    }
}
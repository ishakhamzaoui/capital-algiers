package com.menouer.capitalalgiers.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menouer.capitalalgiers.game.GameSessionViewModel
import com.menouer.capitalalgiers.ui.board.BoardScreen
import com.menouer.capitalalgiers.ui.setup.SetupScreen

/**
 * The app's entire screen graph: no game yet -> SetupScreen; game started ->
 * BoardScreen. A plain null-check switch rather than navigation-compose,
 * since M3 is explicitly a throwaway-quality prototype with a tiny, linear
 * screen flow — adding a navigation library isn't earning its cost here.
 *
 * pendingPurchaseOffer() and lastRejection are read directly from the
 * ViewModel on every recomposition (cheap, pure derivations) rather than
 * being folded into GameSessionUiState itself, since they're presentation
 * concerns derived FROM the state rather than part of the authoritative
 * state the engine owns.
 */
@Composable
fun CapitalAlgiersApp(viewModel: GameSessionViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val lastRejection by viewModel.lastRejection.collectAsState()

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
                onRollDice = { viewModel.rollDice() },
                onBuy = { viewModel.buyPendingAsset() },
                onDecline = { viewModel.declinePendingAsset() },
                onSkipAuction = { viewModel.skipAuctionWithAllPasses() },
                onEndTurn = { viewModel.endTurn() },
                onExit = { viewModel.exitToSetup() },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
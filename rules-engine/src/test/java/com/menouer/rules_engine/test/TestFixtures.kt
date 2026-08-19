package com.menouer.rules_engine.test

import com.menouer.economy_data.BoardConfig
import com.menouer.economy_data.SampleEconomyData
import com.menouer.rules_engine.model.AssetState
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.PlayerState
import com.menouer.rules_engine.model.TurnPhase

/**
 * Shared test fixtures so every session's test file builds a fresh GameState
 * the same way, per GameRules.md §3 (Setup): all tokens start on GO, starting
 * money from BoardEconomy.md, host/engine acts as the Bank, deck order is
 * whatever the config lists (no shuffle applied here — tests that care about
 * card draw order should build their own deck list explicitly).
 */
object TestFixtures {

    /** A freshly set-up game with the given player ids, all starting on GO. */
    fun newGame(
        playerIds: List<PlayerId>,
        config: BoardConfig = SampleEconomyData.boardConfig
    ): GameState {
        require(playerIds.size >= 2) { "a match requires at least two players (SRS.md FR-004)" }

        val players = playerIds.map {
            PlayerState(
                id = it,
                balance = config.constants.startingMoney,
                position = 0
            )
        }

        val assets: Map<String, AssetState> =
            (config.properties.map { it.id } + config.stations.map { it.id } + config.utilities.map { it.id })
                .associateWith { AssetState(id = it) }

        return GameState(
            stateVersion = 0,
            config = config,
            players = players,
            assets = assets,
            activePlayerId = players.first().id,
            phase = TurnPhase.AWAITING_ROLL,
            bankHouses = config.constants.totalHouses,
            bankHotels = config.constants.totalHotels,
            chanceDeck = config.chanceDeck.map { it.id },
            chestDeck = config.chestDeck.map { it.id }
        )
    }
}

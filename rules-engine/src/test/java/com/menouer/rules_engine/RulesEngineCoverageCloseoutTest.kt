package com.menouer.rules_engine

import com.menouer.rules_engine.dice.DiceRoll
import com.menouer.rules_engine.model.EngineError
import com.menouer.rules_engine.model.EngineResult
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.TradeProposal
import com.menouer.rules_engine.model.TurnPhase
import com.menouer.rules_engine.test.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rejection branches that are correctly implemented but weren't exercised by any
 * test in Sessions 1-8, found via a manual audit of RulesEngineImpl.kt against
 * every existing test file (a substitute for actually running JaCoCo, which
 * isn't possible in this environment -- see the M1 coverage requirement in
 * DevelopmentRoadmap.md). Please still run the real coverage report locally;
 * this closes the gaps I could find by inspection, not a guarantee of >=90%.
 */
class RulesEngineCoverageCloseoutTest {

    private val engine = RulesEngineImpl()

    private fun rejected(result: EngineResult) = result as EngineResult.Rejected

    private fun GameState.own(assetId: String, ownerId: PlayerId): GameState =
        copy(assets = assets + (assetId to assets.getValue(assetId).copy(ownerId = ownerId)))

    @Test
    fun `applyRoll is rejected when the active player id doesn't match any real player`() {
        val state = TestFixtures.newGame(listOf("p1", "p2")).copy(activePlayerId = "ghost")
        val result = rejected(engine.applyRoll(state, "ghost", DiceRoll(1, 2)))
        assertEquals(EngineError.PLAYER_NOT_FOUND, result.reason)
    }

    @Test
    fun `applyRoll is rejected for a bankrupt active player`() {
        var state = TestFixtures.newGame(listOf("p1", "p2"))
        state = state.copy(players = state.players.replace(state.player("p1").copy(bankrupt = true)))
        val result = rejected(engine.applyRoll(state, "p1", DiceRoll(1, 2)))
        assertEquals(EngineError.PLAYER_BANKRUPT, result.reason)
    }

    @Test
    fun `resolveLanding is rejected outside the RESOLVING_LANDING phase`() {
        val state = TestFixtures.newGame(listOf("p1", "p2")).copy(phase = TurnPhase.AWAITING_ROLL)
        val result = rejected(engine.resolveLanding(state))
        assertEquals(EngineError.WRONG_PHASE, result.reason)
    }

    @Test
    fun `jailAction is rejected when the player id doesn't match any real player`() {
        val state = TestFixtures.newGame(listOf("p1", "p2"))
            .copy(activePlayerId = "ghost", phase = TurnPhase.AWAITING_JAIL_DECISION)
        val result = rejected(engine.jailAction(state, "ghost", JailAction.PAY_FINE))
        assertEquals(EngineError.PLAYER_NOT_FOUND, result.reason)
    }

    @Test
    fun `sellBuilding is rejected for a property the player doesn't own`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p2")
        state = state.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS) // p1 is active, p2 owns Dergana
        val result = rejected(engine.sellBuilding(state, "p1", "Dergana"))
        assertEquals(EngineError.ASSET_NOT_OWNED_BY_PLAYER, result.reason)
    }

    @Test
    fun `mortgage is rejected for an asset id that doesn't exist`() {
        val state = TestFixtures.newGame(listOf("p1", "p2")).copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS)
        val result = rejected(engine.mortgage(state, "p1", "NotARealAsset"))
        assertEquals(EngineError.ASSET_NOT_FOUND, result.reason)
    }

    @Test
    fun `unmortgage is rejected for an asset id that doesn't exist`() {
        val state = TestFixtures.newGame(listOf("p1", "p2")).copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS)
        val result = rejected(engine.unmortgage(state, "p1", "NotARealAsset"))
        assertEquals(EngineError.ASSET_NOT_FOUND, result.reason)
    }

    @Test
    fun `proposeTrade is rejected when fromPlayerId doesn't match any real player`() {
        val state = TestFixtures.newGame(listOf("p1", "p2"))
        val result = rejected(engine.proposeTrade(state, TradeProposal(fromPlayerId = "ghost", toPlayerId = "p2")))
        assertEquals(EngineError.PLAYER_NOT_FOUND, result.reason)
    }

    @Test
    fun `proposeTrade is rejected once the game is over`() {
        val state = TestFixtures.newGame(listOf("p1", "p2")).copy(phase = TurnPhase.GAME_OVER)
        val result = rejected(engine.proposeTrade(state, TradeProposal(fromPlayerId = "p1", toPlayerId = "p2")))
        assertEquals(EngineError.WRONG_PHASE, result.reason)
    }

    @Test
    fun `resolveTrade is rejected outside the IN_TRADE phase`() {
        val state = TestFixtures.newGame(listOf("p1", "p2")).copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS)
        val result = rejected(engine.resolveTrade(state, accept = true))
        assertEquals(EngineError.WRONG_PHASE, result.reason)
    }

    @Test
    fun `declinePurchase is rejected outside the purchase-decision phase`() {
        val state = TestFixtures.newGame(listOf("p1", "p2")).copy(phase = TurnPhase.AWAITING_ROLL)
        val result = rejected(engine.declinePurchase(state, "p1"))
        assertEquals(EngineError.WRONG_PHASE, result.reason)
    }

    @Test
    fun `declinePurchase is rejected for a player who is not the active player`() {
        val state = TestFixtures.newGame(listOf("p1", "p2")).copy(phase = TurnPhase.AWAITING_PURCHASE_DECISION)
        val result = rejected(engine.declinePurchase(state, "p2"))
        assertEquals(EngineError.NOT_ACTIVE_PLAYER, result.reason)
    }

    @Test
    fun `placeBid is rejected outside the IN_AUCTION phase`() {
        val state = TestFixtures.newGame(listOf("p1", "p2")).copy(phase = TurnPhase.AWAITING_ROLL)
        val result = rejected(engine.placeBid(state, "p1", 1_000))
        assertEquals(EngineError.WRONG_PHASE, result.reason)
    }

    @Test
    fun `passAuction is rejected outside the IN_AUCTION phase`() {
        val state = TestFixtures.newGame(listOf("p1", "p2")).copy(phase = TurnPhase.AWAITING_ROLL)
        val result = rejected(engine.passAuction(state, "p1"))
        assertEquals(EngineError.WRONG_PHASE, result.reason)
    }
}
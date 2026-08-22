package com.menouer.rules_engine

import com.menouer.economy_data.Deck
import com.menouer.rules_engine.model.EngineError
import com.menouer.rules_engine.model.EngineResult
import com.menouer.rules_engine.model.GameEvent
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.TradeProposal
import com.menouer.rules_engine.model.TurnPhase
import com.menouer.rules_engine.test.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesEngineTradeTest {

    private val engine = RulesEngineImpl()

    private fun applied(result: EngineResult) = result as EngineResult.Applied
    private fun rejected(result: EngineResult) = result as EngineResult.Rejected

    private fun GameState.own(assetId: String, ownerId: PlayerId, houses: Int = 0, hasHotel: Boolean = false): GameState =
        copy(assets = assets + (assetId to assets.getValue(assetId).copy(ownerId = ownerId, houses = houses, hasHotel = hasHotel)))

    private fun GameState.withCard(playerId: PlayerId, deck: Deck): GameState =
        copy(players = players.replace(player(playerId).copy(getOutOfJailCards = player(playerId).getOutOfJailCards + deck)))

    // --- Proposing pauses the game ---

    @Test
    fun `proposing a valid trade pauses the game to IN_TRADE and records it as pending`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p1")
        state = state.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS)

        val trade = TradeProposal(fromPlayerId = "p1", toPlayerId = "p2", offeredAssets = setOf("Dergana"), requestedCash = 1_000)
        val result = applied(engine.proposeTrade(state, trade))

        assertEquals(TurnPhase.IN_TRADE, result.newState.phase)
        assertEquals(trade, result.newState.pendingTrade?.proposal)
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, result.newState.pendingTrade?.previousPhase)
        assertTrue(result.events.any { it is GameEvent.TradeProposed })
    }

    @Test
    fun `a second trade cannot be proposed while one is already pending`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p1")
        state = applied(engine.proposeTrade(state, TradeProposal("p1", "p2", offeredAssets = setOf("Dergana")))).newState

        val second = engine.proposeTrade(state, TradeProposal("p1", "p2", offeredCash = 500))
        assertEquals(EngineError.TRADE_ALREADY_PENDING, rejected(second).reason)
    }

    // --- Validation at proposal time ---

    @Test
    fun `proposing a trade offering an asset the proposer doesn't own is rejected`() {
        val state = TestFixtures.newGame(listOf("p1", "p2")) // Dergana unowned
        val result = rejected(engine.proposeTrade(state, TradeProposal("p1", "p2", offeredAssets = setOf("Dergana"))))
        assertEquals(EngineError.ASSET_NOT_OWNED_BY_PLAYER, result.reason)
    }

    @Test
    fun `proposing a trade requesting more cash than the counterparty has is rejected`() {
        val state = TestFixtures.newGame(listOf("p1", "p2"))
        val result = rejected(engine.proposeTrade(state, TradeProposal("p1", "p2", requestedCash = 999_999)))
        assertEquals(EngineError.INSUFFICIENT_FUNDS, result.reason)
    }

    @Test
    fun `proposing a trade with a property from a group that has any buildings is rejected`() {
        val state = TestFixtures.newGame(listOf("p1", "p2"))
            .own("Dergana", "p1") // no buildings itself
            .own("OuedKoriche", "p1", houses = 2) // but its sibling in the group does

        val result = rejected(engine.proposeTrade(state, TradeProposal("p1", "p2", offeredAssets = setOf("Dergana"))))
        assertEquals(EngineError.MUST_SELL_BUILDINGS_FIRST, result.reason)
    }

    @Test
    fun `proposing a trade for a Get Out of Jail Free card the proposer doesn't hold is rejected`() {
        val state = TestFixtures.newGame(listOf("p1", "p2"))
        val result = rejected(engine.proposeTrade(state, TradeProposal("p1", "p2", offeredGetOutOfJailCards = listOf(Deck.CHANCE))))
        assertEquals(EngineError.NO_GET_OUT_OF_JAIL_CARD, result.reason)
    }

    @Test
    fun `trading with yourself is rejected`() {
        val state = TestFixtures.newGame(listOf("p1", "p2"))
        val result = rejected(engine.proposeTrade(state, TradeProposal("p1", "p1")))
        assertEquals(EngineError.INVALID_TRADE, result.reason)
    }

    // --- Accepting executes the atomic exchange ---

    @Test
    fun `accepting a trade exchanges cash and a property atomically and restores the previous phase`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p1")
        state = state.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS)
        val trade = TradeProposal(fromPlayerId = "p1", toPlayerId = "p2", offeredAssets = setOf("Dergana"), requestedCash = 5_000)
        state = applied(engine.proposeTrade(state, trade)).newState

        val result = applied(engine.resolveTrade(state, accept = true))

        assertEquals("p2", result.newState.assets.getValue("Dergana").ownerId)
        assertEquals(155_000, result.newState.player("p1").balance) // +5,000 received
        assertEquals(145_000, result.newState.player("p2").balance) // -5,000 paid
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, result.newState.phase) // restored
        assertNull(result.newState.pendingTrade)
        assertTrue(result.events.any { it is GameEvent.TradeResolved && it.accepted })
    }

    @Test
    fun `accepting a trade swaps Get Out of Jail Free cards and tags them with the right deck`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).withCard("p1", Deck.CHANCE)
        val trade = TradeProposal(fromPlayerId = "p1", toPlayerId = "p2", offeredGetOutOfJailCards = listOf(Deck.CHANCE))
        state = applied(engine.proposeTrade(state, trade)).newState

        val result = applied(engine.resolveTrade(state, accept = true))

        assertTrue(result.newState.player("p1").getOutOfJailCards.isEmpty())
        assertEquals(listOf(Deck.CHANCE), result.newState.player("p2").getOutOfJailCards)
    }

    @Test
    fun `declining a trade restores the previous phase without transferring anything`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p1")
        state = state.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS)
        val trade = TradeProposal(fromPlayerId = "p1", toPlayerId = "p2", offeredAssets = setOf("Dergana"), requestedCash = 5_000)
        state = applied(engine.proposeTrade(state, trade)).newState

        val result = applied(engine.resolveTrade(state, accept = false))

        assertEquals("p1", result.newState.assets.getValue("Dergana").ownerId) // unchanged
        assertEquals(150_000, result.newState.player("p1").balance)
        assertEquals(150_000, result.newState.player("p2").balance)
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, result.newState.phase)
        assertNull(result.newState.pendingTrade)
        assertFalse(result.events.any { it is GameEvent.TradeResolved && it.accepted })
    }

    @Test
    fun `resolveTrade is rejected when there is no pending trade`() {
        val state = TestFixtures.newGame(listOf("p1", "p2")).copy(phase = TurnPhase.IN_TRADE)
        val result = rejected(engine.resolveTrade(state, accept = true))
        assertEquals(EngineError.INVALID_TRADE, result.reason)
    }

    // --- Two-sided trade (assets + cash both directions) ---

    @Test
    fun `a two-sided trade exchanges assets and cash in both directions in one atomic commit`() {
        var state = TestFixtures.newGame(listOf("p1", "p2"))
            .own("Dergana", "p1")
            .own("Sonelgaz", "p2")
        val trade = TradeProposal(
            fromPlayerId = "p1",
            toPlayerId = "p2",
            offeredAssets = setOf("Dergana"),
            offeredCash = 1_000,
            requestedAssets = setOf("Sonelgaz"),
            requestedCash = 2_000
        )
        state = applied(engine.proposeTrade(state, trade)).newState

        val result = applied(engine.resolveTrade(state, accept = true))

        assertEquals("p2", result.newState.assets.getValue("Dergana").ownerId)
        assertEquals("p1", result.newState.assets.getValue("Sonelgaz").ownerId)
        assertEquals(151_000, result.newState.player("p1").balance) // -1,000 + 2,000
        assertEquals(149_000, result.newState.player("p2").balance) // +1,000 - 2,000
    }
}
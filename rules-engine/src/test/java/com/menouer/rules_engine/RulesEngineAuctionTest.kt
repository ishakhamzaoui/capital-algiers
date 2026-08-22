package com.menouer.rules_engine

import com.menouer.rules_engine.model.EngineError
import com.menouer.rules_engine.model.EngineResult
import com.menouer.rules_engine.model.GameEvent
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.TurnPhase
import com.menouer.rules_engine.test.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesEngineAuctionTest {

    private val engine = RulesEngineImpl()

    private fun applied(result: EngineResult) = result as EngineResult.Applied
    private fun rejected(result: EngineResult) = result as EngineResult.Rejected

    private fun GameState.atPosition(playerId: PlayerId, position: Int): GameState =
        copy(players = players.replace(player(playerId).copy(position = position)))

    private fun GameState.withBalance(playerId: PlayerId, balance: Int): GameState =
        copy(players = players.replace(player(playerId).copy(balance = balance)))

    /** p1 is standing on Dergana (index 1, price 6,000), unowned, awaiting a purchase decision. */
    private fun purchaseDecisionState(playerIds: List<PlayerId> = listOf("p1", "p2")): GameState =
        TestFixtures.newGame(playerIds).atPosition("p1", 1).copy(phase = TurnPhase.AWAITING_PURCHASE_DECISION)

    // --- Direct purchase ---

    @Test
    fun `buying the pending asset transfers ownership and charges the listed price`() {
        val state = purchaseDecisionState()

        val result = applied(engine.buyAsset(state, "p1", "Dergana"))

        assertEquals("p1", result.newState.assets.getValue("Dergana").ownerId)
        assertEquals(144_000, result.newState.player("p1").balance) // 150,000 - 6,000
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, result.newState.phase)
        assertTrue(result.events.any { it is GameEvent.AssetPurchased })
    }

    @Test
    fun `buying is rejected if the player can't afford the listed price`() {
        var state = purchaseDecisionState()
        state = state.withBalance("p1", 100)

        val result = rejected(engine.buyAsset(state, "p1", "Dergana"))
        assertEquals(EngineError.INSUFFICIENT_FUNDS, result.reason)
    }

    @Test
    fun `buying an asset that isn't the one currently pending is rejected`() {
        val state = purchaseDecisionState()
        val result = rejected(engine.buyAsset(state, "p1", "OuedKoriche")) // p1 is standing on Dergana, not this
        assertEquals(EngineError.INVALID_REQUEST, result.reason)
    }

    @Test
    fun `buying is rejected outside the purchase-decision phase`() {
        val state = purchaseDecisionState().copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS)
        val result = rejected(engine.buyAsset(state, "p1", "Dergana"))
        assertEquals(EngineError.WRONG_PHASE, result.reason)
    }

    // --- Declining starts an auction ---

    @Test
    fun `declining starts an auction open to every non-bankrupt player, including the decliner`() {
        val state = purchaseDecisionState(listOf("p1", "p2", "p3"))

        val result = applied(engine.declinePurchase(state, "p1"))
        val auction = result.newState.pendingAuction

        assertEquals(TurnPhase.IN_AUCTION, result.newState.phase)
        assertEquals(setOf("p1", "p2", "p3"), auction?.eligibleBidders)
        assertEquals(0, auction?.highestBid)
        assertNull(auction?.highestBidderId)
        assertTrue(result.events.any { it is GameEvent.AuctionStarted })
    }

    // --- Bidding ---

    @Test
    fun `an opening bid below the configured minimum is rejected`() {
        val state = applied(engine.declinePurchase(purchaseDecisionState(), "p1")).newState
        val result = rejected(engine.placeBid(state, "p1", 500)) // minimum is 1,000
        assertEquals(EngineError.INVALID_BID, result.reason)
    }

    @Test
    fun `a valid opening bid is accepted and the auction stays open for the other bidder`() {
        val state = applied(engine.declinePurchase(purchaseDecisionState(), "p1")).newState
        val result = applied(engine.placeBid(state, "p1", 1_000))

        assertEquals(TurnPhase.IN_AUCTION, result.newState.phase) // still open, p2 hasn't responded
        assertEquals(1_000, result.newState.pendingAuction?.highestBid)
        assertEquals("p1", result.newState.pendingAuction?.highestBidderId)
    }

    @Test
    fun `a subsequent bid smaller than the minimum increment over the current high bid is rejected`() {
        var state = applied(engine.declinePurchase(purchaseDecisionState(), "p1")).newState
        state = applied(engine.placeBid(state, "p1", 1_000)).newState

        val result = rejected(engine.placeBid(state, "p2", 1_200)) // needs to be at least 1,000+500=1,500
        assertEquals(EngineError.INVALID_BID, result.reason)
    }

    @Test
    fun `bidding more than the bidder can afford is rejected`() {
        var state = applied(engine.declinePurchase(purchaseDecisionState(), "p1")).newState
        state = state.withBalance("p1", 500)

        val result = rejected(engine.placeBid(state, "p1", 1_000))
        assertEquals(EngineError.INSUFFICIENT_FUNDS, result.reason)
    }

    @Test
    fun `a player who already passed cannot bid again`() {
        var state = applied(engine.declinePurchase(purchaseDecisionState(listOf("p1", "p2", "p3")), "p1")).newState
        state = applied(engine.passAuction(state, "p2")).newState

        val result = rejected(engine.placeBid(state, "p2", 1_000))
        assertEquals(EngineError.NOT_ELIGIBLE_TO_BID, result.reason)
    }

    // --- Concluding the auction ---

    @Test
    fun `the auction concludes once every other eligible bidder has passed, and the highest bidder wins`() {
        var state = applied(engine.declinePurchase(purchaseDecisionState(), "p1")).newState
        state = applied(engine.placeBid(state, "p1", 2_000)).newState

        val result = applied(engine.passAuction(state, "p2")) // last other eligible bidder passes

        assertEquals("p1", result.newState.assets.getValue("Dergana").ownerId)
        assertEquals(148_000, result.newState.player("p1").balance) // 150,000 - 2,000 winning bid
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, result.newState.phase)
        assertNull(result.newState.pendingAuction)
        assertTrue(result.events.any { it is GameEvent.AuctionWon })
    }

    @Test
    fun `if every eligible bidder passes without any bids, the asset remains unowned`() {
        var state = applied(engine.declinePurchase(purchaseDecisionState(), "p1")).newState
        state = applied(engine.passAuction(state, "p1")).newState

        val result = applied(engine.passAuction(state, "p2"))

        assertNull(result.newState.assets.getValue("Dergana").ownerId)
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, result.newState.phase)
        assertNull(result.newState.pendingAuction)
        assertTrue(result.events.any { it is GameEvent.AuctionEndedWithNoBids })
    }

    @Test
    fun `a three-player auction only concludes once BOTH other bidders have passed`() {
        var state = applied(engine.declinePurchase(purchaseDecisionState(listOf("p1", "p2", "p3")), "p1")).newState
        state = applied(engine.placeBid(state, "p2", 1_000)).newState

        val afterFirstPass = applied(engine.passAuction(state, "p1"))
        assertEquals(TurnPhase.IN_AUCTION, afterFirstPass.newState.phase) // p3 hasn't responded yet

        val afterSecondPass = applied(engine.passAuction(afterFirstPass.newState, "p3"))
        assertEquals("p2", afterSecondPass.newState.assets.getValue("Dergana").ownerId)
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, afterSecondPass.newState.phase)
    }

    @Test
    fun `a later, higher bid reopens eligibility for players who hadn't yet responded`() {
        var state = applied(engine.declinePurchase(purchaseDecisionState(listOf("p1", "p2", "p3")), "p1")).newState
        state = applied(engine.placeBid(state, "p1", 1_000)).newState
        state = applied(engine.passAuction(state, "p2")).newState
        // p3 outbids -- the auction must NOT have concluded just because p2 passed,
        // since p3 (the new high bidder) hadn't passed and is free to bid.
        val result = applied(engine.placeBid(state, "p3", 1_500))

        assertEquals(TurnPhase.IN_AUCTION, result.newState.phase) // p1 still needs to respond to being outbid
        assertEquals("p3", result.newState.pendingAuction?.highestBidderId)
    }
}
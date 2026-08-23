package com.menouer.rules_engine

import com.menouer.economy_data.Deck
import com.menouer.rules_engine.dice.DiceRoll
import com.menouer.rules_engine.model.EngineResult
import com.menouer.rules_engine.model.GameEvent
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.TurnPhase
import com.menouer.rules_engine.test.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DevelopmentRoadmap.md M1 exit criterion: "the engine can play a full simulated
 * game (scripted dice) from start to a bankruptcy-driven win with no manual
 * intervention." That's the first test below -- deliberately kept SHORT (one
 * deliberately-low starting balance, two turns) so every dice total and board
 * position here is hand-verifiable with confidence, rather than a long
 * multi-turn script whose wraparound arithmetic I can't actually execute to
 * check. It still exercises the real chain end-to-end purely through
 * RulesEngine calls: movement, GO, purchase, tax, auto-liquidation (trivially,
 * since p2 owns nothing), bankruptcy-to-the-Bank, and win detection -- no
 * direct state field edits standing in for any of those steps.
 *
 * The other tests here are focused coverage/regression additions found while
 * designing that game: a real bug in turn-advancement (caught by hand-tracing a
 * 3-player bankruptcy scenario before writing it), and two bankruptcy-to-a-player
 * paths (rent-driven, and Get-Out-of-Jail-Free card transfer) that weren't
 * exercised by any single-mechanic test in earlier sessions.
 */
class RulesEngineFullGameTest {

    private val engine = RulesEngineImpl()

    private fun applied(result: EngineResult) = result as EngineResult.Applied

    private fun GameState.own(assetId: String, ownerId: PlayerId, houses: Int = 0): GameState =
        copy(assets = assets + (assetId to assets.getValue(assetId).copy(ownerId = ownerId, houses = houses)))

    private fun GameState.withBalance(playerId: PlayerId, balance: Int): GameState =
        copy(players = players.replace(player(playerId).copy(balance = balance)))

    /** Buys the pending asset if affordable; otherwise declines and has every eligible bidder pass (never bids). */
    private fun resolvePendingPurchase(state: GameState, playerId: PlayerId, assetId: String, price: Int): GameState {
        var s = if (state.player(playerId).balance >= price) {
            applied(engine.buyAsset(state, playerId, assetId)).newState
        } else {
            applied(engine.declinePurchase(state, playerId)).newState
        }
        while (s.phase == TurnPhase.IN_AUCTION) {
            val auction = s.pendingAuction!!
            val bidder = (auction.eligibleBidders - auction.passedBidders - setOfNotNull(auction.highestBidderId)).first()
            s = applied(engine.passAuction(s, bidder)).newState
        }
        return s
    }

    // --- The M1 exit-criterion test ---

    @Test
    fun `a full game from start plays through movement, purchase, tax, bankruptcy, and win detection`() {
        var state = TestFixtures.newGame(listOf("p1", "p2"))
        // Deliberately low so this integration test stays short and hand-verifiable,
        // rather than simulating dozens of realistic turns to drain 150,000 naturally
        // (already covered by RulesEngineBankruptcyTest's liquidation scenarios).
        state = state.withBalance("p2", 3_000)

        // --- Turn 1 (p1): rolls (1,2) = 3, non-double, GO -> Oued Koriche (index 3, price 6,000). Buys it. ---
        var result = applied(engine.applyRoll(state, "p1", DiceRoll(1, 2)))
        assertEquals(3, result.newState.player("p1").position)
        assertEquals(TurnPhase.RESOLVING_LANDING, result.newState.phase)

        result = applied(engine.resolveLanding(result.newState))
        assertEquals(TurnPhase.AWAITING_PURCHASE_DECISION, result.newState.phase)

        state = resolvePendingPurchase(result.newState, "p1", "OuedKoriche", 6_000)
        assertEquals("p1", state.assets.getValue("OuedKoriche").ownerId)
        assertEquals(144_000, state.player("p1").balance) // 150,000 - 6,000

        result = applied(engine.endTurn(state))
        assertEquals("p2", result.newState.activePlayerId) // non-double roll, no bonus
        state = result.newState

        // --- Turn 2 (p2): rolls (2,2) = 4, GO -> Income Tax (index 4, 20,000). ---
        // p2 has only 3,000 and owns nothing to liquidate -> bankrupt to the Bank ->
        // only p1 remains -> game over.
        result = applied(engine.applyRoll(state, "p2", DiceRoll(2, 2)))
        assertEquals(4, result.newState.player("p2").position)

        result = applied(engine.resolveLanding(result.newState))
        val p2 = result.newState.player("p2")

        assertTrue(p2.bankrupt)
        assertEquals(0, p2.balance)
        assertTrue(result.events.any { it is GameEvent.PlayerBankrupted && it.creditorId == null })
        assertTrue(result.events.any { it is GameEvent.GameEnded })
        assertEquals(TurnPhase.GAME_OVER, result.newState.phase)
        assertEquals(listOf("p1"), result.newState.nonBankruptPlayers.map { it.id })
    }

    // --- Regression test for the bug found while designing the game above ---

    @Test
    fun `advancing past a bankrupted active player skips to the correct next player, not back to the first`() {
        val base = TestFixtures.newGame(listOf("p1", "p2", "p3"))
        val bankruptP2 = base.player("p2").copy(bankrupt = true, balance = 0)
        val state = base.copy(
            players = base.players.replace(bankruptP2),
            activePlayerId = "p2",
            phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS
        )

        val (newState, events) = engine.advanceToNextPlayer(state)

        assertEquals("p3", newState.activePlayerId) // p3 is next after p2 in turn order, NOT p1
        assertTrue(events.any { it is GameEvent.TurnChanged })
    }

    // --- Coverage: building raising rent enough to bankrupt an opponent TO THE OWNER ---

    @Test
    fun `building houses raises rent enough to bankrupt an opponent to the property owner, not the bank`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p1").own("OuedKoriche", "p1")
        state = state.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS, activePlayerId = "p1")

        repeat(3) {
            state = applied(engine.build(state, "p1", "Dergana")).newState
            state = applied(engine.build(state, "p1", "OuedKoriche")).newState
        }
        assertEquals(3, state.assets.getValue("OuedKoriche").houses) // rent3Houses = 18,000

        state = state.withBalance("p2", 500)
        state = state.copy(players = state.players.replace(state.player("p2").copy(position = 0)))
        state = state.copy(activePlayerId = "p2", phase = TurnPhase.AWAITING_ROLL)

        val afterRoll = applied(engine.applyRoll(state, "p2", DiceRoll(1, 2))) // GO -> Oued Koriche
        val result = applied(engine.resolveLanding(afterRoll.newState))
        val p2 = result.newState.player("p2")

        assertTrue(p2.bankrupt)
        assertEquals(0, p2.balance)
        assertTrue(result.events.any { it is GameEvent.PlayerBankrupted && it.creditorId == "p1" })
    }

    // --- Coverage: Get Out of Jail Free card transfer on a debt-to-player bankruptcy ---

    @Test
    fun `bankruptcy to another player transfers held Get Out of Jail Free cards to the creditor, not back to the deck`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Casbah", "p1")
        state = state.copy(
            players = state.players.replace(
                state.player("p2").copy(balance = 50, position = 0, getOutOfJailCards = listOf(Deck.CHANCE))
            ),
            activePlayerId = "p2",
            phase = TurnPhase.AWAITING_ROLL
        )
        val originalChanceDeckSize = state.chanceDeck.size

        val afterRoll = applied(engine.applyRoll(state, "p2", DiceRoll(4, 5))) // GO -> Casbah (index 9), base rent 800
        val result = applied(engine.resolveLanding(afterRoll.newState))

        assertTrue(result.newState.player("p2").bankrupt)
        assertTrue(result.newState.player("p2").getOutOfJailCards.isEmpty())
        assertEquals(listOf(Deck.CHANCE), result.newState.player("p1").getOutOfJailCards)
        // Must NOT have returned to the deck -- that only happens for a debt to the Bank.
        assertEquals(originalChanceDeckSize, result.newState.chanceDeck.size)
    }
}
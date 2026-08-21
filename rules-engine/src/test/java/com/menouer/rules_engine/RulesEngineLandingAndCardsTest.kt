package com.menouer.rules_engine

import com.menouer.rules_engine.dice.DiceRoll
import com.menouer.rules_engine.model.EngineResult
import com.menouer.rules_engine.model.GameEvent
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.TurnPhase
import com.menouer.rules_engine.test.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesEngineLandingAndCardsTest {

    private val engine = RulesEngineImpl()

    private fun applied(result: EngineResult) = result as EngineResult.Applied

    private fun GameState.own(assetId: String, ownerId: PlayerId, mortgaged: Boolean = false, houses: Int = 0, hasHotel: Boolean = false): GameState =
        copy(assets = assets + (assetId to assets.getValue(assetId).copy(ownerId = ownerId, mortgaged = mortgaged, houses = houses, hasHotel = hasHotel)))

    private fun GameState.atPosition(playerId: PlayerId, position: Int): GameState =
        copy(players = players.replace(player(playerId).copy(position = position)))

    private fun readyToResolve(state: GameState, roll: DiceRoll? = null): GameState =
        state.copy(phase = TurnPhase.RESOLVING_LANDING, lastRoll = roll ?: state.lastRoll)

    // --- Unowned purchasable asset: offer to buy ---

    @Test
    fun `landing on an unowned property transitions to the purchase decision phase`() {
        val state = readyToResolve(TestFixtures.newGame(listOf("p1", "p2")).atPosition("p1", 1)) // Dergana

        val result = applied(engine.resolveLanding(state))

        assertEquals(TurnPhase.AWAITING_PURCHASE_DECISION, result.newState.phase)
        assertTrue(result.events.any { it is GameEvent.PurchaseDecisionPending })
    }

    // --- Self-owned / mortgaged: no rent ---

    @Test
    fun `landing on your own property charges no rent`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p1")
        state = readyToResolve(state.atPosition("p1", 1))

        val result = applied(engine.resolveLanding(state))

        assertEquals(150_000, result.newState.player("p1").balance)
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, result.newState.phase)
    }

    @Test
    fun `landing on a mortgaged property owned by someone else charges no rent`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p2", mortgaged = true)
        state = readyToResolve(state.atPosition("p1", 1))

        val result = applied(engine.resolveLanding(state))

        assertEquals(150_000, result.newState.player("p1").balance)
        assertEquals(150_000, result.newState.player("p2").balance)
        assertTrue(result.events.none { it is GameEvent.RentPaid })
    }

    // --- Owned by someone else: rent charged and transferred ---

    @Test
    fun `landing on a property owned by another player charges base rent and pays the owner`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p2")
        state = readyToResolve(state.atPosition("p1", 1))

        val result = applied(engine.resolveLanding(state))

        assertEquals(149_800, result.newState.player("p1").balance) // 150,000 - 200 base rent
        assertEquals(150_200, result.newState.player("p2").balance)
        assertTrue(result.events.any { it is GameEvent.RentPaid })
    }

    @Test
    fun `landing on a utility owned by another player charges dice-total times multiplier`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Sonelgaz", "p2")
        state = readyToResolve(state.atPosition("p1", 12), roll = DiceRoll(3, 4)) // total 7

        val result = applied(engine.resolveLanding(state))

        assertEquals(150_000 - 28, result.newState.player("p1").balance) // 7 * singleUtilityMultiplier(4)
    }

    // --- Cards: simple bank payments ---

    @Test
    fun `a CollectFromBank chance card pays the player and cycles the card to the bottom of the chance deck`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).atPosition("p1", 7) // Chance
        state = state.copy(chanceDeck = listOf("CH11") + state.chanceDeck.filterNot { it == "CH11" }) // 10,000 DZD
        state = readyToResolve(state)

        val result = applied(engine.resolveLanding(state))

        assertEquals(160_000, result.newState.player("p1").balance)
        assertEquals("CH11", result.newState.chanceDeck.last()) // cycled to the bottom
        assertTrue(result.events.any { it is GameEvent.CardDrawn })
        assertTrue(result.events.any { it is GameEvent.CardBankPayout })
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, result.newState.phase)
    }

    @Test
    fun `a PayToBank chest card deducts from the player`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).atPosition("p1", 2) // Community Chest
        state = state.copy(chestDeck = listOf("CC02") + state.chestDeck.filterNot { it == "CC02" }) // pay 5,000
        state = readyToResolve(state)

        val result = applied(engine.resolveLanding(state))

        assertEquals(145_000, result.newState.player("p1").balance)
    }

    // --- Cards: movement, including recursive resolution of the destination ---

    @Test
    fun `a MoveToPosition card that lands exactly on GO pays the GO reward and resolves as a no-op`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).atPosition("p1", 22) // Chance
        state = state.copy(chanceDeck = listOf("CH01") + state.chanceDeck.filterNot { it == "CH01" }) // -> GO
        state = readyToResolve(state)

        val result = applied(engine.resolveLanding(state))

        assertEquals(0, result.newState.player("p1").position)
        assertEquals(170_000, result.newState.player("p1").balance) // 150,000 + 20,000 GO reward
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, result.newState.phase)
    }

    @Test
    fun `a backward MoveRelative card never pays GO even when it wraps past index 0`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).atPosition("p1", 2) // Chest Capital
        state = state.copy(chestDeck = listOf("CC16") + state.chestDeck.filterNot { it == "CC16" }) // Move 3 spaces backward
        state = readyToResolve(state)

        val result = applied(engine.resolveLanding(state))

        assertEquals(39, result.newState.player("p1").position) // 2 - 3 -> wraps to 39
        assertTrue(result.events.none { it is GameEvent.GoCollected })
        assertEquals(TurnPhase.AWAITING_PURCHASE_DECISION, result.newState.phase)
        assertEquals(150_000, result.newState.player("p1").balance) // No GO reward
    }

    @Test
    fun `a card that lands on another card space fully resolves the second card too`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).atPosition("p1", 36) // Chance
        state = state.copy(chanceDeck = listOf("CH06") + state.chanceDeck.filterNot { it == "CH06" }) // -3 -> 33 (Community Chest)
        state = state.copy(chestDeck = listOf("CC01") + state.chestDeck.filterNot { it == "CC01" }) // +20,000
        state = readyToResolve(state)

        val result = applied(engine.resolveLanding(state))

        assertEquals(33, result.newState.player("p1").position)
        assertEquals(170_000, result.newState.player("p1").balance) // 150,000 + 20,000 from the chained chest card
        assertEquals(2, result.events.count { it is GameEvent.CardDrawn }) // both cards drawn
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, result.newState.phase)
    }

    @Test
    fun `a GoToJail card sends the player to jail and ends the turn without paying GO`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).atPosition("p1", 7) // Chance
        state = state.copy(chanceDeck = listOf("CH07") + state.chanceDeck.filterNot { it == "CH07" })
        state = readyToResolve(state)

        val result = applied(engine.resolveLanding(state))

        val p1 = result.newState.player("p1")
        assertTrue(p1.inJail)
        assertEquals(10, p1.position)
        assertEquals(150_000, p1.balance)
        assertEquals("p2", result.newState.activePlayerId)
        assertTrue(result.events.none { it is GameEvent.GoCollected })
    }

    @Test
    fun `a GetOutOfJailFree card is retained by the player and removed from circulation`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).atPosition("p1", 7) // Chance
        val originalSize = state.chanceDeck.size
        state = state.copy(chanceDeck = listOf("CH08") + state.chanceDeck.filterNot { it == "CH08" })
        state = readyToResolve(state)

        val result = applied(engine.resolveLanding(state))

        assertEquals(1, result.newState.player("p1").getOutOfJailCards.size)
        assertEquals(originalSize - 1, result.newState.chanceDeck.size) // not returned to the deck
        assertTrue(result.events.any { it is GameEvent.GetOutOfJailCardReceived })
    }

    // --- Cards: PropertyRepairs, PayEachPlayer, CollectFromEachPlayer ---

    @Test
    fun `PropertyRepairs charges perHouse and perHotel across all of the player's buildings`() {
        var state = TestFixtures.newGame(listOf("p1", "p2"))
            .own("Dergana", "p1", houses = 2)
            .own("BabElOued", "p1", hasHotel = true)
        state = state.atPosition("p1", 7) // Chance
        state = state.copy(chanceDeck = listOf("CH12") + state.chanceDeck.filterNot { it == "CH12" }) // 400/house, 1150/hotel
        state = readyToResolve(state)

        val result = applied(engine.resolveLanding(state))

        // 2 houses * 400 + 1 hotel * 1150 = 800 + 1150 = 1,950
        assertEquals(150_000 - 1_950, result.newState.player("p1").balance)
    }

    @Test
    fun `PayEachPlayer distributes cash from the active player to every other non-bankrupt player`() {
        var state = TestFixtures.newGame(listOf("p1", "p2", "p3")).atPosition("p1", 7) // Chance
        state = state.copy(chanceDeck = listOf("CH14") + state.chanceDeck.filterNot { it == "CH14" }) // pay each 2,000
        state = readyToResolve(state)

        val result = applied(engine.resolveLanding(state))

        assertEquals(150_000 - 4_000, result.newState.player("p1").balance)
        assertEquals(152_000, result.newState.player("p2").balance)
        assertEquals(152_000, result.newState.player("p3").balance)
        assertEquals(2, result.events.count { it is GameEvent.CardPlayerToPlayerPayment })
    }

    @Test
    fun `CollectFromEachPlayer collects cash from every other non-bankrupt player`() {
        var state = TestFixtures.newGame(listOf("p1", "p2", "p3")).atPosition("p1", 17) // Community Chest
        state = state.copy(chestDeck = listOf("CC08") + state.chestDeck.filterNot { it == "CC08" }) // collect 1,000 each
        state = readyToResolve(state)

        val result = applied(engine.resolveLanding(state))

        assertEquals(150_000 + 2_000, result.newState.player("p1").balance)
        assertEquals(149_000, result.newState.player("p2").balance)
        assertEquals(149_000, result.newState.player("p3").balance)
    }

    // --- Cards: advance-to-nearest with rent overrides ---

    @Test
    fun `MoveToNearestStation card charges double the usual station rent when owned by another player`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("AghaStation", "p2")
        state = state.atPosition("p1", 36) // Chance; nearest station forward is AghaStation at index 5
        state = state.copy(chanceDeck = listOf("CH04") + state.chanceDeck.filterNot { it == "CH04" })
        state = readyToResolve(state)

        val result = applied(engine.resolveLanding(state))

        assertEquals(5, result.newState.player("p1").position)
        assertEquals(150_000 + 20_000 - 5_000, result.newState.player("p1").balance) // Crosses GO: +20,000, then pays double 2,500 station rent = -5,000
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, result.newState.phase)
        assertTrue(result.events.any { it is GameEvent.RentPaid && it.amount == 5_000 })
    }

    @Test
    fun `MoveToNearestUtility card charges a flat 10x dice total when owned by another player, ignoring ownership count`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Sonelgaz", "p2") // single utility owned
        state = state.atPosition("p1", 7) // Chance; nearest utility forward is Sonelgaz at index 12
        state = state.copy(chanceDeck = listOf("CH05") + state.chanceDeck.filterNot { it == "CH05" })
        state = readyToResolve(state, roll = DiceRoll(2, 3)) // total 5

        val result = applied(engine.resolveLanding(state))

        assertEquals(12, result.newState.player("p1").position)
        assertEquals(150_000 - 50, result.newState.player("p1").balance) // 5 * 10, NOT 5 * singleUtilityMultiplier(4)
    }

    @Test
    fun `MoveToNearestStation card offers a purchase decision when the nearest station is unowned`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).atPosition("p1", 36) // Chance -> nearest is AghaStation, unowned
        state = state.copy(chanceDeck = listOf("CH04") + state.chanceDeck.filterNot { it == "CH04" })
        state = readyToResolve(state)

        val result = applied(engine.resolveLanding(state))

        assertEquals(5, result.newState.player("p1").position)
        assertEquals(TurnPhase.AWAITING_PURCHASE_DECISION, result.newState.phase)
        assertFalse(result.events.any { it is GameEvent.RentPaid })
    }
}
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesEngineBankruptcyTest {

    private val engine = RulesEngineImpl()

    private fun applied(result: EngineResult) = result as EngineResult.Applied

    private fun GameState.own(assetId: String, ownerId: PlayerId, mortgaged: Boolean = false, houses: Int = 0, hasHotel: Boolean = false): GameState =
        copy(assets = assets + (assetId to assets.getValue(assetId).copy(ownerId = ownerId, mortgaged = mortgaged, houses = houses, hasHotel = hasHotel)))

    private fun GameState.atPosition(playerId: PlayerId, position: Int): GameState =
        copy(players = players.replace(player(playerId).copy(position = position)))

    private fun GameState.withBalance(playerId: PlayerId, balance: Int): GameState =
        copy(players = players.replace(player(playerId).copy(balance = balance)))

    private fun readyToResolve(state: GameState): GameState =
        state.copy(phase = TurnPhase.RESOLVING_LANDING)

    // --- Cash alone is fine: no liquidation needed ---

    @Test
    fun `a debt fully covered by cash never triggers liquidation`() {
        var state = TestFixtures.newGame(listOf("p1", "p2"))
            .own("Dergana", "p1", houses = 1)
            .own("OuedKoriche", "p1", houses = 1)
        state = readyToResolve(state.atPosition("p1", 4)) // IncomeTax, 20,000

        val result = applied(engine.resolveLanding(state))

        assertEquals(1, result.newState.assets.getValue("Dergana").houses) // untouched
        assertTrue(result.events.none { it is GameEvent.HouseSold })
        assertTrue(result.events.none { it is GameEvent.PlayerBankrupted })
    }

    // --- Auto-liquidation covers the debt: player survives ---

    @Test
    fun `insufficient cash triggers automatic liquidation, stopping as soon as the debt is covered`() {
        var state = TestFixtures.newGame(listOf("p1", "p2"))
            .own("Dergana", "p1", houses = 4)
            .own("OuedKoriche", "p1", houses = 4)
        state = state.withBalance("p1", 5_000) // can't cover 20,000 income tax alone
        state = readyToResolve(state.atPosition("p1", 4)) // IncomeTax

        val result = applied(engine.resolveLanding(state))
        val p1 = result.newState.player("p1")

        assertTrue(result.events.any { it is GameEvent.HouseSold })
        assertTrue(result.events.none { it is GameEvent.PlayerBankrupted })
        assertEquals(false, p1.bankrupt)
        // Dergana's 4 houses (4*2,500=10,000) plus only 2 of OuedKoriche's houses
        // (2*2,500=5,000) reach 5,000+10,000+5,000=20,000 -- liquidation stops there
        // rather than also selling OuedKoriche's remaining 2 houses.
        assertEquals(0, result.newState.assets.getValue("Dergana").houses)
        assertEquals(2, result.newState.assets.getValue("OuedKoriche").houses)
        assertEquals(0, p1.balance)
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, result.newState.phase)
    }

    @Test
    fun `liquidation mortgages remaining properties if selling buildings alone isn't enough`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p1") // no buildings, mortgage value 3,000
        state = state.withBalance("p1", 100)

        // Give p1 enough OTHER unmortgaged assets to cover the tax via mortgaging alone.
        state = state.own("OuedKoriche", "p1") // mortgage value 3,000
            .own("BabElOued", "p1") // 5,000
            .own("Bologhine", "p1") // 5,000
            .own("Casbah", "p1") // 6,000
        // total mortgage value available: 3,000+3,000+5,000+5,000+6,000 = 22,000 (plus 100 cash)

        state = readyToResolve(state.atPosition("p1", 4)) // IncomeTax 20,000

        val result = applied(engine.resolveLanding(state))
        val p1 = result.newState.player("p1")

        assertTrue(result.events.any { it is GameEvent.MortgagePlaced })
        assertEquals(false, p1.bankrupt)
        assertTrue(result.newState.assets.getValue("Dergana").mortgaged)
        // 100 + 3,000+3,000+5,000+5,000+6,000 = 22,100 raised; pay 20,000 tax -> 2,100 left.
        assertEquals(2_100, p1.balance)
    }

    // --- Full liquidation still isn't enough: bankruptcy ---

    @Test
    fun `debt to the bank that exceeds everything the player can raise declares bankruptcy and unowns their assets`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p1") // only 3,000 mortgage value available
        state = state.withBalance("p1", 100)
        state = readyToResolve(state.atPosition("p1", 4)) // IncomeTax 20,000 -- impossible to cover

        val result = applied(engine.resolveLanding(state))
        val p1 = result.newState.player("p1")

        assertTrue(p1.bankrupt)
        assertEquals(0, p1.balance)
        assertNull(result.newState.assets.getValue("Dergana").ownerId) // returned to the bank, unowned
        assertEquals(false, result.newState.assets.getValue("Dergana").mortgaged)
        assertTrue(result.events.any { it is GameEvent.PlayerBankrupted && it.creditorId == null })
    }

    @Test
    fun `the active player going bankrupt ends their turn immediately`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p1")
        state = state.withBalance("p1", 100)
        state = readyToResolve(state.atPosition("p1", 4)) // IncomeTax, unaffordable

        val result = applied(engine.resolveLanding(state))

        assertEquals("p2", result.newState.activePlayerId)
        assertEquals(TurnPhase.AWAITING_ROLL, result.newState.phase)
        assertTrue(result.events.any { it is GameEvent.TurnChanged })
    }

    @Test
    fun `bankruptcy to the bank leaves only two non-bankrupt players and the game continues`() {
        var state = TestFixtures.newGame(listOf("p1", "p2", "p3")).own("Dergana", "p1")
        state = state.withBalance("p1", 100)
        state = readyToResolve(state.atPosition("p1", 4))

        val result = applied(engine.resolveLanding(state))

        assertTrue(result.newState.nonBankruptPlayers.map { it.id }.containsAll(listOf("p2", "p3")))
        assertEquals(2, result.newState.nonBankruptPlayers.size)
        assertEquals(TurnPhase.AWAITING_ROLL, result.newState.phase) // game continues, not over
    }

    @Test
    fun `when only one player remains after a bankruptcy, the game ends`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p1")
        state = state.withBalance("p1", 100)
        state = readyToResolve(state.atPosition("p1", 4))

        val result = applied(engine.resolveLanding(state))

        assertEquals(TurnPhase.GAME_OVER, result.newState.phase)
        assertTrue(result.events.any { it is GameEvent.GameEnded })
    }

    // --- Debt to another player: creditor receives assets and cash ---

    @Test
    fun `bankruptcy from unpayable rent transfers all remaining cash and assets to the creditor`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Casbah", "p2") // p2 owns the property p1 will land on
        state = state.withBalance("p1", 50) // p1 owns nothing else and can't cover the rent
        state = readyToResolve(state.atPosition("p1", 9)) // Casbah, base rent 800

        val result = applied(engine.resolveLanding(state))
        val p1 = result.newState.player("p1")
        val p2 = result.newState.player("p2")

        assertTrue(p1.bankrupt)
        assertEquals(0, p1.balance)
        assertEquals(150_050, p2.balance) // received p1's remaining 50 cash (p1 had no assets to transfer)
        assertTrue(result.events.any { it is GameEvent.PlayerBankrupted && it.creditorId == "p2" })
    }

    @Test
    fun `bankruptcy to another player transfers the debtor's remaining assets to the creditor too`() {
        var state = TestFixtures.newGame(listOf("p1", "p2"))
            .own("Dergana", "p1") // p1's only asset, mortgage value 3,000 -- not nearly enough
            .own("Casbah", "p2", houses = 4) // rent at 4 houses is 45,000
        state = state.withBalance("p1", 50)
        state = readyToResolve(state.atPosition("p1", 9)) // Casbah

        val result = applied(engine.resolveLanding(state))
        val p1 = result.newState.player("p1")

        assertTrue(p1.bankrupt)
        assertEquals("p2", result.newState.assets.getValue("Dergana").ownerId) // transferred to the creditor
    }

    // --- Forced jail-turn-3 fine can also trigger bankruptcy ---

    @Test
    fun `an unaffordable forced jail-turn-3 fine triggers bankruptcy instead of crashing`() {
        val base = TestFixtures.newGame(listOf("p1", "p2"))
        val jailedP1 = base.player("p1").copy(position = 10, inJail = true, jailTurnsUsed = 2, balance = 100)
        val state = base.copy(players = base.players.replace(jailedP1), phase = TurnPhase.AWAITING_JAIL_DECISION)

        val result = applied(engine.applyRoll(state, "p1", DiceRoll(3, 2)))
        val p1 = result.newState.player("p1")

        assertTrue(p1.bankrupt)
        assertEquals("p2", result.newState.activePlayerId)
        assertTrue(result.events.any { it is GameEvent.PlayerBankrupted })
    }

    // --- Get Out of Jail Free cards return to their own deck on a bank bankruptcy ---

    @Test
    fun `bankruptcy to the bank returns held Get Out of Jail Free cards to their own decks`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p1")
        state = state.copy(players = state.players.replace(state.player("p1").copy(getOutOfJailCards = listOf(Deck.CHANCE))))
        state = state.withBalance("p1", 100)
        state = readyToResolve(state.atPosition("p1", 4)) // IncomeTax, unaffordable

        val result = applied(engine.resolveLanding(state))

        assertTrue(result.newState.player("p1").getOutOfJailCards.isEmpty())
        assertEquals("CH08", result.newState.chanceDeck.last())
    }
}
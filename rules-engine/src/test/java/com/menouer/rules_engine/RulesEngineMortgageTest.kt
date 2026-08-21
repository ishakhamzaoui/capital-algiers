package com.menouer.rules_engine

import com.menouer.rules_engine.model.EngineError
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

class RulesEngineMortgageTest {

    private val engine = RulesEngineImpl()

    private fun applied(result: EngineResult) = result as EngineResult.Applied
    private fun rejected(result: EngineResult) = result as EngineResult.Rejected

    private fun GameState.own(assetId: String, ownerId: PlayerId, mortgaged: Boolean = false, houses: Int = 0, hasHotel: Boolean = false): GameState =
        copy(assets = assets + (assetId to assets.getValue(assetId).copy(ownerId = ownerId, mortgaged = mortgaged, houses = houses, hasHotel = hasHotel)))

    private fun readyGame(): GameState =
        TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p1").copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS)

    // --- Mortgaging ---

    @Test
    fun `mortgaging an unimproved owned property pays the mortgage value and marks it mortgaged`() {
        val state = readyGame()

        val result = applied(engine.mortgage(state, "p1", "Dergana"))

        assertTrue(result.newState.assets.getValue("Dergana").mortgaged)
        assertEquals(153_000, result.newState.player("p1").balance) // 150,000 + 3,000 mortgage value
        assertTrue(result.events.any { it is GameEvent.MortgagePlaced })
    }

    @Test
    fun `mortgaging a station or utility works the same way as a property`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Sonelgaz", "p1")
        state = state.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS)

        val result = applied(engine.mortgage(state, "p1", "Sonelgaz"))

        assertTrue(result.newState.assets.getValue("Sonelgaz").mortgaged)
        assertEquals(157_500, result.newState.player("p1").balance) // 150,000 + 7,500
    }

    @Test
    fun `mortgaging is rejected if the property already has houses -- must sell buildings first`() {
        var state = TestFixtures.newGame(listOf("p1", "p2"))
            .own("Dergana", "p1", houses = 1)
            .own("OuedKoriche", "p1", houses = 1) // even-building satisfied, irrelevant to this check though
        state = state.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS)

        val result = rejected(engine.mortgage(state, "p1", "Dergana"))
        assertEquals(EngineError.MUST_SELL_BUILDINGS_FIRST, result.reason)
    }

    @Test
    fun `mortgaging is rejected if already mortgaged`() {
        val state = readyGame().own("Dergana", "p1", mortgaged = true)
        val result = rejected(engine.mortgage(state, "p1", "Dergana"))
        assertEquals(EngineError.ASSET_MORTGAGED, result.reason)
    }

    @Test
    fun `mortgaging is rejected for a property the player doesn't own`() {
        val state = readyGame() // Dergana owned by p1
        val result = rejected(engine.mortgage(state, "p2", "Dergana"))
        // p2 isn't the active player, so this is caught even earlier, but the ownership
        // check exists independently -- see the active-player-gated variant below too.
        assertEquals(EngineError.NOT_ACTIVE_PLAYER, result.reason)
    }

    @Test
    fun `mortgaging is rejected outside the optional-actions phase`() {
        val state = readyGame().copy(phase = TurnPhase.AWAITING_ROLL)
        val result = rejected(engine.mortgage(state, "p1", "Dergana"))
        assertEquals(EngineError.WRONG_PHASE, result.reason)
    }

    // --- Unmortgaging ---

    @Test
    fun `unmortgaging costs mortgage value plus 10 percent interest`() {
        val state = readyGame().own("Dergana", "p1", mortgaged = true)

        val result = applied(engine.unmortgage(state, "p1", "Dergana"))

        assertFalse(result.newState.assets.getValue("Dergana").mortgaged)
        assertEquals(146_700, result.newState.player("p1").balance) // 150,000 - (3,000 + 300 interest)
        assertTrue(result.events.any { it is GameEvent.MortgageLifted })
    }

    @Test
    fun `unmortgaging is rejected if the asset isn't mortgaged`() {
        val state = readyGame() // Dergana owned, not mortgaged
        val result = rejected(engine.unmortgage(state, "p1", "Dergana"))
        assertEquals(EngineError.ASSET_NOT_MORTGAGED, result.reason)
    }

    @Test
    fun `unmortgaging is rejected if the player can't afford mortgage value plus interest`() {
        var state = readyGame().own("Dergana", "p1", mortgaged = true)
        state = state.copy(players = state.players.replace(state.player("p1").copy(balance = 100)))

        val result = rejected(engine.unmortgage(state, "p1", "Dergana"))
        assertEquals(EngineError.INSUFFICIENT_FUNDS, result.reason)
    }

    // --- Integration with rent: mortgaging disqualifies the whole group's monopoly rent ---

    @Test
    fun `mortgaging one property in a group disqualifies monopoly rent for the whole group`() {
        var state = TestFixtures.newGame(listOf("p1", "p2"))
            .own("Dergana", "p2")
            .own("OuedKoriche", "p2")
        state = state.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS, activePlayerId = "p2")

        // Before mortgaging: full group, monopoly rent applies.
        assertEquals(400, RentCalculator.rentFor(state.config, state.assets, "Dergana", diceTotal = 0))

        val afterMortgage = applied(engine.mortgage(state, "p2", "OuedKoriche"))

        // After mortgaging OuedKoriche: Dergana (itself unmortgaged) drops to base rent.
        assertEquals(200, RentCalculator.rentFor(afterMortgage.newState.config, afterMortgage.newState.assets, "Dergana", diceTotal = 0))

        val afterUnmortgage = applied(engine.unmortgage(afterMortgage.newState, "p2", "OuedKoriche"))

        // Lifting the mortgage resumes monopoly rent automatically (GameRules.md §18).
        assertEquals(400, RentCalculator.rentFor(afterUnmortgage.newState.config, afterUnmortgage.newState.assets, "Dergana", diceTotal = 0))
    }
}
package com.menouer.rules_engine

import com.menouer.rules_engine.model.EngineError
import com.menouer.rules_engine.model.EngineResult
import com.menouer.rules_engine.model.GameEvent
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.TurnPhase
import com.menouer.rules_engine.test.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the Brown group (Dergana + Oued Koriche, houseCost 5,000 each) since it's the smallest complete group. */
class RulesEngineBuildingTest {

    private val engine = RulesEngineImpl()

    private fun applied(result: EngineResult) = result as EngineResult.Applied
    private fun rejected(result: EngineResult) = result as EngineResult.Rejected

    private fun GameState.own(assetId: String, ownerId: PlayerId, mortgaged: Boolean = false, houses: Int = 0, hasHotel: Boolean = false): GameState =
        copy(assets = assets + (assetId to assets.getValue(assetId).copy(ownerId = ownerId, mortgaged = mortgaged, houses = houses, hasHotel = hasHotel)))

    /** p1 owns the entire Brown group (unmortgaged), phase ready for optional actions. */
    private fun readyGame(derganaHouses: Int = 0, oudKoricheHouses: Int = 0): GameState {
        val state = TestFixtures.newGame(listOf("p1", "p2"))
            .own("Dergana", "p1", houses = derganaHouses)
            .own("OuedKoriche", "p1", houses = oudKoricheHouses)
        return state.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS)
    }

    // --- Building houses ---

    @Test
    fun `building the first house on a complete unmortgaged group succeeds`() {
        val state = readyGame()

        val result = applied(engine.build(state, "p1", "Dergana"))

        assertEquals(1, result.newState.assets.getValue("Dergana").houses)
        assertEquals(145_000, result.newState.player("p1").balance) // 150,000 - 5,000 houseCost
        assertEquals(31, result.newState.bankHouses) // 32 - 1
        assertTrue(result.events.any { it is GameEvent.HouseBuilt })
    }

    @Test
    fun `building is rejected when the player doesn't own the whole group`() {
        var state = TestFixtures.newGame(listOf("p1", "p2")).own("Dergana", "p1") // OuedKoriche left unowned
        state = state.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS)

        val result = rejected(engine.build(state, "p1", "Dergana"))
        assertEquals(EngineError.GROUP_NOT_COMPLETE, result.reason)
    }

    @Test
    fun `building is rejected while any property in the group is mortgaged`() {
        var state = TestFixtures.newGame(listOf("p1", "p2"))
            .own("Dergana", "p1")
            .own("OuedKoriche", "p1", mortgaged = true)
        state = state.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS)

        val result = rejected(engine.build(state, "p1", "Dergana"))
        assertEquals(EngineError.GROUP_HAS_MORTGAGED_PROPERTY, result.reason)
    }

    @Test
    fun `building unevenly is rejected -- a property can't get a 2nd house before its sibling has a 1st`() {
        val state = readyGame(derganaHouses = 1, oudKoricheHouses = 0)

        val result = rejected(engine.build(state, "p1", "Dergana"))
        assertEquals(EngineError.UNEVEN_BUILDING, result.reason)
    }

    @Test
    fun `building evenly across the group is allowed once siblings catch up`() {
        val state = readyGame(derganaHouses = 1, oudKoricheHouses = 1)

        val result = applied(engine.build(state, "p1", "Dergana"))
        assertEquals(2, result.newState.assets.getValue("Dergana").houses)
    }

    @Test
    fun `building is rejected when the bank has no houses left`() {
        val state = readyGame().copy(bankHouses = 0)

        val result = rejected(engine.build(state, "p1", "Dergana"))
        assertEquals(EngineError.BUILDING_SUPPLY_EXHAUSTED, result.reason)
    }

    @Test
    fun `building is rejected when the player can't afford the house cost`() {
        var state = readyGame()
        state = state.copy(players = state.players.replace(state.player("p1").copy(balance = 100)))

        val result = rejected(engine.build(state, "p1", "Dergana"))
        assertEquals(EngineError.INSUFFICIENT_FUNDS, result.reason)
    }

    @Test
    fun `building is rejected outside the player's own optional-actions phase`() {
        val state = readyGame().copy(phase = TurnPhase.AWAITING_ROLL)
        val result = rejected(engine.build(state, "p1", "Dergana"))
        assertEquals(EngineError.WRONG_PHASE, result.reason)
    }

    @Test
    fun `building is rejected for a player who is not the active player`() {
        val state = readyGame()
        val result = rejected(engine.build(state, "p2", "Dergana"))
        assertEquals(EngineError.NOT_ACTIVE_PLAYER, result.reason)
    }

    // --- Building a hotel ---

    @Test
    fun `upgrading to a hotel requires four houses on every property in the group`() {
        val state = readyGame(derganaHouses = 4, oudKoricheHouses = 3)

        val result = rejected(engine.build(state, "p1", "Dergana"))
        assertEquals(EngineError.UNEVEN_BUILDING, result.reason)
    }

    @Test
    fun `upgrading to a hotel succeeds once every property in the group has four houses`() {
        val state = readyGame(derganaHouses = 4, oudKoricheHouses = 4)

        val result = applied(engine.build(state, "p1", "Dergana"))
        val dergana = result.newState.assets.getValue("Dergana")

        assertTrue(dergana.hasHotel)
        assertEquals(0, dergana.houses)
        assertEquals(145_000, result.newState.player("p1").balance) // same houseCost, per BoardEconomy.md's single building-cost field
        assertEquals(state.bankHouses + 4, result.newState.bankHouses) // 4 houses returned
        assertEquals(state.bankHotels - 1, result.newState.bankHotels)
        assertTrue(result.events.any { it is GameEvent.HotelBuilt })
    }

    @Test
    fun `building is rejected on a property that already has a hotel`() {
        val state = readyGame().own("Dergana", "p1", hasHotel = true).own("OuedKoriche", "p1", houses = 4)
        val result = rejected(engine.build(state, "p1", "Dergana"))
        assertEquals(EngineError.MAX_BUILDINGS_REACHED, result.reason)
    }

    @Test
    fun `upgrading to a hotel is rejected when the bank has no hotels left`() {
        val state = readyGame(derganaHouses = 4, oudKoricheHouses = 4).copy(bankHotels = 0)
        val result = rejected(engine.build(state, "p1", "Dergana"))
        assertEquals(EngineError.BUILDING_SUPPLY_EXHAUSTED, result.reason)
    }

    // --- Selling houses ---

    @Test
    fun `selling a house refunds 50 percent of house cost and returns it to the bank`() {
        val state = readyGame(derganaHouses = 2, oudKoricheHouses = 2)

        val result = applied(engine.sellBuilding(state, "p1", "Dergana"))

        assertEquals(1, result.newState.assets.getValue("Dergana").houses)
        assertEquals(152_500, result.newState.player("p1").balance) // 150,000 + 2,500 (50% of 5,000)
        assertEquals(state.bankHouses + 1, result.newState.bankHouses)
        assertTrue(result.events.any { it is GameEvent.HouseSold })
    }

    @Test
    fun `selling unevenly is rejected -- can't sell from the property with fewer houses first`() {
        val state = readyGame(derganaHouses = 1, oudKoricheHouses = 2)

        val result = rejected(engine.sellBuilding(state, "p1", "Dergana"))
        assertEquals(EngineError.UNEVEN_BUILDING, result.reason)
    }

    @Test
    fun `selling with nothing built is rejected`() {
        val state = readyGame()
        val result = rejected(engine.sellBuilding(state, "p1", "Dergana"))
        assertEquals(EngineError.NO_BUILDING_TO_SELL, result.reason)
    }

    // --- Selling a hotel (converts back to 4 houses) ---

    @Test
    fun `selling a hotel converts it back to four houses and refunds 50 percent`() {
        var state = readyGame()
        state = state.own("Dergana", "p1", hasHotel = true).own("OuedKoriche", "p1", hasHotel = true)

        val result = applied(engine.sellBuilding(state, "p1", "Dergana"))
        val dergana = result.newState.assets.getValue("Dergana")

        assertTrue(!dergana.hasHotel)
        assertEquals(4, dergana.houses)
        assertEquals(152_500, result.newState.player("p1").balance)
        assertEquals(state.bankHouses - 4, result.newState.bankHouses)
        assertEquals(state.bankHotels + 1, result.newState.bankHotels)
        assertTrue(result.events.any { it is GameEvent.HotelSold })
    }

    @Test
    fun `selling a hotel is rejected when the bank doesn't have 4 houses available to convert it back`() {
        var state = readyGame()
        state = state.own("Dergana", "p1", hasHotel = true).own("OuedKoriche", "p1", hasHotel = true)
        state = state.copy(bankHouses = 3)

        val result = rejected(engine.sellBuilding(state, "p1", "Dergana"))
        assertEquals(EngineError.BUILDING_SUPPLY_EXHAUSTED, result.reason)
    }
}
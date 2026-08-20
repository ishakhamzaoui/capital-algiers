package com.menouer.rules_engine

import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.test.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class RentCalculatorTest {

    private fun baseState(): GameState = TestFixtures.newGame(listOf("p1", "p2"))

    private fun GameState.own(assetId: String, ownerId: PlayerId, mortgaged: Boolean = false, houses: Int = 0, hasHotel: Boolean = false): GameState =
        copy(assets = assets + (assetId to assets.getValue(assetId).copy(ownerId = ownerId, mortgaged = mortgaged, houses = houses, hasHotel = hasHotel)))

    // --- Properties: base vs monopoly rent ---

    @Test
    fun `base rent applies when the owner does not hold the full group`() {
        val state = baseState().own("Dergana", "p2") // OuedKoriche left unowned
        val rent = RentCalculator.rentFor(state.config, state.assets, "Dergana", diceTotal = 0)
        assertEquals(200, rent)
    }

    @Test
    fun `monopoly rent applies when the owner holds every property in the group, none mortgaged`() {
        val state = baseState()
            .own("Dergana", "p2")
            .own("OuedKoriche", "p2")
        val rent = RentCalculator.rentFor(state.config, state.assets, "Dergana", diceTotal = 0)
        assertEquals(400, rent)
    }

    @Test
    fun `mortgaging any property in the group disqualifies monopoly rent for the whole group`() {
        val state = baseState()
            .own("Dergana", "p2")
            .own("OuedKoriche", "p2", mortgaged = true)

        // Dergana itself isn't mortgaged, but the group's monopoly bonus is disqualified.
        val derganaRent = RentCalculator.rentFor(state.config, state.assets, "Dergana", diceTotal = 0)
        assertEquals(200, derganaRent) // base rent, not 400
    }

    @Test
    fun `a mortgaged property itself always collects zero rent even with a complete group`() {
        val state = baseState()
            .own("Dergana", "p2", mortgaged = true)
            .own("OuedKoriche", "p2")
        val rent = RentCalculator.rentFor(state.config, state.assets, "Dergana", diceTotal = 0)
        assertEquals(0, rent)
    }

    @Test
    fun `unmortgaging every property in the group resumes monopoly rent automatically`() {
        val state = baseState()
            .own("Dergana", "p2")
            .own("OuedKoriche", "p2", mortgaged = false) // previously mortgaged, now lifted
        val rent = RentCalculator.rentFor(state.config, state.assets, "Dergana", diceTotal = 0)
        assertEquals(400, rent)
    }

    // --- Properties: houses / hotel override group status entirely ---

    @Test
    fun `house rent tiers are used once a property has houses, regardless of group completeness`() {
        val state = baseState().own("Dergana", "p2", houses = 2) // owner doesn't even own OuedKoriche
        assertEquals(3_000, RentCalculator.rentFor(state.config, state.assets, "Dergana", diceTotal = 0))
    }

    @Test
    fun `hotel rent is used once a property has a hotel`() {
        val state = baseState().own("Dergana", "p2", hasHotel = true)
        assertEquals(25_000, RentCalculator.rentFor(state.config, state.assets, "Dergana", diceTotal = 0))
    }

    // --- Stations: rent by count owned ---

    @Test
    fun `station rent scales with how many stations the same owner holds`() {
        var state = baseState().own("AghaStation", "p2")
        assertEquals(2_500, RentCalculator.rentFor(state.config, state.assets, "AghaStation", diceTotal = 0))

        state = state.own("ElHarrachStation", "p2")
        assertEquals(5_000, RentCalculator.rentFor(state.config, state.assets, "AghaStation", diceTotal = 0))

        state = state.own("PlaceDesMartyrsMetro", "p2")
        assertEquals(10_000, RentCalculator.rentFor(state.config, state.assets, "AghaStation", diceTotal = 0))

        state = state.own("BabEzzouarTramway", "p2")
        assertEquals(20_000, RentCalculator.rentFor(state.config, state.assets, "AghaStation", diceTotal = 0))
    }

    @Test
    fun `a mortgaged station collects zero rent`() {
        val state = baseState().own("AghaStation", "p2", mortgaged = true)
        assertEquals(0, RentCalculator.rentFor(state.config, state.assets, "AghaStation", diceTotal = 0))
    }

    // --- Utilities: dice-total multiplier ---

    @Test
    fun `single utility owned uses the single-utility multiplier times the dice total`() {
        val state = baseState().own("Sonelgaz", "p2")
        assertEquals(28, RentCalculator.rentFor(state.config, state.assets, "Sonelgaz", diceTotal = 7)) // 7 * 4
    }

    @Test
    fun `both utilities owned by the same player uses the both-utilities multiplier`() {
        val state = baseState()
            .own("Sonelgaz", "p2")
            .own("SEAAL", "p2")
        assertEquals(70, RentCalculator.rentFor(state.config, state.assets, "Sonelgaz", diceTotal = 7)) // 7 * 10
    }

    @Test
    fun `an unowned asset collects zero rent`() {
        val state = baseState()
        assertEquals(0, RentCalculator.rentFor(state.config, state.assets, "Dergana", diceTotal = 5))
    }
}
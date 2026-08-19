package com.menouer.rules_engine

import com.menouer.rules_engine.dice.DiceRoll
import com.menouer.rules_engine.dice.ScriptedDiceSource
import com.menouer.rules_engine.model.TurnPhase
import com.menouer.rules_engine.test.TestFixtures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session 0 smoke tests: confirms the foundation types compile, wire together,
 * and behave as expected before any real rule logic is layered on top.
 */
class Session0FoundationTest {

    @Test
    fun `new game starts all players on GO with starting money`() {
        val state = TestFixtures.newGame(listOf("p1", "p2", "p3"))

        assertEquals(3, state.players.size)
        state.players.forEach {
            assertEquals(0, it.position)
            assertEquals(150_000, it.balance)
            assertFalse(it.bankrupt)
            assertFalse(it.inJail)
        }
        assertEquals("p1", state.activePlayerId)
        assertEquals(TurnPhase.AWAITING_ROLL, state.phase)
        assertEquals(32, state.bankHouses)
        assertEquals(12, state.bankHotels)
        assertEquals(16, state.chanceDeck.size)
        assertEquals(16, state.chestDeck.size)
    }

    @Test
    fun `every property, station, and utility starts unowned`() {
        val state = TestFixtures.newGame(listOf("p1", "p2"))

        assertEquals(28, state.assets.size) // 22 properties + 4 stations + 2 utilities
        state.assets.values.forEach {
            assertEquals(null, it.ownerId)
            assertFalse(it.mortgaged)
            assertEquals(0, it.houses)
            assertFalse(it.hasHotel)
        }
    }

    @Test
    fun `scripted dice source replays exactly the given rolls in order`() {
        val dice = ScriptedDiceSource.of(1 to 1, 3 to 4, 6 to 6)

        assertEquals(DiceRoll(1, 1), dice.roll())
        assertEquals(DiceRoll(3, 4), dice.roll())
        assertEquals(DiceRoll(6, 6), dice.roll())
    }

    @Test
    fun `scripted dice source throws once exhausted rather than looping silently`() {
        val dice = ScriptedDiceSource.of(2 to 3)
        dice.roll()
        try {
            dice.roll()
            org.junit.Assert.fail("expected an exception once the script is exhausted")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("exhausted"))
        }
    }

    @Test
    fun `dice roll correctly reports total and double-ness`() {
        assertTrue(DiceRoll(4, 4).isDouble)
        assertFalse(DiceRoll(4, 5).isDouble)
        assertEquals(9, DiceRoll(4, 5).total)
    }
}
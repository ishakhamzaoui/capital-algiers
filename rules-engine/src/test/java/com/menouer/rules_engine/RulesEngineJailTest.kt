package com.menouer.rules_engine

import com.menouer.economy_data.Deck
import com.menouer.rules_engine.dice.DiceRoll
import com.menouer.rules_engine.model.EngineError
import com.menouer.rules_engine.model.EngineResult
import com.menouer.rules_engine.model.GameEvent
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.JailReleaseMethod
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.TurnPhase
import com.menouer.rules_engine.test.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesEngineJailTest {

    private val engine = RulesEngineImpl()

    private fun applied(result: EngineResult) = result as EngineResult.Applied
    private fun rejected(result: EngineResult) = result as EngineResult.Rejected

    /** A 2-player game with p1 jailed at the start of their turn, jailTurnsUsed as given. */
    private fun jailedGame(jailTurnsUsed: Int = 0): GameState {
        val base = TestFixtures.newGame(listOf("p1", "p2"))
        val jailedP1 = base.player("p1").copy(position = 10, inJail = true, jailTurnsUsed = jailTurnsUsed)
        return base.copy(players = base.players.replace(jailedP1), phase = TurnPhase.AWAITING_JAIL_DECISION)
    }

    private fun GameState.withGetOutOfJailCard(playerId: PlayerId, deck: Deck): GameState =
        copy(players = players.replace(player(playerId).copy(getOutOfJailCards = player(playerId).getOutOfJailCards + deck)))

    // --- Voluntary: pay fine ---

    @Test
    fun `paying the fine voluntarily releases the player and grants a normal roll this turn`() {
        val state = jailedGame()

        val result = applied(engine.jailAction(state, "p1", JailAction.PAY_FINE))
        val p1 = result.newState.player("p1")

        assertFalse(p1.inJail)
        assertEquals(145_000, p1.balance) // 150,000 - 5,000 fine
        assertEquals(10, p1.position) // no movement from paying the fine itself
        assertEquals(TurnPhase.AWAITING_ROLL, result.newState.phase)
        assertTrue(result.events.any { it is GameEvent.JailFinePaid && !it.forced })

        // and now a completely normal roll works, same turn
        val afterRoll = applied(engine.applyRoll(result.newState, "p1", DiceRoll(2, 3)))
        assertEquals(15, afterRoll.newState.player("p1").position) // 10 -> 15
    }

    @Test
    fun `paying the fine voluntarily is rejected if the player can't afford it`() {
        val state = jailedGame()
        val poorP1 = state.player("p1").copy(balance = 1_000)
        val poorState = state.copy(players = state.players.replace(poorP1))

        val result = rejected(engine.jailAction(poorState, "p1", JailAction.PAY_FINE))

        assertEquals(EngineError.INSUFFICIENT_FUNDS, result.reason)
    }

    // --- Voluntary: use Get Out of Jail Free card ---

    @Test
    fun `using a Get Out of Jail Free card releases the player and returns the card to its own deck`() {
        var state = jailedGame().withGetOutOfJailCard("p1", Deck.CHANCE)
        val originalChanceDeckSize = state.chanceDeck.size

        val result = applied(engine.jailAction(state, "p1", JailAction.USE_GET_OUT_OF_JAIL_CARD))
        val p1 = result.newState.player("p1")

        assertFalse(p1.inJail)
        assertTrue(p1.getOutOfJailCards.isEmpty())
        assertEquals(150_000, p1.balance) // no cost
        assertEquals(TurnPhase.AWAITING_ROLL, result.newState.phase)
        assertEquals(originalChanceDeckSize + 1, result.newState.chanceDeck.size)
        assertEquals("CH08", result.newState.chanceDeck.last()) // returned to the bottom of ITS OWN deck
        assertTrue(result.events.any { it is GameEvent.GetOutOfJailCardUsed })
    }

    @Test
    fun `using a Get Out of Jail Free card returns it to the chest deck when that's where it came from`() {
        val state = jailedGame().withGetOutOfJailCard("p1", Deck.CAPITAL_CHEST)

        val result = applied(engine.jailAction(state, "p1", JailAction.USE_GET_OUT_OF_JAIL_CARD))

        assertEquals("CC06", result.newState.chestDeck.last())
        assertEquals(state.chanceDeck, result.newState.chanceDeck) // chance deck untouched
    }

    @Test
    fun `using a Get Out of Jail Free card is rejected when the player doesn't hold one`() {
        val state = jailedGame()
        val result = rejected(engine.jailAction(state, "p1", JailAction.USE_GET_OUT_OF_JAIL_CARD))
        assertEquals(EngineError.NO_GET_OUT_OF_JAIL_CARD, result.reason)
    }

    // --- Validation ---

    @Test
    fun `jailAction is rejected for a player who is not in jail`() {
        val state = TestFixtures.newGame(listOf("p1", "p2")).copy(phase = TurnPhase.AWAITING_JAIL_DECISION)
        val result = rejected(engine.jailAction(state, "p1", JailAction.PAY_FINE))
        assertEquals(EngineError.PLAYER_NOT_IN_JAIL, result.reason)
    }

    @Test
    fun `jailAction is rejected outside the AWAITING_JAIL_DECISION phase`() {
        val state = jailedGame().copy(phase = TurnPhase.AWAITING_ROLL)
        val result = rejected(engine.jailAction(state, "p1", JailAction.PAY_FINE))
        assertEquals(EngineError.WRONG_PHASE, result.reason)
    }

    // --- Doubles attempt: jail-turns 1/2 ---

    @Test
    fun `rolling doubles on jail-turn 1 releases and moves the player, with no bonus roll even though it's a double`() {
        val state = jailedGame(jailTurnsUsed = 0)

        val result = applied(engine.applyRoll(state, "p1", DiceRoll(5, 5))) // double
        val p1 = result.newState.player("p1")

        assertFalse(p1.inJail)
        assertEquals(0, p1.jailTurnsUsed)
        assertEquals(20, p1.position) // 10 -> 16 (Hussein Dey)
        assertEquals(TurnPhase.RESOLVING_LANDING, result.newState.phase)
        assertFalse(result.newState.pendingBonusRoll) // the critical rule: a jail-exit double never grants a bonus roll
        assertTrue(result.events.any { it is GameEvent.ReleasedFromJail && it.method == JailReleaseMethod.DOUBLES_ATTEMPT })

        // Prove it end-to-end: resolving the landing and ending the turn must advance
        // to the next player, NOT loop back to p1 for a bonus roll.
        val afterLanding = applied(engine.resolveLanding(result.newState))
        val afterEndTurn = applied(engine.endTurn(afterLanding.newState))
        assertEquals("p2", afterEndTurn.newState.activePlayerId)
    }

    @Test
    fun `failing to roll doubles on jail-turn 1 keeps the player jailed and ends the turn`() {
        val state = jailedGame(jailTurnsUsed = 0)

        val result = applied(engine.applyRoll(state, "p1", DiceRoll(2, 5))) // not a double
        val p1 = result.newState.player("p1")

        assertTrue(p1.inJail)
        assertEquals(1, p1.jailTurnsUsed)
        assertEquals(10, p1.position) // unchanged
        assertEquals("p2", result.newState.activePlayerId) // turn already advanced
        assertTrue(result.events.any { it is GameEvent.JailRollFailed })
        assertTrue(result.events.none { it is GameEvent.PlayerMoved })
    }

    @Test
    fun `failing to roll doubles on jail-turn 2 advances jailTurnsUsed to 2, ready for the forced roll`() {
        val state = jailedGame(jailTurnsUsed = 1)

        val result = applied(engine.applyRoll(state, "p1", DiceRoll(1, 4))) // not a double
        val p1 = result.newState.player("p1")

        assertTrue(p1.inJail)
        assertEquals(2, p1.jailTurnsUsed)
    }

    // --- Forced jail-turn 3 ---

    @Test
    fun `jail-turn 3 automatically deducts the fine and moves the player regardless of doubles`() {
        val state = jailedGame(jailTurnsUsed = 2)

        val result = applied(engine.applyRoll(state, "p1", DiceRoll(4, 4))) // a double, but doesn't matter here
        val p1 = result.newState.player("p1")

        assertFalse(p1.inJail)
        assertEquals(145_000, p1.balance) // 150,000 - 5,000 fine, auto-deducted
        assertEquals(18, p1.position) // 10 -> 18
        assertFalse(result.newState.pendingBonusRoll) // no bonus roll even though the roll was a double
        assertTrue(result.events.any { it is GameEvent.JailFinePaid && it.forced })
        assertTrue(result.events.any { it is GameEvent.ReleasedFromJail && it.method == JailReleaseMethod.FORCED_TURN_THREE })
    }

    @Test
    fun `rolling doubles on jail-turn 2 also releases the player (boundary check for jailTurnsUsed less than 2)`() {
        val state = jailedGame(jailTurnsUsed = 1)

        val result = applied(engine.applyRoll(state, "p1", DiceRoll(5, 5))) // double
        val p1 = result.newState.player("p1")

        assertFalse(p1.inJail)
        assertEquals(20, p1.position) // 10 -> 20 (Free Parking)
        assertTrue(result.events.any { it is GameEvent.ReleasedFromJail && it.method == JailReleaseMethod.DOUBLES_ATTEMPT })
    }

    @Test
    fun `jail-turn 3 with insufficient funds surfaces the pending bankruptcy TODO rather than silently succeeding`() {
        val state = jailedGame(jailTurnsUsed = 2)
        val poorP1 = state.player("p1").copy(balance = 1_000)
        val poorState = state.copy(players = state.players.replace(poorP1))

        assertThrows(IllegalStateException::class.java) {
            engine.applyRoll(poorState, "p1", DiceRoll(3, 2))
        }
    }

    // --- applyRoll dispatch sanity ---

    @Test
    fun `applyRoll while in AWAITING_JAIL_DECISION is not rejected as wrong-phase`() {
        val state = jailedGame()
        val result = engine.applyRoll(state, "p1", DiceRoll(1, 2))
        assertTrue(result is EngineResult.Applied)
    }
}
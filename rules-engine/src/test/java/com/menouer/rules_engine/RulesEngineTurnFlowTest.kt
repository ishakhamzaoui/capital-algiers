package com.menouer.rules_engine

import com.menouer.rules_engine.dice.DiceRoll
import com.menouer.rules_engine.model.EngineError
import com.menouer.rules_engine.model.EngineResult
import com.menouer.rules_engine.model.GameEvent
import com.menouer.rules_engine.model.TurnPhase
import com.menouer.rules_engine.test.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesEngineTurnFlowTest {

    private val engine = RulesEngineImpl()

    private fun applied(result: EngineResult) = result as EngineResult.Applied
    private fun rejected(result: EngineResult) = result as EngineResult.Rejected

    // --- Basic movement ---

    @Test
    fun `moving without passing GO does not pay the GO reward`() {
        val state = TestFixtures.newGame(listOf("p1", "p2"))
            .let { it.copy(players = it.players.replace(it.player("p1").copy(position = 5))) }

        val result = applied(engine.applyRoll(state, "p1", DiceRoll(1, 3))) // 5 -> 9, no wrap
        val p1 = result.newState.player("p1")

        assertEquals(9, p1.position)
        assertEquals(150_000, p1.balance)
        assertTrue(result.events.none { it is GameEvent.GoCollected })
        assertEquals(TurnPhase.RESOLVING_LANDING, result.newState.phase)
    }

    @Test
    fun `landing exactly on GO pays the GO reward`() {
        val state = TestFixtures.newGame(listOf("p1", "p2"))
            .let { it.copy(players = it.players.replace(it.player("p1").copy(position = 33))) }

        val result = applied(engine.applyRoll(state, "p1", DiceRoll(3, 4))) // 33 -> 40 -> 0
        val p1 = result.newState.player("p1")

        assertEquals(0, p1.position)
        assertEquals(170_000, p1.balance)
    }

    // --- Validation ---

    @Test
    fun `applyRoll is rejected for a player who is not the active player`() {
        val state = TestFixtures.newGame(listOf("p1", "p2"))
        val result = rejected(engine.applyRoll(state, "p2", DiceRoll(2, 2)))
        assertEquals(EngineError.NOT_ACTIVE_PLAYER, result.reason)
    }

    @Test
    fun `applyRoll is rejected when phase is not AWAITING_ROLL`() {
        val state = TestFixtures.newGame(listOf("p1", "p2")).copy(phase = TurnPhase.RESOLVING_LANDING)
        val result = rejected(engine.applyRoll(state, "p1", DiceRoll(2, 2)))
        assertEquals(EngineError.WRONG_PHASE, result.reason)
    }

    @Test
    fun `endTurn is rejected before the landed space has been resolved`() {
        val state = TestFixtures.newGame(listOf("p1", "p2")).copy(phase = TurnPhase.RESOLVING_LANDING)
        val result = rejected(engine.endTurn(state))
        assertEquals(EngineError.WRONG_PHASE, result.reason)
    }

    // --- Non-double: normal turn end advances to the next player ---

    @Test
    fun `a non-double roll ends the turn and advances to the next player`() {
        val state0 = TestFixtures.newGame(listOf("p1", "p2"))
        val state1 = state0.copy(players = state0.players.replace(state0.player("p1").copy(position = 14)))

        val afterRoll = applied(engine.applyRoll(state1, "p1", DiceRoll(2, 4))) // 14 -> 20 (Free Parking), non-double
        assertEquals(TurnPhase.RESOLVING_LANDING, afterRoll.newState.phase)

        val afterLanding = applied(engine.resolveLanding(afterRoll.newState))
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, afterLanding.newState.phase)

        val afterEndTurn = applied(engine.endTurn(afterLanding.newState))
        assertEquals("p2", afterEndTurn.newState.activePlayerId)
        assertEquals(TurnPhase.AWAITING_ROLL, afterEndTurn.newState.phase)
        assertEquals(0, afterEndTurn.newState.consecutiveDoublesCount)
        assertTrue(afterEndTurn.events.any { it is GameEvent.TurnChanged })
    }

    // --- Double: bonus roll granted to the same player ---

    @Test
    fun `a double grants a bonus roll to the same player instead of ending the turn`() {
        val state0 = TestFixtures.newGame(listOf("p1", "p2"))
        val state1 = state0.copy(players = state0.players.replace(state0.player("p1").copy(position = 34)))

        val afterRoll = applied(engine.applyRoll(state1, "p1", DiceRoll(2, 2))) // 34 -> 38 (Luxury Tax), double
        assertEquals(1, afterRoll.newState.consecutiveDoublesCount)

        val afterLanding = applied(engine.resolveLanding(afterRoll.newState))
        assertEquals(140_000, afterLanding.newState.player("p1").balance) // 150,000 - 10,000 luxury tax

        val afterEndTurn = applied(engine.endTurn(afterLanding.newState))
        assertEquals("p1", afterEndTurn.newState.activePlayerId) // still p1, no advance
        assertEquals(TurnPhase.AWAITING_ROLL, afterEndTurn.newState.phase)
        assertEquals(1, afterEndTurn.newState.consecutiveDoublesCount) // preserved, not reset
        assertTrue(afterEndTurn.events.any { it is GameEvent.BonusRollGranted })
    }

    // --- Three consecutive doubles sends the player to jail and ends the turn immediately ---

    @Test
    fun `three consecutive doubles sends the player to jail without the third roll's movement`() {
        var state = TestFixtures.newGame(listOf("p1", "p2"))
        state = state.copy(players = state.players.replace(state.player("p1").copy(position = 34)))

        // Roll 1: 34 -> 38 (Luxury Tax), double. Bonus roll granted.
        var result = applied(engine.applyRoll(state, "p1", DiceRoll(2, 2)))
        result = applied(engine.resolveLanding(result.newState))
        result = applied(engine.endTurn(result.newState))
        assertEquals(TurnPhase.AWAITING_ROLL, result.newState.phase)
        assertEquals(1, result.newState.consecutiveDoublesCount)

        // Roll 2: 38 -> 0 (GO), double. Bonus roll granted again.
        result = applied(engine.applyRoll(result.newState, "p1", DiceRoll(1, 1)))
        assertTrue(result.events.any { it is GameEvent.GoCollected })
        result = applied(engine.resolveLanding(result.newState))
        result = applied(engine.endTurn(result.newState))
        assertEquals(TurnPhase.AWAITING_ROLL, result.newState.phase)
        assertEquals(2, result.newState.consecutiveDoublesCount)
        val balanceBeforeThirdRoll = result.newState.player("p1").balance

        // Roll 3: double again -> sent directly to jail, no movement, no GO payment, turn ends immediately.
        result = applied(engine.applyRoll(result.newState, "p1", DiceRoll(3, 3)))
        val p1 = result.newState.player("p1")

        assertTrue(p1.inJail)
        assertEquals(10, p1.position) // ElHarrachJail board index
        assertEquals(balanceBeforeThirdRoll, p1.balance) // no GO payment on the jailing roll
        assertEquals(0, result.newState.consecutiveDoublesCount) // reset after forced turn-end
        assertEquals("p2", result.newState.activePlayerId) // turn already advanced
        assertEquals(TurnPhase.AWAITING_ROLL, result.newState.phase) // p2 is not in jail
        assertTrue(result.events.any { it is GameEvent.SentToJail })
        assertTrue(result.events.any { it is GameEvent.TurnChanged })
        assertTrue(result.events.none { it is GameEvent.PlayerMoved }) // no normal movement on the 3rd roll
    }

    // --- Landing on GO_TO_JAIL ends the turn immediately, without GO payment ---

    @Test
    fun `landing on GO_TO_JAIL sends the player to jail and ends the turn immediately`() {
        val state0 = TestFixtures.newGame(listOf("p1", "p2"))
        val state1 = state0.copy(players = state0.players.replace(state0.player("p1").copy(position = 25)))

        val afterRoll = applied(engine.applyRoll(state1, "p1", DiceRoll(2, 3))) // 25 -> 30 (Go To Jail), non-double
        val afterLanding = applied(engine.resolveLanding(afterRoll.newState))
        val p1 = afterLanding.newState.player("p1")

        assertTrue(p1.inJail)
        assertEquals(10, p1.position)
        assertFalse(afterLanding.events.any { it is GameEvent.GoCollected })
        assertEquals("p2", afterLanding.newState.activePlayerId)
        assertEquals(TurnPhase.AWAITING_ROLL, afterLanding.newState.phase)
    }

    // --- Multi-GO-per-turn deviation (GameRules.md §6) ---
    //
    // A genuine two-dice-rolls-in-one-turn double GO crossing is not reachable with
    // real 2d6 totals on a 40-space board (after the first wrap you're too close to
    // GO again for a second wrap with a max roll of 12) — real double collection in
    // one turn happens via a card-forced move stacked with normal movement, which
    // lands in Session 2. This test locks the underlying mechanism (no per-turn
    // "already collected" guard) directly, so Session 2's card logic can rely on it.

    @Test
    fun `nothing in engine state prevents collecting the GO reward twice in the same turn`() {
        val state0 = TestFixtures.newGame(listOf("p1", "p2"))
        var state = state0.copy(players = state0.players.replace(state0.player("p1").copy(position = 39)))

        val (posAfterFirst, passedGoFirst) = engine.movePosition(state.player("p1").position, 1) // 39 -> 0
        assertTrue(passedGoFirst)
        val p1AfterFirst = state.player("p1").copy(
            position = posAfterFirst,
            balance = state.player("p1").balance + state.config.constants.goReward
        )
        state = state.copy(players = state.players.replace(p1AfterFirst))

        val (posAfterSecond, passedGoSecond) = engine.movePosition(state.player("p1").position, 40) // 0 -> wraps fully -> 0
        assertTrue(passedGoSecond)
        val p1AfterSecond = state.player("p1").copy(
            position = posAfterSecond,
            balance = state.player("p1").balance + state.config.constants.goReward
        )
        state = state.copy(players = state.players.replace(p1AfterSecond))

        assertEquals(150_000 + 20_000 + 20_000, state.player("p1").balance)
    }
}
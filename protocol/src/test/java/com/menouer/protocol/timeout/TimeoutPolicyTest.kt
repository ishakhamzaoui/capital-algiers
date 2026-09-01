package com.menouer.protocol.timeout

import com.menouer.rules_engine.model.TurnPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeoutPolicyTest {

    @Test
    fun `AWAITING_ROLL defaults to auto-roll`() {
        assertEquals(TimeoutAction.AUTO_ROLL, TimeoutPolicy.defaultActionFor(TurnPhase.AWAITING_ROLL))
    }

    @Test
    fun `AWAITING_JAIL_DECISION also defaults to auto-roll`() {
        assertEquals(TimeoutAction.AUTO_ROLL, TimeoutPolicy.defaultActionFor(TurnPhase.AWAITING_JAIL_DECISION))
    }

    @Test
    fun `AWAITING_PURCHASE_DECISION defaults to auto-decline purchase`() {
        assertEquals(
            TimeoutAction.AUTO_DECLINE_PURCHASE,
            TimeoutPolicy.defaultActionFor(TurnPhase.AWAITING_PURCHASE_DECISION)
        )
    }

    @Test
    fun `AWAITING_OPTIONAL_ACTIONS defaults to auto-end-turn`() {
        assertEquals(TimeoutAction.AUTO_END_TURN, TimeoutPolicy.defaultActionFor(TurnPhase.AWAITING_OPTIONAL_ACTIONS))
    }

    @Test
    fun `IN_TRADE defaults to auto-decline trade`() {
        assertEquals(TimeoutAction.AUTO_DECLINE_TRADE, TimeoutPolicy.defaultActionFor(TurnPhase.IN_TRADE))
    }

    @Test
    fun `IN_AUCTION has no generic default (its own dedicated timeout applies instead)`() {
        assertNull(TimeoutPolicy.defaultActionFor(TurnPhase.IN_AUCTION))
    }

    @Test
    fun `RESOLVING_LANDING has no default (transient, host-internal only)`() {
        assertNull(TimeoutPolicy.defaultActionFor(TurnPhase.RESOLVING_LANDING))
    }

    @Test
    fun `GAME_OVER has no default`() {
        assertNull(TimeoutPolicy.defaultActionFor(TurnPhase.GAME_OVER))
    }
}
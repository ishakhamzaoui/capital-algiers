package com.menouer.protocol.host

import com.menouer.economy_data.EconomyConfigLoader
import com.menouer.economy_data.EconomyConfigValidator
import com.menouer.protocol.envelope.ClientEnvelope
import com.menouer.protocol.message.ClientMessage
import com.menouer.protocol.message.HostMessage
import com.menouer.protocol.time.FakeClock
import com.menouer.protocol.time.TimeoutConfig
import com.menouer.rules_engine.RulesEngineImpl
import com.menouer.rules_engine.dice.ScriptedDiceSource
import com.menouer.rules_engine.model.GameEvent
import com.menouer.rules_engine.model.TurnPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private const val GAME_ID = "game-1"
private const val PROTOCOL_VERSION = 1

class HostSessionTimeoutTest {

    private val realConfig = EconomyConfigLoader.loadDefault().also {
        EconomyConfigValidator.validate(it, "economy-config.json")
    }

    /** Small, fast values so tests don't need to advance a real clock by minutes. */
    private fun fastConfig(reconnectionGracePeriodSeconds: Long = 120) = TimeoutConfig(
        normalActionTimeoutSeconds = 30,
        auctionResponseTimeoutSeconds = 10,
        reconnectionGracePeriodSeconds = reconnectionGracePeriodSeconds,
        hostLossHeartbeatIntervalSeconds = 5
    )

    private fun events(broadcasts: List<HostBroadcast>): List<GameEvent> =
        broadcasts.flatMap { it.messages }.mapNotNull { (it as? HostMessage.GameEventMessage)?.event }

    private fun join(host: HostSession, name: String, id: String) {
        host.handle(
            ClientEnvelope("join-$id", PROTOCOL_VERSION, GAME_ID, "conn-$id", host.currentStateVersion(), ClientMessage.JoinRequest(name, "tok"))
        )
    }

    private fun send(host: HostSession, senderId: String, messageId: String, payload: ClientMessage): DispatchResult =
        host.handle(ClientEnvelope(messageId, PROTOCOL_VERSION, GAME_ID, senderId, host.currentStateVersion(), payload))

    @Test
    fun `checkTimeouts does nothing before a match has started`() {
        val clock = FakeClock(Instant.EPOCH)
        val host = HostSession(GAME_ID, PROTOCOL_VERSION, 6, realConfig, RulesEngineImpl(), ScriptedDiceSource(emptyList()), clock, fastConfig())
        clock.advanceBySeconds(1000)
        assertEquals(emptyList<HostBroadcast>(), host.checkTimeouts())
    }

    @Test
    fun `checkTimeouts does nothing before the deadline elapses`() {
        val clock = FakeClock(Instant.EPOCH)
        val host = HostSession(GAME_ID, PROTOCOL_VERSION, 6, realConfig, RulesEngineImpl(), ScriptedDiceSource.of(1 to 2), clock, fastConfig())
        join(host, "Amine", "0")
        join(host, "Yasmine", "1")
        host.startMatch()

        clock.advanceBySeconds(29) // just under the 30s normalActionTimeout
        assertEquals(emptyList<HostBroadcast>(), host.checkTimeouts())
        assertEquals(TurnPhase.AWAITING_ROLL, host.currentGameState()!!.phase)
    }

    @Test
    fun `an idle active player is auto-rolled, then auto-declines an unowned purchase, then the auction is auto-passed through`() {
        val clock = FakeClock(Instant.EPOCH)
        // total 3: GO -> index 3 (OuedKoriche, unowned).
        val host = HostSession(GAME_ID, PROTOCOL_VERSION, 6, realConfig, RulesEngineImpl(), ScriptedDiceSource.of(1 to 2), clock, fastConfig())
        join(host, "Amine", "0")
        join(host, "Yasmine", "1")
        host.startMatch()

        // --- Step 1: normalActionTimeout at AWAITING_ROLL -> auto-roll ---
        clock.advanceBySeconds(30)
        val rollBroadcasts = host.checkTimeouts()
        assertTrue(events(rollBroadcasts).any { it is GameEvent.DiceRolled })
        assertEquals(TurnPhase.AWAITING_PURCHASE_DECISION, host.currentGameState()!!.phase)

        // --- Step 2: normalActionTimeout at AWAITING_PURCHASE_DECISION -> auto-decline, triggers auction ---
        clock.advanceBySeconds(30)
        val declineBroadcasts = host.checkTimeouts()
        assertTrue(events(declineBroadcasts).any { it is GameEvent.AuctionStarted })
        assertEquals(TurnPhase.IN_AUCTION, host.currentGameState()!!.phase)
        assertEquals(setOf("p0", "p1"), host.currentGameState()!!.pendingAuction!!.eligibleBidders)

        // --- Step 3: auctionResponseTimeout -> every still-eligible bidder auto-passed ---
        clock.advanceBySeconds(10)
        val auctionTimeoutBroadcasts = host.checkTimeouts()
        val auctionEvents = events(auctionTimeoutBroadcasts)
        assertTrue(auctionEvents.any { it is GameEvent.AuctionPassed })
        // Nobody ever bid, and every eligible bidder was auto-passed, so the
        // auction concludes with no buyer (GameRules.md §7).
        assertTrue(auctionEvents.any { it is GameEvent.AuctionEndedWithNoBids })
        assertNull(host.currentGameState()!!.pendingAuction)
    }

    @Test
    fun `an idle active player with optional actions pending is auto-ended`() {
        val clock = FakeClock(Instant.EPOCH)
        val host = HostSession(GAME_ID, PROTOCOL_VERSION, 6, realConfig, RulesEngineImpl(), ScriptedDiceSource.of(1 to 2), clock, fastConfig())
        join(host, "Amine", "0")
        join(host, "Yasmine", "1")
        host.startMatch()

        // Real (non-timeout) roll + buy to reach AWAITING_OPTIONAL_ACTIONS cleanly.
        send(host, "p0", "roll-1", ClientMessage.RollDiceRequest)
        send(host, "p0", "buy-1", ClientMessage.BuyAssetRequest("OuedKoriche"))
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, host.currentGameState()!!.phase)

        clock.advanceBySeconds(30)
        val broadcasts = host.checkTimeouts()
        assertTrue(events(broadcasts).any { it is GameEvent.TurnChanged })
        assertEquals("p1", host.currentGameState()!!.activePlayerId)
        assertEquals(TurnPhase.AWAITING_ROLL, host.currentGameState()!!.phase)
    }

    @Test
    fun `a pending trade proposal is auto-declined on timeout`() {
        val clock = FakeClock(Instant.EPOCH)
        val host = HostSession(GAME_ID, PROTOCOL_VERSION, 6, realConfig, RulesEngineImpl(), ScriptedDiceSource.of(1 to 2), clock, fastConfig())
        join(host, "Amine", "0")
        join(host, "Yasmine", "1")
        host.startMatch()
        send(host, "p0", "roll-1", ClientMessage.RollDiceRequest)
        send(host, "p0", "buy-1", ClientMessage.BuyAssetRequest("OuedKoriche"))

        val proposeResult = send(
            host, "p0", "trade-1",
            ClientMessage.TradeProposalRequest(toPlayerId = "p1", offeredCash = 100)
        )
        check(proposeResult is DispatchResult.Applied) { "trade proposal was rejected: $proposeResult" }
        assertEquals(TurnPhase.IN_TRADE, host.currentGameState()!!.phase)

        clock.advanceBySeconds(30)
        val broadcasts = host.checkTimeouts()
        val tradeResolved = events(broadcasts).filterIsInstance<GameEvent.TradeResolved>().single()
        assertEquals(false, tradeResolved.accepted)
        // Reverts to whatever phase the trade interrupted (RulesEngine's own TradeState.previousPhase).
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, host.currentGameState()!!.phase)
    }

    @Test
    fun `reconnection grace period expiry auto-progresses a disconnected active player's turn`() {
        val clock = FakeClock(Instant.EPOCH)
        // normalActionTimeout deliberately far longer than the grace period,
        // so this test proves the grace-period check fires independently —
        // not merely because the ordinary per-phase timeout also happened to elapse.
        val config = fastConfig(reconnectionGracePeriodSeconds = 50).copy(normalActionTimeoutSeconds = 1000)
        val host = HostSession(GAME_ID, PROTOCOL_VERSION, 6, realConfig, RulesEngineImpl(), ScriptedDiceSource.of(1 to 2), clock, config)
        join(host, "Amine", "0")
        join(host, "Yasmine", "1")
        host.startMatch()

        host.markDisconnected("p0") // p0 is the active player, at AWAITING_ROLL
        clock.advanceBySeconds(50) // exactly the grace period; normalActionTimeout (1000s) has NOT elapsed

        val broadcasts = host.checkTimeouts()
        // LIMITATION (see HostSession.checkTimeouts' doc): this still rolls
        // dice for the disconnected player rather than literally skipping
        // their turn dice-free, pending a decision on adding a dedicated
        // RulesEngine.skipTurn capability.
        assertTrue(events(broadcasts).any { it is GameEvent.DiceRolled })
    }

    @Test
    fun `maybeSendHeartbeat is due immediately, then not again until the interval elapses`() {
        val clock = FakeClock(Instant.EPOCH)
        val host = HostSession(GAME_ID, PROTOCOL_VERSION, 6, realConfig, RulesEngineImpl(), ScriptedDiceSource(emptyList()), clock, fastConfig())

        val first = host.maybeSendHeartbeat()
        assertTrue(first != null)

        val second = host.maybeSendHeartbeat()
        assertNull(second)

        clock.advanceBySeconds(5)
        val third = host.maybeSendHeartbeat()
        assertTrue(third != null)
    }
}
package com.menouer.protocol.transport

import com.menouer.economy_data.EconomyConfigLoader
import com.menouer.economy_data.EconomyConfigValidator
import com.menouer.protocol.envelope.ClientEnvelope
import com.menouer.protocol.envelope.HostEnvelope
import com.menouer.protocol.host.DispatchResult
import com.menouer.protocol.host.HostSession
import com.menouer.protocol.message.ClientMessage
import com.menouer.protocol.message.ErrorCode
import com.menouer.protocol.message.HostMessage
import com.menouer.protocol.validation.MatchStatus
import com.menouer.rules_engine.RulesEngineImpl
import com.menouer.rules_engine.dice.ScriptedDiceSource
import com.menouer.rules_engine.model.GameEvent
import com.menouer.rules_engine.model.TurnPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GAME_ID = "game-1"
private const val PROTOCOL_VERSION = 1

/**
 * Exercises the whole `:protocol` stack end to end through [InMemoryTransport]
 * — no real sockets, per this milestone's own scope. Matches
 * DevelopmentRoadmap.md's M4 exit criterion: "a host + N simulated
 * in-process clients can complete a full game via the protocol layer,
 * including at least one simulated disconnect/reconnect and one
 * duplicate-message replay that doesn't double-apply."
 */
class InMemoryTransportGameTest {

    private val realConfig = EconomyConfigLoader.loadDefault().also {
        EconomyConfigValidator.validate(it, "economy-config.json")
    }

    private fun events(envelopes: List<HostEnvelope>): List<GameEvent> =
        envelopes.mapNotNull { (it.payload as? HostMessage.GameEventMessage)?.event }

    @Test
    fun `two clients join, start a match, and play through a roll and a purchase`() {
        val dice = ScriptedDiceSource.of(1 to 2) // total 3: GO -> index 3 (OuedKoriche)
        val hostSession = HostSession(GAME_ID, PROTOCOL_VERSION, 6, realConfig, RulesEngineImpl(), dice)
        val transport = InMemoryTransport(hostSession)

        val amine = SimulatedClient(transport, GAME_ID, PROTOCOL_VERSION, "conn-1")
        val yasmine = SimulatedClient(transport, GAME_ID, PROTOCOL_VERSION, "conn-2")

        // --- Join ---
        amine.send(ClientMessage.JoinRequest("Amine", "tok1"))
        assertEquals("p0", amine.assignedPlayerId)

        yasmine.send(ClientMessage.JoinRequest("Yasmine", "tok2"))
        assertEquals("p1", yasmine.assignedPlayerId)

        // Amine's join broadcast a lobby update that Yasmine (already
        // connected, not yet joined) also receives — and Yasmine's own join
        // broadcasts a second one Amine sees too.
        val amineLobbyUpdates = amine.inbox.count { it.payload is HostMessage.LobbyStateChanged }
        assertTrue("expected at least one lobby broadcast", amineLobbyUpdates >= 1)

        // --- Start match ---
        val startResult = transport.startMatch()
        assertTrue(startResult is DispatchResult.Applied)

        val amineSnapshotMsg = amine.inbox.last().payload as HostMessage.Snapshot
        val yasmineSnapshotMsg = yasmine.inbox.last().payload as HostMessage.Snapshot
        assertEquals(TurnPhase.AWAITING_ROLL, amineSnapshotMsg.snapshot.phase)
        assertEquals(TurnPhase.AWAITING_ROLL, yasmineSnapshotMsg.snapshot.phase)
        assertEquals(MatchStatus.IN_PROGRESS, hostSession.currentMatchStatus())

        // --- Roll: verified event sequence is exactly DiceRolled, PlayerMoved
        // (applyRoll's commit) then PurchaseDecisionPending (the
        // auto-triggered resolveLanding commit) — confirmed against the real
        // rules-engine source before writing this, not assumed. ---
        val amineBaseline = amine.inbox.size
        val yasmineBaseline = yasmine.inbox.size

        amine.send(ClientMessage.RollDiceRequest)

        val amineRollEvents = events(amine.inbox.drop(amineBaseline))
        val yasmineRollEvents = events(yasmine.inbox.drop(yasmineBaseline))
        val expectedEventTypes = listOf(
            GameEvent.DiceRolled::class,
            GameEvent.PlayerMoved::class,
            GameEvent.PurchaseDecisionPending::class
        )
        assertEquals(expectedEventTypes, amineRollEvents.map { it::class })
        // Both connected clients see the same broadcast events, not just the roller.
        assertEquals(expectedEventTypes, yasmineRollEvents.map { it::class })

        val stateAfterRoll = hostSession.currentGameState()!!
        assertEquals(TurnPhase.AWAITING_PURCHASE_DECISION, stateAfterRoll.phase)
        assertEquals(3, stateAfterRoll.player("p0").position)

        // --- Buy the pending asset ---
        amine.send(ClientMessage.BuyAssetRequest("OuedKoriche"))
        val stateAfterBuy = hostSession.currentGameState()!!
        assertEquals("p0", stateAfterBuy.assets.getValue("OuedKoriche").ownerId)
        assertEquals(150000 - 6000, stateAfterBuy.player("p0").balance)
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, stateAfterBuy.phase)

        // --- Exit criterion: duplicate-message replay must not double-apply ---
        amine.resendLast() // re-sends the exact same BuyAssetRequest envelope (same messageId)

        val stateAfterDuplicate = hostSession.currentGameState()!!
        assertEquals(150000 - 6000, stateAfterDuplicate.player("p0").balance) // unchanged, not double-charged
        assertEquals("p0", stateAfterDuplicate.assets.getValue("OuedKoriche").ownerId) // unchanged

        val lastToAmine = amine.inbox.last().payload
        assertTrue(lastToAmine is HostMessage.ErrorResponse)
        assertEquals(ErrorCode.DUPLICATE_REQUEST, (lastToAmine as HostMessage.ErrorResponse).errorCode)
        // The rejection is targeted only to the sender, not broadcast.
        assertFalse(yasmine.inbox.last().payload is HostMessage.ErrorResponse)
    }

    @Test
    fun `exit criterion - a simulated disconnect and reconnect completes correctly`() {
        val hostSession = HostSession(
            GAME_ID, PROTOCOL_VERSION, 6, realConfig, RulesEngineImpl(), ScriptedDiceSource(emptyList())
        )
        val transport = InMemoryTransport(hostSession)

        val amine = SimulatedClient(transport, GAME_ID, PROTOCOL_VERSION, "conn-1")
        val yasmine = SimulatedClient(transport, GAME_ID, PROTOCOL_VERSION, "conn-2")
        amine.send(ClientMessage.JoinRequest("Amine", "tok1"))
        yasmine.send(ClientMessage.JoinRequest("Yasmine", "tok2"))
        transport.startMatch()

        // --- Disconnect ---
        transport.disconnect("p0")
        val disconnectMsg = yasmine.inbox.last().payload as HostMessage.PlayerConnectionChanged
        assertEquals("p0", disconnectMsg.playerId)
        assertEquals(false, disconnectMsg.connected)

        // --- Reconnect: transport-level reconnect (new/same physical connection
        // re-registers its channel) is a distinct step from the protocol-level
        // ReconnectRequest/ClientAcknowledgement handshake. ---
        amine.reconnectChannel()

        amine.send(ClientMessage.ReconnectRequest(matchLocalPlayerId = "p0"))
        val reconnectSnapshotMsg = amine.inbox.last().payload as HostMessage.Snapshot
        // Per MultiplayerProtocol.md §4/§8: connected status is NOT restored
        // merely by requesting reconnection — only once acknowledged.
        assertFalse(reconnectSnapshotMsg.snapshot.players.single { it.playerId == "p0" }.connected)
        // Targeted only to the reconnecting client, not broadcast.
        assertFalse(yasmine.inbox.last().payload is HostMessage.Snapshot)

        amine.send(ClientMessage.ClientAcknowledgement(acknowledgedStateVersion = amine.lastKnownStateVersion))
        val reconnectedMsg = yasmine.inbox.last().payload as HostMessage.PlayerConnectionChanged
        assertEquals("p0", reconnectedMsg.playerId)
        assertEquals(true, reconnectedMsg.connected)
    }

    @Test
    fun `wrong-protocol-version message is rejected and does not affect other clients`() {
        val hostSession = HostSession(
            GAME_ID, PROTOCOL_VERSION, 6, realConfig, RulesEngineImpl(), ScriptedDiceSource(emptyList())
        )
        val transport = InMemoryTransport(hostSession)
        val amine = SimulatedClient(transport, GAME_ID, PROTOCOL_VERSION, "conn-1")
        val yasmine = SimulatedClient(transport, GAME_ID, PROTOCOL_VERSION, "conn-2")
        amine.send(ClientMessage.JoinRequest("Amine", "tok1"))
        yasmine.send(ClientMessage.JoinRequest("Yasmine", "tok2"))

        val yasmineInboxBefore = yasmine.inbox.size
        transport.send(
            ClientEnvelope(
                messageId = "bad-1",
                protocolVersion = 99,
                gameId = GAME_ID,
                senderId = "p0",
                stateVersion = hostSession.currentStateVersion(),
                payload = ClientMessage.ReadyChanged(ready = true)
            )
        )

        val rejection = amine.inbox.last().payload as HostMessage.ErrorResponse
        assertEquals(ErrorCode.PROTOCOL_VERSION_MISMATCH, rejection.errorCode)
        assertEquals(yasmineInboxBefore, yasmine.inbox.size) // untouched
        assertNull(hostSession.currentGameState()) // never reached the engine; match never started in this test
    }
}
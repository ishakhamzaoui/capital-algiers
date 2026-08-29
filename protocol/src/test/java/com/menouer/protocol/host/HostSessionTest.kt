package com.menouer.protocol.host

import com.menouer.economy_data.EconomyConfigLoader
import com.menouer.economy_data.EconomyConfigValidator
import com.menouer.protocol.envelope.ClientEnvelope
import com.menouer.protocol.message.ClientMessage
import com.menouer.protocol.message.ErrorCode
import com.menouer.protocol.message.HostMessage
import com.menouer.protocol.validation.MatchStatus
import com.menouer.rules_engine.RulesEngineImpl
import com.menouer.rules_engine.dice.ScriptedDiceSource
import com.menouer.rules_engine.model.TurnPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GAME_ID = "game-1"
private const val PROTOCOL_VERSION = 1

class HostSessionTest {

    /** The real bundled config (SRS.md FR-008/FR-009: never a hand-rolled sample), same as production/M3. */
    private val realConfig = EconomyConfigLoader.loadDefault().also {
        EconomyConfigValidator.validate(it, "economy-config.json")
    }

    private fun session(dice: ScriptedDiceSource = ScriptedDiceSource(emptyList())) = HostSession(
        gameId = GAME_ID,
        protocolVersion = PROTOCOL_VERSION,
        matchCapacity = 6,
        config = realConfig,
        engine = RulesEngineImpl(),
        diceSource = dice
    )

    private fun join(host: HostSession, name: String, messageId: String): String {
        val result = host.handle(
            ClientEnvelope(
                messageId = messageId,
                protocolVersion = PROTOCOL_VERSION,
                gameId = GAME_ID,
                senderId = "conn-$messageId",
                stateVersion = host.currentStateVersion(),
                payload = ClientMessage.JoinRequest(displayName = name, token = "token-$messageId")
            )
        )
        check(result is DispatchResult.Joined) { "expected Joined, got $result" }
        return result.assignedPlayerId
    }

    // ---- Lobby / join / start ----------------------------------------------

    @Test
    fun `joining assigns sequential player ids and broadcasts the roster`() {
        val host = session()

        val p0 = join(host, "Amine", "m1")
        assertEquals("p0", p0)

        val result = host.handle(
            ClientEnvelope("m2", PROTOCOL_VERSION, GAME_ID, "conn-m2", host.currentStateVersion(), ClientMessage.JoinRequest("Yasmine", "tok2"))
        )
        check(result is DispatchResult.Joined)
        assertEquals("p1", result.assignedPlayerId)

        val broadcast = result.broadcasts.single()
        val lobbyMsg = broadcast.messages.single() as HostMessage.LobbyStateChanged
        assertEquals(setOf("p0", "p1"), lobbyMsg.players.map { it.playerId }.toSet())
        assertEquals(6, lobbyMsg.matchCapacity)
    }

    @Test
    fun `starting a match with fewer than two players is rejected`() {
        val host = session()
        join(host, "Amine", "m1")

        val result = host.startMatch()
        assertEquals(DispatchResult.Rejected(ErrorCode.INVALID_PAYLOAD), result)
        assertEquals(MatchStatus.LOBBY, host.currentMatchStatus())
    }

    @Test
    fun `starting a match with two players initializes GameState correctly`() {
        val host = session()
        join(host, "Amine", "m1")
        join(host, "Yasmine", "m2")

        val result = host.startMatch()
        assertTrue(result is DispatchResult.Applied)
        assertEquals(MatchStatus.IN_PROGRESS, host.currentMatchStatus())

        val state = host.currentGameState()!!
        assertEquals(TurnPhase.AWAITING_ROLL, state.phase)
        assertEquals("p0", state.activePlayerId)
        assertEquals(listOf(150000, 150000), state.players.map { it.balance })
        assertEquals(22, state.config.properties.size) // sanity: real config, not a stub (SRS.md §3)
        assertTrue(state.assets.containsKey("OuedKoriche"))
    }

    // ---- Gameplay dispatch: happy path (roll -> auto-resolve landing -> purchase) ----

    @Test
    fun `rolling dice moves the player and auto-resolves onto a purchase decision`() {
        val host = session(dice = ScriptedDiceSource.of(1 to 2)) // total 3: GO -> index 3 (OuedKoriche)
        join(host, "Amine", "m1")
        join(host, "Yasmine", "m2")
        host.startMatch()

        val versionBeforeRoll = host.currentStateVersion()
        val result = host.handle(
            ClientEnvelope("roll-1", PROTOCOL_VERSION, GAME_ID, "p0", versionBeforeRoll, ClientMessage.RollDiceRequest)
        )

        check(result is DispatchResult.Applied)
        // One commit for applyRoll (DiceRolled/PlayerMoved), one for the auto-triggered resolveLanding.
        assertEquals(2, result.broadcasts.size)
        assertEquals(versionBeforeRoll + 1, result.broadcasts[0].stateVersion)
        assertEquals(versionBeforeRoll + 2, result.broadcasts[1].stateVersion)

        val state = host.currentGameState()!!
        assertEquals(TurnPhase.AWAITING_PURCHASE_DECISION, state.phase)
        assertEquals(3, state.player("p0").position)
    }

    @Test
    fun `rolling from a non-active player is rejected before reaching the engine`() {
        val host = session(dice = ScriptedDiceSource(emptyList())) // never consumed
        join(host, "Amine", "m1")
        join(host, "Yasmine", "m2")
        host.startMatch()

        val result = host.handle(
            ClientEnvelope("roll-1", PROTOCOL_VERSION, GAME_ID, "p1", host.currentStateVersion(), ClientMessage.RollDiceRequest)
        )
        assertEquals(DispatchResult.Rejected(ErrorCode.UNAUTHORIZED_PLAYER), result)
    }

    @Test
    fun `buying the pending asset succeeds and deducts the price`() {
        val host = session(dice = ScriptedDiceSource.of(1 to 2))
        join(host, "Amine", "m1")
        join(host, "Yasmine", "m2")
        host.startMatch()
        host.handle(ClientEnvelope("roll-1", PROTOCOL_VERSION, GAME_ID, "p0", host.currentStateVersion(), ClientMessage.RollDiceRequest))

        val result = host.handle(
            ClientEnvelope(
                "buy-1", PROTOCOL_VERSION, GAME_ID, "p0", host.currentStateVersion(),
                ClientMessage.BuyAssetRequest(assetId = "OuedKoriche")
            )
        )
        assertTrue(result is DispatchResult.Applied)

        val state = host.currentGameState()!!
        assertEquals("p0", state.assets.getValue("OuedKoriche").ownerId)
        assertEquals(150000 - 6000, state.player("p0").balance)
        assertEquals(TurnPhase.AWAITING_OPTIONAL_ACTIONS, state.phase)
    }

    // ---- A genuine EngineError, mapped through EngineErrorMapper -----------

    @Test
    fun `an engine-level rejection (incomplete group) is mapped to an ErrorCode`() {
        val host = session(dice = ScriptedDiceSource.of(1 to 2)) // lands on OuedKoriche
        join(host, "Amine", "m1")
        join(host, "Yasmine", "m2")
        host.startMatch()
        host.handle(ClientEnvelope("roll-1", PROTOCOL_VERSION, GAME_ID, "p0", host.currentStateVersion(), ClientMessage.RollDiceRequest))
        host.handle(
            ClientEnvelope("buy-1", PROTOCOL_VERSION, GAME_ID, "p0", host.currentStateVersion(), ClientMessage.BuyAssetRequest("OuedKoriche"))
        )

        // p0 owns OuedKoriche but not Dergana, so the Brown group isn't complete.
        // RequestValidator only checks that p0 owns the referenced asset (it does) —
        // this rejection can only come from RulesEngine.build itself, proving the
        // EngineError -> ErrorCode wiring actually runs end to end.
        val result = host.handle(
            ClientEnvelope(
                "build-1", PROTOCOL_VERSION, GAME_ID, "p0", host.currentStateVersion(),
                ClientMessage.BuildRequest(assetId = "OuedKoriche")
            )
        )
        assertEquals(DispatchResult.Rejected(ErrorCode.INVALID_PAYLOAD), result)
    }

    @Test
    fun `ready change toggles the roster's ready flag`() {
        val host = session()
        join(host, "Amine", "m1")

        val result = host.handle(
            ClientEnvelope("ready-1", PROTOCOL_VERSION, GAME_ID, "p0", host.currentStateVersion(), ClientMessage.ReadyChanged(ready = true))
        )
        check(result is DispatchResult.Applied)
        val lobbyMsg = result.broadcasts.single().messages.single() as HostMessage.LobbyStateChanged
        assertEquals(true, lobbyMsg.players.single { it.playerId == "p0" }.ready)
    }

    // ---- Reconnect / disconnect bookkeeping ---------------------------------

    @Test
    fun `reconnect broadcasts a connection-status change`() {
        val host = session()
        join(host, "Amine", "m1")
        join(host, "Yasmine", "m2")

        val disconnectBroadcast = host.markDisconnected("p0")
        val connChanged = disconnectBroadcast.messages.single() as HostMessage.PlayerConnectionChanged
        assertEquals("p0", connChanged.playerId)
        assertEquals(false, connChanged.connected)

        val result = host.handle(
            ClientEnvelope(
                "reconnect-1", PROTOCOL_VERSION, GAME_ID, "p0", host.currentStateVersion(),
                ClientMessage.ReconnectRequest(matchLocalPlayerId = "p0")
            )
        )
        check(result is DispatchResult.Applied)
        val reconnectMsg = result.broadcasts.single().messages.single() as HostMessage.PlayerConnectionChanged
        assertEquals("p0", reconnectMsg.playerId)
        assertEquals(true, reconnectMsg.connected)
    }

    @Test
    fun `snapshot request from a known connected player is accepted as a no-op for now`() {
        val host = session()
        join(host, "Amine", "m1")

        val result = host.handle(
            ClientEnvelope("snap-1", PROTOCOL_VERSION, GAME_ID, "p0", host.currentStateVersion(), ClientMessage.SnapshotRequest)
        )
        assertEquals(DispatchResult.Applied(emptyList()), result)
    }

    @Test
    fun `wrong-match request is rejected regardless of session state`() {
        val host = session()
        val result = host.handle(
            ClientEnvelope("m1", PROTOCOL_VERSION, "other-game", "conn-1", 0, ClientMessage.JoinRequest("Amine", "tok"))
        )
        assertEquals(DispatchResult.Rejected(ErrorCode.INVALID_PAYLOAD), result)
        assertNull(host.currentGameState())
    }
}
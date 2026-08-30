package com.menouer.protocol.validation

import com.menouer.economy_data.BoardConfig
import com.menouer.economy_data.EconomyConstants
import com.menouer.protocol.dedupe.RequestDeduplicator
import com.menouer.protocol.envelope.ClientEnvelope
import com.menouer.protocol.message.ClientMessage
import com.menouer.protocol.message.ErrorCode
import com.menouer.rules_engine.JailAction
import com.menouer.rules_engine.model.AssetState
import com.menouer.rules_engine.model.AuctionState
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerState
import com.menouer.rules_engine.model.TradeProposal
import com.menouer.rules_engine.model.TradeState
import com.menouer.rules_engine.model.TurnPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val GAME_ID = "game-1"
private const val PROTOCOL_VERSION = 1

class RequestValidatorTest {

    // ---- Fixtures ----------------------------------------------------

    /** BoardConfig's actual content is never read by RequestValidator — an empty one just satisfies GameState's type. */
    private fun emptyBoardConfig() = BoardConfig(
        constants = EconomyConstants(
            startingMoney = 0, goReward = 0, jailFine = 0,
            mortgageInterestRate = 0.0, buildingResaleRate = 0.0,
            totalHouses = 0, totalHotels = 0,
            auctionMinimumBid = 0, auctionMinimumIncrement = 0,
            singleUtilityMultiplier = 0, bothUtilitiesMultiplier = 0,
            incomeTax = 0, luxuryTax = 0
        ),
        spaces = emptyList(),
        properties = emptyList(),
        stations = emptyList(),
        utilities = emptyList(),
        chanceDeck = emptyList(),
        chestDeck = emptyList()
    )

    private fun gameState(
        phase: TurnPhase = TurnPhase.AWAITING_ROLL,
        activePlayerId: String = "p1",
        assets: Map<String, AssetState> = emptyMap(),
        pendingAuction: AuctionState? = null,
        pendingTrade: TradeState? = null
    ) = GameState(
        stateVersion = 0,
        config = emptyBoardConfig(),
        players = listOf(
            PlayerState(id = "p1", balance = 150000, position = 0),
            PlayerState(id = "p2", balance = 150000, position = 0)
        ),
        assets = assets,
        activePlayerId = activePlayerId,
        phase = phase,
        bankHouses = 32,
        bankHotels = 12,
        chanceDeck = emptyList(),
        chestDeck = emptyList(),
        pendingAuction = pendingAuction,
        pendingTrade = pendingTrade
    )

    private fun context(
        matchStatus: MatchStatus = MatchStatus.IN_PROGRESS,
        gameState: GameState? = gameState(),
        knownPlayerIds: Set<String> = setOf("p1", "p2"),
        connectedPlayerIds: Set<String> = setOf("p1", "p2"),
        currentStateVersion: Long = 10,
        matchCapacity: Int = 6
    ) = ValidationContext(
        gameId = GAME_ID,
        matchStatus = matchStatus,
        supportedProtocolVersion = PROTOCOL_VERSION,
        matchCapacity = matchCapacity,
        knownPlayerIds = knownPlayerIds,
        connectedPlayerIds = connectedPlayerIds,
        currentStateVersion = currentStateVersion,
        gameState = gameState
    )

    private fun envelope(
        senderId: String = "p1",
        payload: ClientMessage,
        messageId: String = "msg-1",
        protocolVersion: Int = PROTOCOL_VERSION,
        stateVersion: Long = 10,
        gameId: String = GAME_ID
    ) = ClientEnvelope(
        messageId = messageId,
        protocolVersion = protocolVersion,
        gameId = gameId,
        senderId = senderId,
        stateVersion = stateVersion,
        payload = payload
    )

    private fun validator() = RequestValidator(RequestDeduplicator())

    // ---- Cross-cutting: gameId / protocol version / duplicate ---------

    @Test
    fun `wrong gameId is rejected regardless of message type`() {
        val result = validator().validate(
            envelope(payload = ClientMessage.EndTurnRequest, gameId = "some-other-game"),
            context()
        )
        assertEquals(ErrorCode.INVALID_PAYLOAD, result)
    }

    @Test
    fun `mismatched protocol version is rejected`() {
        val result = validator().validate(
            envelope(payload = ClientMessage.EndTurnRequest, protocolVersion = 99),
            context(gameState = gameState(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS))
        )
        assertEquals(ErrorCode.PROTOCOL_VERSION_MISMATCH, result)
    }

    @Test
    fun `duplicate messageId from the same sender is rejected on the second attempt`() {
        val v = validator()
        val env = envelope(payload = ClientMessage.RollDiceRequest, messageId = "dup-1")
        val ctx = context()

        assertNull(v.validate(env, ctx))
        assertEquals(ErrorCode.DUPLICATE_REQUEST, v.validate(env, ctx))
    }

    // ---- JoinRequest ---------------------------------------------------

    @Test
    fun `join succeeds when lobby is open and has room`() {
        val result = validator().validate(
            envelope(senderId = "new-conn", payload = ClientMessage.JoinRequest("Amine", "token")),
            context(matchStatus = MatchStatus.LOBBY, gameState = null, knownPlayerIds = setOf("p1"))
        )
        assertNull(result)
    }

    @Test
    fun `join is rejected once the match has started`() {
        val result = validator().validate(
            envelope(senderId = "new-conn", payload = ClientMessage.JoinRequest("Amine", "token")),
            context(matchStatus = MatchStatus.IN_PROGRESS)
        )
        assertEquals(ErrorCode.GAME_STARTED, result)
    }

    @Test
    fun `join is rejected when the lobby is at capacity`() {
        val result = validator().validate(
            envelope(senderId = "new-conn", payload = ClientMessage.JoinRequest("Amine", "token")),
            context(
                matchStatus = MatchStatus.LOBBY,
                gameState = null,
                knownPlayerIds = setOf("p1", "p2"),
                matchCapacity = 2
            )
        )
        assertEquals(ErrorCode.GAME_FULL, result)
    }

    // ---- ReconnectRequest ------------------------------------------------

    @Test
    fun `reconnect succeeds for a known but currently disconnected player`() {
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.ReconnectRequest(matchLocalPlayerId = "p1")),
            context(connectedPlayerIds = setOf("p2")) // p1 is NOT currently connected
        )
        assertNull(result)
    }

    @Test
    fun `reconnect is rejected for a sender who never joined`() {
        val result = validator().validate(
            envelope(senderId = "ghost", payload = ClientMessage.ReconnectRequest(matchLocalPlayerId = "ghost")),
            context()
        )
        assertEquals(ErrorCode.UNAUTHORIZED_PLAYER, result)
    }

    @Test
    fun `reconnect is rejected once the match has ended`() {
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.ReconnectRequest(matchLocalPlayerId = "p1")),
            context(matchStatus = MatchStatus.ENDED, gameState = null)
        )
        assertEquals(ErrorCode.MATCH_UNAVAILABLE, result)
    }

    // ---- SnapshotRequest / ClientAcknowledgement (resync-only) ----------

    @Test
    fun `snapshot request succeeds for a known connected player regardless of stateVersion staleness`() {
        val result = validator().validate(
            envelope(payload = ClientMessage.SnapshotRequest, stateVersion = 0), // deliberately stale
            context()
        )
        assertNull(result)
    }

    @Test
    fun `acknowledgement succeeds from a known but currently disconnected sender (mid-reconnect)`() {
        // A client sends ClientAcknowledgement specifically as the last step
        // of reconnecting (MultiplayerProtocol.md §4/§8) — at that point it
        // is, by definition, not yet marked connected. Requiring
        // connected-ness here would make finishing a reconnect impossible.
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.ClientAcknowledgement(acknowledgedStateVersion = 10)),
            context(connectedPlayerIds = setOf("p2"))
        )
        assertNull(result)
    }

    @Test
    fun `acknowledgement is rejected from a sender who never joined`() {
        val result = validator().validate(
            envelope(senderId = "ghost", payload = ClientMessage.ClientAcknowledgement(acknowledgedStateVersion = 10)),
            context()
        )
        assertEquals(ErrorCode.UNAUTHORIZED_PLAYER, result)
    }

    // ---- ReadyChanged ----------------------------------------------------

    @Test
    fun `ready change succeeds during the lobby`() {
        val result = validator().validate(
            envelope(payload = ClientMessage.ReadyChanged(ready = true)),
            context(matchStatus = MatchStatus.LOBBY, gameState = null)
        )
        assertNull(result)
    }

    @Test
    fun `ready change is rejected once the match has started`() {
        val result = validator().validate(
            envelope(payload = ClientMessage.ReadyChanged(ready = true)),
            context(matchStatus = MatchStatus.IN_PROGRESS)
        )
        assertEquals(ErrorCode.INVALID_PHASE, result)
    }

    @Test
    fun `ready change with a stale stateVersion is rejected`() {
        val result = validator().validate(
            envelope(payload = ClientMessage.ReadyChanged(ready = true), stateVersion = 1),
            context(matchStatus = MatchStatus.LOBBY, gameState = null, currentStateVersion = 10)
        )
        assertEquals(ErrorCode.STALE_STATE, result)
    }

    // ---- Gameplay: match-not-started guard --------------------------------

    @Test
    fun `gameplay message is rejected when there is no active match`() {
        val result = validator().validate(
            envelope(payload = ClientMessage.RollDiceRequest),
            context(matchStatus = MatchStatus.LOBBY, gameState = null)
        )
        assertEquals(ErrorCode.INVALID_PHASE, result)
    }

    @Test
    fun `gameplay message is rejected from a sender who is not connected`() {
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.RollDiceRequest),
            context(connectedPlayerIds = setOf("p2"))
        )
        assertEquals(ErrorCode.UNAUTHORIZED_PLAYER, result)
    }

    // ---- Gameplay: turn ownership + phase (RollDiceRequest / EndTurnRequest) ----

    @Test
    fun `roll dice succeeds for the active player in AWAITING_ROLL`() {
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.RollDiceRequest),
            context(gameState = gameState(phase = TurnPhase.AWAITING_ROLL, activePlayerId = "p1"))
        )
        assertNull(result)
    }

    @Test
    fun `roll dice also succeeds while awaiting a jail decision (doubles attempt)`() {
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.RollDiceRequest),
            context(gameState = gameState(phase = TurnPhase.AWAITING_JAIL_DECISION, activePlayerId = "p1"))
        )
        assertNull(result)
    }

    @Test
    fun `roll dice from a non-active player is rejected`() {
        val result = validator().validate(
            envelope(senderId = "p2", payload = ClientMessage.RollDiceRequest),
            context(gameState = gameState(phase = TurnPhase.AWAITING_ROLL, activePlayerId = "p1"))
        )
        assertEquals(ErrorCode.UNAUTHORIZED_PLAYER, result)
    }

    @Test
    fun `roll dice in the wrong phase is rejected`() {
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.RollDiceRequest),
            context(gameState = gameState(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS, activePlayerId = "p1"))
        )
        assertEquals(ErrorCode.INVALID_PHASE, result)
    }

    @Test
    fun `end turn succeeds for the active player in AWAITING_OPTIONAL_ACTIONS`() {
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.EndTurnRequest),
            context(gameState = gameState(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS, activePlayerId = "p1"))
        )
        assertNull(result)
    }

    // ---- Gameplay: BuyAssetRequest ----------------------------------------

    @Test
    fun `buying an unowned asset succeeds`() {
        val state = gameState(
            phase = TurnPhase.AWAITING_PURCHASE_DECISION,
            assets = mapOf("Dergana" to AssetState(id = "Dergana", ownerId = null))
        )
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.BuyAssetRequest(assetId = "Dergana")),
            context(gameState = state)
        )
        assertNull(result)
    }

    @Test
    fun `buying an already-owned asset is rejected`() {
        val state = gameState(
            phase = TurnPhase.AWAITING_PURCHASE_DECISION,
            assets = mapOf("Dergana" to AssetState(id = "Dergana", ownerId = "p2"))
        )
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.BuyAssetRequest(assetId = "Dergana")),
            context(gameState = state)
        )
        assertEquals(ErrorCode.ASSET_UNAVAILABLE, result)
    }

    @Test
    fun `buying a nonexistent asset is rejected`() {
        val state = gameState(phase = TurnPhase.AWAITING_PURCHASE_DECISION)
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.BuyAssetRequest(assetId = "NoSuchAsset")),
            context(gameState = state)
        )
        assertEquals(ErrorCode.ASSET_UNAVAILABLE, result)
    }

    // ---- Gameplay: ownership-gated actions (Build/Sell/Mortgage/Unmortgage) ----

    @Test
    fun `building on an asset you don't own is rejected`() {
        val state = gameState(
            phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS,
            assets = mapOf("Dergana" to AssetState(id = "Dergana", ownerId = "p2"))
        )
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.BuildRequest(assetId = "Dergana")),
            context(gameState = state)
        )
        assertEquals(ErrorCode.UNAUTHORIZED_PLAYER, result)
    }

    @Test
    fun `mortgaging an asset you own succeeds`() {
        val state = gameState(
            phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS,
            assets = mapOf("Dergana" to AssetState(id = "Dergana", ownerId = "p1"))
        )
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.MortgageRequest(assetId = "Dergana")),
            context(gameState = state)
        )
        assertNull(result)
    }

    // ---- Gameplay: auctions ------------------------------------------------

    @Test
    fun `an eligible bidder who has not passed can bid`() {
        val state = gameState(
            phase = TurnPhase.IN_AUCTION,
            pendingAuction = AuctionState(
                assetId = "Dergana", highestBid = 1000, highestBidderId = null,
                eligibleBidders = setOf("p1", "p2")
            )
        )
        val result = validator().validate(
            envelope(senderId = "p2", payload = ClientMessage.AuctionBidRequest(amount = 1500)),
            context(gameState = state)
        )
        assertNull(result)
    }

    @Test
    fun `a bidder who already passed cannot bid again`() {
        val state = gameState(
            phase = TurnPhase.IN_AUCTION,
            pendingAuction = AuctionState(
                assetId = "Dergana", highestBid = 1000, highestBidderId = "p1",
                eligibleBidders = setOf("p1", "p2"), passedBidders = setOf("p2")
            )
        )
        val result = validator().validate(
            envelope(senderId = "p2", payload = ClientMessage.AuctionBidRequest(amount = 1500)),
            context(gameState = state)
        )
        assertEquals(ErrorCode.UNAUTHORIZED_PLAYER, result)
    }

    @Test
    fun `a non-positive bid amount is rejected`() {
        val state = gameState(
            phase = TurnPhase.IN_AUCTION,
            pendingAuction = AuctionState(
                assetId = "Dergana", highestBid = 1000, highestBidderId = null,
                eligibleBidders = setOf("p1", "p2")
            )
        )
        val result = validator().validate(
            envelope(senderId = "p2", payload = ClientMessage.AuctionBidRequest(amount = 0)),
            context(gameState = state)
        )
        assertEquals(ErrorCode.INVALID_BID, result)
    }

    @Test
    fun `bidding with no pending auction is rejected`() {
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.AuctionBidRequest(amount = 1500)),
            context(gameState = gameState(phase = TurnPhase.IN_AUCTION, pendingAuction = null))
        )
        assertEquals(ErrorCode.UNAUTHORIZED_PLAYER, result)
    }

    // ---- Gameplay: trades ---------------------------------------------------

    @Test
    fun `only the trade's counterparty can respond to it`() {
        val state = gameState(
            phase = TurnPhase.IN_TRADE,
            pendingTrade = TradeState(
                proposal = TradeProposal(fromPlayerId = "p1", toPlayerId = "p2"),
                previousPhase = TurnPhase.AWAITING_OPTIONAL_ACTIONS
            )
        )
        val rejected = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.TradeResponseRequest(accept = true)),
            context(gameState = state)
        )
        val accepted = validator().validate(
            envelope(senderId = "p2", payload = ClientMessage.TradeResponseRequest(accept = true)),
            context(gameState = state)
        )
        assertEquals(ErrorCode.UNAUTHORIZED_PLAYER, rejected)
        assertNull(accepted)
    }

    @Test
    fun `proposing a trade with yourself is rejected`() {
        val result = validator().validate(
            envelope(
                senderId = "p1",
                payload = ClientMessage.TradeProposalRequest(toPlayerId = "p1")
            ),
            context(gameState = gameState(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS))
        )
        assertEquals(ErrorCode.INVALID_PAYLOAD, result)
    }

    @Test
    fun `proposing a trade offering an asset you don't own is rejected`() {
        val state = gameState(
            phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS,
            assets = mapOf("Dergana" to AssetState(id = "Dergana", ownerId = "p2"))
        )
        val result = validator().validate(
            envelope(
                senderId = "p1",
                payload = ClientMessage.TradeProposalRequest(toPlayerId = "p2", offeredAssets = setOf("Dergana"))
            ),
            context(gameState = state)
        )
        assertEquals(ErrorCode.ASSET_UNAVAILABLE, result)
    }

    @Test
    fun `a well-formed trade proposal succeeds validation`() {
        val state = gameState(
            phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS,
            assets = mapOf(
                "Dergana" to AssetState(id = "Dergana", ownerId = "p1"),
                "OuedKoriche" to AssetState(id = "OuedKoriche", ownerId = "p2")
            )
        )
        val result = validator().validate(
            envelope(
                senderId = "p1",
                payload = ClientMessage.TradeProposalRequest(
                    toPlayerId = "p2",
                    offeredAssets = setOf("Dergana"),
                    requestedAssets = setOf("OuedKoriche")
                )
            ),
            context(gameState = state)
        )
        assertNull(result)
    }

    // ---- Gameplay: jail actions ----------------------------------------------

    @Test
    fun `jail action succeeds for the active player awaiting a jail decision`() {
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.JailActionRequest(action = JailAction.PAY_FINE)),
            context(gameState = gameState(phase = TurnPhase.AWAITING_JAIL_DECISION, activePlayerId = "p1"))
        )
        assertNull(result)
    }

    // ---- Gameplay: staleness ---------------------------------------------------

    @Test
    fun `a gameplay request stamped with a stale stateVersion is rejected`() {
        val result = validator().validate(
            envelope(senderId = "p1", payload = ClientMessage.EndTurnRequest, stateVersion = 3),
            context(
                gameState = gameState(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS),
                currentStateVersion = 10
            )
        )
        assertEquals(ErrorCode.STALE_STATE, result)
    }
}
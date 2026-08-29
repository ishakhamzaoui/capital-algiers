package com.menouer.protocol.message

import com.menouer.economy_data.Deck
import com.menouer.protocol.serialization.DeckSerializer
import com.menouer.protocol.serialization.JailActionSerializer
import com.menouer.rules_engine.JailAction
import com.menouer.rules_engine.model.AssetId
import com.menouer.rules_engine.model.PlayerId
import kotlinx.serialization.Serializable

/**
 * Every request a client can send to the host, per MultiplayerProtocol.md §6.
 * Carried as the `payload` of a [com.menouer.protocol.envelope.ClientEnvelope].
 *
 * Deliberately does NOT carry a dice payload on [RollDiceRequest]:
 * MultiplayerProtocol.md §1/§16 make the host the sole source of official
 * dice results (via rules-engine's `DiceSource`) — a client-supplied roll
 * would be an authority violation, not a convenience, so there is nothing
 * for the client to send beyond "it's my turn, roll now."
 *
 * Fields already carried by the envelope itself (§5) — protocolVersion,
 * gameId, senderId — are intentionally not repeated inside individual
 * payloads. This includes [ReconnectRequest]: §4 describes it as containing
 * "its match-local identity and protocol version", but the protocol version
 * is already the envelope's job, so the payload only adds the match-local
 * identity.
 */
@Serializable
sealed class ClientMessage {

    @Serializable
    data class JoinRequest(val displayName: String, val token: String) : ClientMessage()

    @Serializable
    data class ReadyChanged(val ready: Boolean) : ClientMessage()

    @Serializable
    data object RollDiceRequest : ClientMessage()

    @Serializable
    data class BuyAssetRequest(val assetId: AssetId) : ClientMessage()

    @Serializable
    data object DeclinePurchaseRequest : ClientMessage()

    @Serializable
    data class AuctionBidRequest(val amount: Int) : ClientMessage()

    @Serializable
    data object AuctionPassRequest : ClientMessage()

    @Serializable
    data class BuildRequest(val assetId: AssetId) : ClientMessage()

    @Serializable
    data class SellBuildingRequest(val assetId: AssetId) : ClientMessage()

    @Serializable
    data class MortgageRequest(val assetId: AssetId) : ClientMessage()

    @Serializable
    data class UnmortgageRequest(val assetId: AssetId) : ClientMessage()

    /**
     * Mirrors rules-engine's `TradeProposal` (GameRules.md §17) but is its own
     * wire type rather than reusing `TradeProposal` directly: `TradeProposal`
     * carries `fromPlayerId`, which on the wire is redundant with the
     * envelope's `senderId`, so this payload only adds `toPlayerId` plus both
     * sides' offered/requested terms. Session 3 (host/engine wiring) is
     * responsible for reconstructing a real `TradeProposal` from this plus
     * the envelope's `senderId`.
     */
    @Serializable
    data class TradeProposalRequest(
        val toPlayerId: PlayerId,
        val offeredCash: Int = 0,
        val offeredAssets: Set<AssetId> = emptySet(),
        val offeredGetOutOfJailCards: List<@Serializable(with = DeckSerializer::class) Deck> = emptyList(),
        val requestedCash: Int = 0,
        val requestedAssets: Set<AssetId> = emptySet(),
        val requestedGetOutOfJailCards: List<@Serializable(with = DeckSerializer::class) Deck> = emptyList()
    ) : ClientMessage()

    @Serializable
    data class TradeResponseRequest(val accept: Boolean) : ClientMessage()

    @Serializable
    data class JailActionRequest(
        @Serializable(with = JailActionSerializer::class) val action: JailAction
    ) : ClientMessage()

    @Serializable
    data object EndTurnRequest : ClientMessage()

    @Serializable
    data class ReconnectRequest(val matchLocalPlayerId: PlayerId) : ClientMessage()

    @Serializable
    data object SnapshotRequest : ClientMessage()

    @Serializable
    data class ClientAcknowledgement(val acknowledgedStateVersion: Long) : ClientMessage()
}
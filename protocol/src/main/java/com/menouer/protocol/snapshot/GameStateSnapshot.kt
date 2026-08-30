package com.menouer.protocol.snapshot

import com.menouer.economy_data.Deck
import com.menouer.protocol.serialization.DeckSerializer
import com.menouer.protocol.serialization.TurnPhaseSerializer
import com.menouer.protocol.validation.MatchStatus
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.TurnPhase
import kotlinx.serialization.Serializable

/**
 * A full synchronization snapshot, per MultiplayerProtocol.md §18's minimum
 * content list. Sent on join (wrapped in `HostMessage.JoinAccepted`), on a
 * completed reconnect, after a detected synchronization failure, or on an
 * explicit `SnapshotRequest` (§11) — always to the one requesting client,
 * never broadcast (see `HostMessage.Snapshot`'s own doc).
 *
 * Every rules-engine-owned type this touches (`GameState`, `PlayerState`,
 * `AssetState`, `AuctionState`, `TradeProposal`, `TurnPhase`) is fully
 * translated into a plain, protocol-owned shape below rather than embedded
 * directly — unlike `HostMessage.GameEventMessage`, which wraps `GameEvent`
 * as-is (Session 1). The difference is deliberate: a snapshot is one large,
 * infrequent payload that's exactly the kind of thing worth being properly
 * `@Serializable` end-to-end ahead of M5's real JSON wire transport,
 * whereas `GameEvent` is a large, frequently-extended sealed hierarchy
 * where hand-mirroring every variant would be an ongoing maintenance
 * burden for comparatively little payoff before M5 actually needs it.
 * `TurnPhase` and `Deck` are simple enums, so mirroring them is just one
 * small custom serializer each (`EnumSerializers.kt`) — cheap enough to do
 * now.
 *
 * [matchConfigId] answers TechnicalSpecification.md §9's "a mismatch [in
 * bundled economy config] should be caught the same way as a
 * protocolVersion mismatch": it's `BoardConfig.hashCode()` (a data class
 * hash over every economy/board value) rather than a hand-maintained
 * version string that could silently drift out of sync with the config it
 * describes.
 *
 * Every gameplay-only field ([players]' balance/position/etc.,
 * [activePlayerId], [phase], deck counts, pending auction/trade) is `null`
 * exactly when [matchStatus] is `LOBBY` — `GameState` itself doesn't exist
 * yet at that point (see `ValidationContext.gameState`'s own doc for why).
 *
 * [phase] alone stands in for §18's "any pending mandatory resolution" —
 * `AWAITING_PURCHASE_DECISION`/`AWAITING_JAIL_DECISION`/etc. already say
 * exactly what's pending; a separate flag would just restate the phase.
 *
 * Deck contents are reported as remaining counts ([chanceDeckRemaining]/
 * [chestDeckRemaining]), not the literal card order — §18 asks for deck
 * *state*, and a client has no legitimate use for knowing what's about to
 * be drawn next.
 */
@Serializable
data class GameStateSnapshot(
    val gameId: String,
    val matchStatus: MatchStatus,
    val matchConfigId: String,
    val stateVersion: Long,
    val matchCapacity: Int,
    val players: List<SnapshotPlayer>,
    val assets: List<SnapshotAsset>,
    val activePlayerId: PlayerId?,
    @Serializable(with = TurnPhaseSerializer::class) val phase: TurnPhase?,
    val bankHouses: Int?,
    val bankHotels: Int?,
    val chanceDeckRemaining: Int?,
    val chestDeckRemaining: Int?,
    val pendingAuction: SnapshotAuction?,
    val pendingTrade: SnapshotTrade?
)

@Serializable
data class SnapshotPlayer(
    val playerId: PlayerId,
    val displayName: String,
    val connected: Boolean,
    /** Meaningful only pre-start; retained (rather than dropped) post-start purely as a join-time record. */
    val ready: Boolean,
    val balance: Int?,
    val position: Int?,
    val inJail: Boolean?,
    val jailTurnsUsed: Int?,
    val getOutOfJailCards: List<@Serializable(with = DeckSerializer::class) Deck>,
    val bankrupt: Boolean?
)

@Serializable
data class SnapshotAsset(
    val assetId: String,
    val ownerId: PlayerId?,
    val mortgaged: Boolean,
    val houses: Int,
    val hasHotel: Boolean
)

/** Mirrors rules-engine's `AuctionState` in a fully serializable, protocol-owned shape. */
@Serializable
data class SnapshotAuction(
    val assetId: String,
    val highestBid: Int,
    val highestBidderId: PlayerId?,
    val eligibleBidders: Set<PlayerId>,
    val passedBidders: Set<PlayerId>
)

/** Mirrors rules-engine's `TradeProposal` in a fully serializable, protocol-owned shape. */
@Serializable
data class SnapshotTrade(
    val fromPlayerId: PlayerId,
    val toPlayerId: PlayerId,
    val offeredCash: Int,
    val offeredAssets: Set<String>,
    val offeredGetOutOfJailCards: List<@Serializable(with = DeckSerializer::class) Deck>,
    val requestedCash: Int,
    val requestedAssets: Set<String>,
    val requestedGetOutOfJailCards: List<@Serializable(with = DeckSerializer::class) Deck>
)
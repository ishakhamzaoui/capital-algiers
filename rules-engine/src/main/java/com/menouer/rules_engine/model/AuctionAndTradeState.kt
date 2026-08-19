package com.menouer.rules_engine.model

/**
 * State of an in-progress auction (GameRules.md §7). Minimal shape for now —
 * fleshed out fully in the Session 8 (auctions) pass.
 */
data class AuctionState(
    val assetId: AssetId,
    val highestBid: Int,
    val highestBidderId: PlayerId?,
    val eligibleBidders: Set<PlayerId>,
    val passedBidders: Set<PlayerId> = emptySet()
)

/**
 * One side's terms in a proposed trade (GameRules.md §17). Cash loans are
 * intentionally not representable here — the type system itself enforces
 * that constraint rather than relying only on a runtime check.
 */
data class TradeProposal(
    val fromPlayerId: PlayerId,
    val toPlayerId: PlayerId,
    val offeredCash: Int = 0,
    val offeredAssets: Set<AssetId> = emptySet(),
    val offeredGetOutOfJailCards: Int = 0,
    val requestedCash: Int = 0,
    val requestedAssets: Set<AssetId> = emptySet(),
    val requestedGetOutOfJailCards: Int = 0
)

/** State of an in-progress trade awaiting the counterparty's response. */
data class TradeState(
    val proposal: TradeProposal
)
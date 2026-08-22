package com.menouer.rules_engine.model

import com.menouer.economy_data.Deck

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
 * that constraint rather than relying only on a runtime check: every field
 * exchanges hands atomically and simultaneously, so there's no way to
 * express "cash now, repaid later."
 *
 * [offeredGetOutOfJailCards]/[requestedGetOutOfJailCards] name specific cards
 * by their origin [Deck] (matching PlayerState.getOutOfJailCards) rather than
 * a plain count — Cards.md §3 requires a card to return to its own deck
 * eventually, so trading needs to preserve which one is changing hands.
 */
data class TradeProposal(
    val fromPlayerId: PlayerId,
    val toPlayerId: PlayerId,
    val offeredCash: Int = 0,
    val offeredAssets: Set<AssetId> = emptySet(),
    val offeredGetOutOfJailCards: List<Deck> = emptyList(),
    val requestedCash: Int = 0,
    val requestedAssets: Set<AssetId> = emptySet(),
    val requestedGetOutOfJailCards: List<Deck> = emptyList()
)

/**
 * State of an in-progress trade awaiting the counterparty's response.
 * [previousPhase] is restored once the trade is accepted or declined, since
 * proposing a trade pauses whatever else was happening (GameState.phase
 * becomes IN_TRADE) rather than requiring per-player phase tracking.
 */
data class TradeState(
    val proposal: TradeProposal,
    val previousPhase: TurnPhase
)
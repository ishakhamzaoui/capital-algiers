package com.menouer.rules_engine.model

import com.menouer.economy_data.Deck

typealias PlayerId = String
typealias AssetId = String

/**
 * Runtime state of one player. Mutable game progress only — static config
 * (token choice, display name) lives outside the engine's concern.
 *
 * [jailTurnsUsed] counts failed doubles-attempts while jailed (0..2), per the
 * jail state machine in TechnicalSpecification.md §5 / GameRules.md §12
 * Addendum A2. It reaches 2 right before the forced turn-3 roll.
 *
 * [getOutOfJailCards] tags each retained card with the [Deck] it was drawn
 * from (not just a count): Cards.md §3 requires a used/traded card to return
 * to the bottom of its OWN deck specifically, and each deck holds exactly one
 * such card, so remembering the deck is enough to identify which physical
 * card it was.
 *
 * There is deliberately no `ownedAssets` field here: ownership lives solely on
 * [AssetState.ownerId], so there's exactly one place that can go stale. Use
 * GameState.assetsOwnedBy(playerId) to look up what a player owns.
 */
data class PlayerState(
    val id: PlayerId,
    val balance: Int,
    val position: Int,
    val inJail: Boolean = false,
    val jailTurnsUsed: Int = 0,
    val getOutOfJailCards: List<Deck> = emptyList(),
    val bankrupt: Boolean = false
)

/**
 * Runtime state of one purchasable asset (property, station, or utility).
 * Static economy data (price, rent table, group) lives in economy-data's
 * PropertyConfig/StationConfig/UtilityConfig, looked up by [id].
 */
data class AssetState(
    val id: AssetId,
    val ownerId: PlayerId? = null,
    val mortgaged: Boolean = false,
    val houses: Int = 0,
    val hasHotel: Boolean = false
)
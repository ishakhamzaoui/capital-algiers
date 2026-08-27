package com.menouer.rules_engine.model

import com.menouer.economy_data.BoardConfig
import com.menouer.rules_engine.dice.DiceRoll

/**
 * The complete authoritative game state, per TechnicalSpecification.md §3
 * and MultiplayerProtocol.md §18's minimum snapshot content.
 *
 * [config] is the static economy/board data (economy-data's BoardConfig) —
 * carried on GameState itself rather than injected separately into every
 * RulesEngine call, since every rule needs it and it never changes mid-match.
 *
 * [consecutiveDoublesCount] tracks doubles rolled so far *this turn*, for the
 * 3-consecutive-doubles-to-jail rule (GameRules.md §5). It resets to 0 at the
 * start of every turn.
 *
 * [pendingBonusRoll] is explicit rather than re-derived from lastRoll/isDouble
 * at endTurn time: a jail-doubles-release roll or a forced jail-turn-3 roll
 * can also be a double, but GameRules.md §12 is explicit that neither grants
 * a bonus roll the way an ordinary in-turn double does (§5). Every code path
 * that produces a roll sets this flag to exactly what it means, and endTurn
 * simply reads it — that way "was this double special" is decided once, at
 * the one place that knows the context, instead of being re-guessed later.
 *
 * [pendingAuction]/[pendingTrade] track an in-progress auction/trade the same
 * way for both causes that can start one: a normal declined purchase
 * (GameRules.md §7), and a bank-debt bankruptcy forfeiting assets that must
 * also be auctioned (§19's "Eligible assets are auctioned"). Which cause is
 * in effect is distinguished by [postBankruptcyAuctionQueue]: null means
 * "not part of a bankruptcy sequence" (the normal declined-purchase path,
 * which returns to AWAITING_OPTIONAL_ACTIONS once the auction concludes);
 * non-null (even if empty) means "resume by auctioning the next queued asset,
 * or once the queue is empty, advance to the next player" — since a bank-debt
 * bankruptcy always happens mid the debtor's OWN turn resolution, with no
 * optional-actions phase for them to return to.
 */
data class GameState(
    val stateVersion: Long = 0,
    val config: BoardConfig,
    val players: List<PlayerState>,
    val assets: Map<AssetId, AssetState>,
    val activePlayerId: PlayerId,
    val phase: TurnPhase,
    val bankHouses: Int,
    val bankHotels: Int,
    val chanceDeck: List<String>,
    val chestDeck: List<String>,
    val consecutiveDoublesCount: Int = 0,
    val lastRoll: DiceRoll? = null,
    val pendingBonusRoll: Boolean = false,
    val pendingAuction: AuctionState? = null,
    val pendingTrade: TradeState? = null,
    val postBankruptcyAuctionQueue: List<AssetId>? = null
) {
    val activePlayer: PlayerState
        get() = players.first { it.id == activePlayerId }

    fun player(id: PlayerId): PlayerState =
        players.first { it.id == id }

    fun playerOrNull(id: PlayerId): PlayerState? =
        players.firstOrNull { it.id == id }

    val nonBankruptPlayers: List<PlayerState>
        get() = players.filterNot { it.bankrupt }

    /** Every asset currently owned by [playerId] — the single source of truth for "what does X own". */
    fun assetsOwnedBy(playerId: PlayerId): List<AssetState> =
        assets.values.filter { it.ownerId == playerId }
}
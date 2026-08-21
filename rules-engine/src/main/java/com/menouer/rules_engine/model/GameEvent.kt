package com.menouer.rules_engine.model

import com.menouer.rules_engine.dice.DiceRoll

/**
 * Something that happened as a result of an applied RulesEngine call.
 * Deliberately mirrors MultiplayerProtocol.md §7's host-to-client event names
 * so :protocol can broadcast these near-verbatim in a later milestone,
 * without rules-engine knowing anything about networking.
 *
 * This is intentionally a starting set covering Session 0/1/2 needs (roll,
 * move, GO, turn change, jail entry, rent, purchase offers, card effects).
 * Later sessions add events for building, mortgages, trades, auctions, and
 * bankruptcy as those pieces of the engine are implemented.
 */
sealed class GameEvent {
    data class DiceRolled(val playerId: PlayerId, val roll: DiceRoll) : GameEvent()

    data class PlayerMoved(
        val playerId: PlayerId,
        val fromPosition: Int,
        val toPosition: Int,
        val passedGo: Boolean
    ) : GameEvent()

    data class GoCollected(val playerId: PlayerId, val amount: Int) : GameEvent()

    data class SentToJail(val playerId: PlayerId, val reason: JailReason) : GameEvent()

    /** Generic "the landed space has been resolved" marker, mirrors MultiplayerProtocol.md §7's LandingResolved. */
    data class LandingResolved(val playerId: PlayerId, val position: Int) : GameEvent()

    data class TaxPaid(val playerId: PlayerId, val amount: Int) : GameEvent()

    /** Rent charged for landing on another player's non-mortgaged property/station/utility (GameRules.md §8). */
    data class RentPaid(val payerId: PlayerId, val ownerId: PlayerId, val assetId: AssetId, val amount: Int) : GameEvent()

    /** Landed on an unowned purchasable asset; engine is now AWAITING_PURCHASE_DECISION. */
    data class PurchaseDecisionPending(val playerId: PlayerId, val assetId: AssetId) : GameEvent()

    data class CardDrawn(val playerId: PlayerId, val cardId: String, val deck: com.menouer.economy_data.Deck) : GameEvent()

    /** Cards.md CollectFromBank effect. */
    data class CardBankPayout(val playerId: PlayerId, val amount: Int) : GameEvent()

    /** Cards.md PayToBank / PropertyRepairs effects. */
    data class CardBankCharge(val playerId: PlayerId, val amount: Int) : GameEvent()

    /** Cards.md PayEachPlayer / CollectFromEachPlayer effects, emitted once per counterparty. */
    data class CardPlayerToPlayerPayment(val fromPlayerId: PlayerId, val toPlayerId: PlayerId, val amount: Int) : GameEvent()

    data class GetOutOfJailCardReceived(val playerId: PlayerId) : GameEvent()

    /** GameRules.md §12: a failed doubles-attempt on jail-turn 1 or 2 — no movement, turn ends. */
    data class JailRollFailed(val playerId: PlayerId, val jailTurnsUsedNow: Int) : GameEvent()

    /** Covers both a voluntary early payment and the automatic forced jail-turn-3 deduction. */
    data class JailFinePaid(val playerId: PlayerId, val amount: Int, val forced: Boolean) : GameEvent()

    data class GetOutOfJailCardUsed(val playerId: PlayerId, val deck: com.menouer.economy_data.Deck) : GameEvent()

    /** Emitted only for the two roll-based exits (doubles-attempt success, forced turn 3) — never for PAY_FINE/USE_CARD. */
    data class ReleasedFromJail(val playerId: PlayerId, val method: JailReleaseMethod) : GameEvent()

    /** Emitted when a double grants the same player a bonus roll instead of ending the turn (GameRules.md §5). */
    data class BonusRollGranted(val playerId: PlayerId) : GameEvent()

    data class TurnChanged(val newActivePlayerId: PlayerId) : GameEvent()

    data object GameEnded : GameEvent()
}

enum class JailReason {
    LANDED_ON_GO_TO_JAIL_SPACE,
    THREE_CONSECUTIVE_DOUBLES,
    CARD_EFFECT
}

enum class JailReleaseMethod {
    DOUBLES_ATTEMPT,
    FORCED_TURN_THREE
}
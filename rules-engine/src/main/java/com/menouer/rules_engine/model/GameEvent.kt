package com.menouer.rules_engine.model

import com.menouer.rules_engine.dice.DiceRoll

/**
 * Something that happened as a result of an applied RulesEngine call.
 * Deliberately mirrors MultiplayerProtocol.md §7's host-to-client event names
 * so :protocol can broadcast these near-verbatim in a later milestone,
 * without rules-engine knowing anything about networking.
 *
 * This is intentionally a starting set covering Session 0/1 needs (roll,
 * move, GO, turn change, jail entry). Later sessions add events for rent,
 * purchases, auctions, building, mortgages, trades, and bankruptcy as those
 * pieces of the engine are implemented.
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
package com.menouer.rules_engine.model

/**
 * Where the active player is within a turn, per GameRules.md §4 and
 * TechnicalSpecification.md §3. Drives which RulesEngine methods are legal
 * to call (mirrors the phase check in MultiplayerProtocol.md §8 step 6).
 */
enum class TurnPhase {
    AWAITING_ROLL,
    RESOLVING_LANDING,
    AWAITING_PURCHASE_DECISION,
    AWAITING_OPTIONAL_ACTIONS,
    IN_AUCTION,
    IN_TRADE,
    AWAITING_JAIL_DECISION,
    GAME_OVER
}
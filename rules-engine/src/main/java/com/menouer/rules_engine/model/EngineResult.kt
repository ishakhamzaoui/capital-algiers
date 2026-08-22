package com.menouer.rules_engine.model

/** The outcome of one RulesEngine call: either committed, or rejected with a reason. */
sealed class EngineResult {
    data class Applied(val newState: GameState, val events: List<GameEvent> = emptyList()) : EngineResult()
    data class Rejected(val reason: EngineError) : EngineResult()
}

/**
 * Why a request was rejected. Named to map cleanly onto
 * MultiplayerProtocol.md §15's error model when :protocol wraps these later
 * (e.g. INSUFFICIENT_FUNDS -> ErrorResponse.InsufficientFunds), without
 * rules-engine needing to know about the protocol layer.
 */
enum class EngineError {
    NOT_ACTIVE_PLAYER,
    WRONG_PHASE,
    PLAYER_NOT_FOUND,
    PLAYER_BANKRUPT,
    ASSET_NOT_FOUND,
    ASSET_ALREADY_OWNED,
    ASSET_NOT_OWNED_BY_PLAYER,
    ASSET_MORTGAGED,
    ASSET_NOT_MORTGAGED,
    MUST_SELL_BUILDINGS_FIRST,
    INSUFFICIENT_FUNDS,
    INVALID_BID,
    NOT_IN_AUCTION,
    NOT_ELIGIBLE_TO_BID,
    GROUP_NOT_COMPLETE,
    UNEVEN_BUILDING,
    MAX_BUILDINGS_REACHED,
    NO_BUILDING_TO_SELL,
    BUILDING_SUPPLY_EXHAUSTED,
    GROUP_HAS_MORTGAGED_PROPERTY,
    PLAYER_NOT_IN_JAIL,
    NO_GET_OUT_OF_JAIL_CARD,
    INVALID_TRADE,
    TRADE_ALREADY_PENDING,
    CASH_LOAN_NOT_ALLOWED,
    INVALID_REQUEST
}
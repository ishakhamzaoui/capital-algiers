package com.menouer.protocol.message

import kotlinx.serialization.Serializable

/**
 * Protocol-level error codes, per MultiplayerProtocol.md §15. Used both by
 * [HostMessage.ErrorResponse] (sent to a client) and internally by the
 * request-validation pipeline (Session 2) to report why a request was
 * rejected before it ever reached the rules engine.
 *
 * This is intentionally a separate enum from rules-engine's `EngineError`
 * (see rules_engine.model.EngineError): EngineError describes why a
 * *rules-engine call* was rejected (e.g. INSUFFICIENT_FUNDS,
 * GROUP_NOT_COMPLETE) and has entries with no protocol meaning at all;
 * ErrorCode describes why a *protocol request* was rejected, including
 * failures that never reach the engine (GameFull, ProtocolVersionMismatch,
 * StaleState). Session 2's validation pipeline is what maps the subset of
 * EngineErrors that can surface to a client onto these codes.
 */
@Serializable
enum class ErrorCode {
    INVALID_TURN,
    INVALID_PHASE,
    INSUFFICIENT_FUNDS,
    ASSET_UNAVAILABLE,
    INVALID_BID,
    STALE_STATE,
    DUPLICATE_REQUEST,
    GAME_FULL,
    GAME_STARTED,
    UNAUTHORIZED_PLAYER,
    PROTOCOL_VERSION_MISMATCH,
    INVALID_PAYLOAD,
    SYNCHRONIZATION_REQUIRED,
    MATCH_UNAVAILABLE
}
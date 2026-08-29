package com.menouer.protocol.error

import com.menouer.protocol.message.ErrorCode
import com.menouer.rules_engine.model.EngineError

/**
 * Maps rules-engine's `EngineError` onto the protocol's [ErrorCode]
 * (MultiplayerProtocol.md §15), per `EngineError`'s own doc comment: "Named
 * to map cleanly onto MultiplayerProtocol.md §15's error model when
 * :protocol wraps these later ... without rules-engine needing to know
 * about the protocol layer." This is exactly that wrapping, called by
 * `HostSession` whenever a `RulesEngine` call returns
 * `EngineResult.Rejected`.
 *
 * §15's list is intentionally coarser than `EngineError`'s ~25 values —
 * several distinct engine rejection reasons collapse onto one `ErrorCode`
 * where the client-facing distinction wouldn't change what the player needs
 * to do: every "you don't currently have standing to do this" case maps to
 * `UNAUTHORIZED_PLAYER`; every "the request doesn't describe something the
 * engine will currently allow, for reasons beyond a simple phase/turn
 * check" case maps to `INVALID_PAYLOAD`. Any detail beyond the `ErrorCode`
 * belongs in `HostMessage.ErrorResponse`'s optional `detail` string (e.g.
 * `error.name`, matching the M3 prototype's own `_lastRejection.value =
 * result.reason.name` pattern), not in a wider `ErrorCode` enum —
 * MultiplayerProtocol.md §15 is a finalized, closed list.
 */
object EngineErrorMapper {
    fun toErrorCode(error: EngineError): ErrorCode = when (error) {
        EngineError.NOT_ACTIVE_PLAYER -> ErrorCode.UNAUTHORIZED_PLAYER
        EngineError.WRONG_PHASE -> ErrorCode.INVALID_PHASE
        EngineError.PLAYER_NOT_FOUND -> ErrorCode.INVALID_PAYLOAD
        EngineError.PLAYER_BANKRUPT -> ErrorCode.UNAUTHORIZED_PLAYER
        EngineError.ASSET_NOT_FOUND -> ErrorCode.ASSET_UNAVAILABLE
        EngineError.ASSET_ALREADY_OWNED -> ErrorCode.ASSET_UNAVAILABLE
        EngineError.ASSET_NOT_OWNED_BY_PLAYER -> ErrorCode.UNAUTHORIZED_PLAYER
        EngineError.ASSET_MORTGAGED -> ErrorCode.ASSET_UNAVAILABLE
        EngineError.ASSET_NOT_MORTGAGED -> ErrorCode.ASSET_UNAVAILABLE
        EngineError.MUST_SELL_BUILDINGS_FIRST -> ErrorCode.ASSET_UNAVAILABLE
        EngineError.INSUFFICIENT_FUNDS -> ErrorCode.INSUFFICIENT_FUNDS
        EngineError.INVALID_BID -> ErrorCode.INVALID_BID
        EngineError.NOT_IN_AUCTION -> ErrorCode.INVALID_PHASE
        EngineError.NOT_ELIGIBLE_TO_BID -> ErrorCode.UNAUTHORIZED_PLAYER
        EngineError.GROUP_NOT_COMPLETE -> ErrorCode.INVALID_PAYLOAD
        EngineError.UNEVEN_BUILDING -> ErrorCode.INVALID_PAYLOAD
        EngineError.MAX_BUILDINGS_REACHED -> ErrorCode.ASSET_UNAVAILABLE
        EngineError.NO_BUILDING_TO_SELL -> ErrorCode.INVALID_PAYLOAD
        EngineError.BUILDING_SUPPLY_EXHAUSTED -> ErrorCode.ASSET_UNAVAILABLE
        EngineError.GROUP_HAS_MORTGAGED_PROPERTY -> ErrorCode.ASSET_UNAVAILABLE
        EngineError.PLAYER_NOT_IN_JAIL -> ErrorCode.INVALID_PAYLOAD
        EngineError.NO_GET_OUT_OF_JAIL_CARD -> ErrorCode.INVALID_PAYLOAD
        EngineError.INVALID_TRADE -> ErrorCode.INVALID_PAYLOAD
        EngineError.TRADE_ALREADY_PENDING -> ErrorCode.INVALID_PHASE
        EngineError.CASH_LOAN_NOT_ALLOWED -> ErrorCode.INVALID_PAYLOAD
        EngineError.INVALID_REQUEST -> ErrorCode.INVALID_PAYLOAD
    }
}
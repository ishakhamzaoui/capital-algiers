package com.menouer.protocol.message

import com.menouer.rules_engine.model.GameEvent
import com.menouer.rules_engine.model.PlayerId

/**
 * Every message the host can send to a client, per MultiplayerProtocol.md §7.
 *
 * Most of §7's granular event names — DiceRolled, PlayerMoved,
 * LandingResolved, AuctionStarted/Updated/Ended, TradeProposed/Resolved,
 * TurnChanged, GameEnded — already exist as rules-engine `GameEvent`
 * subtypes (see `GameEvent.kt`). Per §7's own closing line ("The final
 * implementation may consolidate event names while preserving the same
 * semantic contract"), this hierarchy wraps `GameEvent` directly via
 * [GameEventMessage] instead of re-declaring ~20 near-duplicate types that
 * would just drift out of sync with rules-engine's real event set as it
 * evolves.
 *
 * [JoinAccepted] and a first-class `GameStateSnapshot` type are deliberately
 * NOT included yet. Both need the full §18 snapshot shape (players,
 * positions, ownership, mortgages, deck state, active auction/trade, etc.),
 * which is Session 4's job — adding a throwaway placeholder now would just
 * mean redesigning it twice. `AuctionStarted`/`AuctionUpdated` for a
 * *rejoining* client and `JoinAccepted` both get wired up together once
 * that snapshot type exists.
 *
 * Not `@Serializable`: `GameEvent` and the enums it references are not
 * annotated for kotlinx.serialization, and editing `:rules-engine`/
 * `:economy-data` production code is off-limits for this module (see
 * `EnumSerializers.kt`'s doc for the same constraint on `ClientMessage`).
 * Real JSON-over-socket wire encoding for host events is deferred to M5
 * (Real LAN Networking) — the likely shape then is a serializable DTO
 * translation layer over `GameEvent`, not retrofitting annotations onto
 * rules-engine itself.
 */
sealed class HostMessage {

    data class JoinRejected(val reason: ErrorCode) : HostMessage()

    /** Wraps one rules-engine `GameEvent` for incremental broadcast (§11's "Event Update"). */
    data class GameEventMessage(val event: GameEvent) : HostMessage()

    /** Not itself a `GameEvent` — connection status is a protocol/transport concern, not a rules concept. */
    data class PlayerConnectionChanged(val playerId: PlayerId, val connected: Boolean) : HostMessage()

    data class ErrorResponse(
        val errorCode: ErrorCode,
        val inResponseToMessageId: String?,
        val detail: String? = null
    ) : HostMessage()
}
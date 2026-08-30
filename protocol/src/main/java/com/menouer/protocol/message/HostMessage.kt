package com.menouer.protocol.message

import com.menouer.protocol.snapshot.GameStateSnapshot
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
 * Not `@Serializable` as a whole: `GameEvent` and the enums it references
 * are not annotated for kotlinx.serialization, and editing `:rules-engine`/
 * `:economy-data` production code is off-limits for this module (see
 * `EnumSerializers.kt`'s doc for the same constraint on `ClientMessage`).
 * Real JSON-over-socket wire encoding for host events is deferred to M5
 * (Real LAN Networking) — the likely shape then is a serializable DTO
 * translation layer over `GameEvent`, not retrofitting annotations onto
 * rules-engine itself. [JoinAccepted] and [Snapshot] are the exception:
 * their payload, `GameStateSnapshot` (Session 4), IS fully `@Serializable`
 * already, since a snapshot is large/infrequent enough to be worth doing
 * properly now rather than deferring to M5 like `GameEvent` is.
 */
sealed class HostMessage {

    data class JoinRejected(val reason: ErrorCode) : HostMessage()

    /**
     * Sent only to the newly-joined client — never broadcast to the rest of
     * the lobby (they get [LobbyStateChanged] instead). Carries the
     * identity the host just assigned plus a full synchronization snapshot
     * (§18), per §3 steps 4-5 ("Host accepts... sends an authoritative
     * snapshot").
     */
    data class JoinAccepted(val assignedPlayerId: PlayerId, val snapshot: GameStateSnapshot) : HostMessage()

    /**
     * A full resynchronization snapshot (§11/§18) sent only to the one
     * requesting/reconnecting client — never broadcast. Used for both an
     * explicit `SnapshotRequest` and the snapshot half of the reconnect
     * flow (§4: sent before the host restores connected status, not after
     * — see `HostSession.handleReconnect`).
     */
    data class Snapshot(val snapshot: GameStateSnapshot) : HostMessage()

    /** Wraps one rules-engine `GameEvent` for incremental broadcast (§11's "Event Update"). */
    data class GameEventMessage(val event: GameEvent) : HostMessage()

    /** Not itself a `GameEvent` — connection status is a protocol/transport concern, not a rules concept. */
    data class PlayerConnectionChanged(val playerId: PlayerId, val connected: Boolean) : HostMessage()

    data class ErrorResponse(
        val errorCode: ErrorCode,
        val inResponseToMessageId: String?,
        val detail: String? = null
    ) : HostMessage()

    /**
     * Broadcast whenever lobby membership or readiness changes (a join, a
     * `ReadyChanged`, or a connection-status flip while still in the
     * lobby) — MultiplayerProtocol.md §3 step 7, "Host broadcasts lobby
     * changes." Added in Session 3 (HostSession) rather than Session 1,
     * since Session 1 deliberately deferred anything snapshot-shaped.
     *
     * Still used for lobby-only changes even now that [Snapshot] exists —
     * this is intentionally lighter-weight (SRS.md FR-003: names,
     * readiness, capacity) than a full snapshot, which has nothing
     * meaningful to add while still in the lobby anyway. Match-start itself
     * now broadcasts a real [Snapshot] instead of reusing this one more
     * time (see `HostSession.startMatch`), exactly as flagged when this
     * type was first added.
     */
    data class LobbyStateChanged(
        val players: List<LobbyPlayerView>,
        val matchCapacity: Int
    ) : HostMessage()

    data class LobbyPlayerView(
        val playerId: PlayerId,
        val displayName: String,
        val ready: Boolean,
        val connected: Boolean
    )
}
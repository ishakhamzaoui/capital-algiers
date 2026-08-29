package com.menouer.protocol.validation

import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerId

/**
 * Lobby vs. started vs. finished. This lives at the protocol layer, not
 * rules-engine: `GameState` only comes into existence once a match has
 * actually started (FR-004, "Start Match" — it requires an
 * `activePlayerId`, initialized positions, etc., none of which exist while
 * players are still joining a lobby). Pre-start lobby membership/readiness
 * is tracked by whatever owns this enum (Session 3's `HostSession`), not by
 * rules-engine.
 */
enum class MatchStatus { LOBBY, IN_PROGRESS, ENDED }

/**
 * Everything [RequestValidator] needs to know about the match to validate
 * one request, per MultiplayerProtocol.md §8 / TechnicalSpecification.md §6.
 * Built fresh (or read from live host state) per validation call rather
 * than the validator holding this itself — that keeps [RequestValidator] a
 * plain function of `(envelope, context) -> ErrorCode?`, so it's testable
 * without a real running host. Session 3's `HostSession` is expected to
 * assemble one of these from its own internal bookkeeping plus the current
 * `GameState` before calling [RequestValidator.validate].
 *
 * [gameState] is `null` exactly when [matchStatus] is [MatchStatus.LOBBY].
 *
 * [knownPlayerIds] covers every player who has successfully joined this
 * match at any point (including currently-disconnected ones) — this is
 * what "sender belongs to the match" (§8 step 1) checks against, as
 * distinct from [connectedPlayerIds] (§8 step 2, "sender connection state
 * allows action").
 */
data class ValidationContext(
    val gameId: String,
    val matchStatus: MatchStatus,
    val supportedProtocolVersion: Int,
    val matchCapacity: Int,
    val knownPlayerIds: Set<PlayerId>,
    val connectedPlayerIds: Set<PlayerId>,
    val currentStateVersion: Long,
    val gameState: GameState?
)
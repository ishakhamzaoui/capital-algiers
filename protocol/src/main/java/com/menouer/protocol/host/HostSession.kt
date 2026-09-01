package com.menouer.protocol.host

import com.menouer.economy_data.BoardConfig
import com.menouer.protocol.dedupe.RequestDeduplicator
import com.menouer.protocol.envelope.ClientEnvelope
import com.menouer.protocol.error.EngineErrorMapper
import com.menouer.protocol.message.ClientMessage
import com.menouer.protocol.message.ErrorCode
import com.menouer.protocol.message.HostMessage
import com.menouer.protocol.snapshot.GameStateSnapshot
import com.menouer.protocol.snapshot.SnapshotBuilder
import com.menouer.protocol.snapshot.SnapshotContext
import com.menouer.protocol.time.Clock
import com.menouer.protocol.time.SystemClock
import com.menouer.protocol.time.TimeoutConfig
import com.menouer.protocol.timeout.TimeoutAction
import com.menouer.protocol.timeout.TimeoutPolicy
import com.menouer.protocol.validation.MatchStatus
import com.menouer.protocol.validation.RequestValidator
import com.menouer.protocol.validation.ValidationContext
import com.menouer.protocol.version.StateVersionCounter
import com.menouer.rules_engine.RulesEngine
import com.menouer.rules_engine.dice.DiceSource
import com.menouer.rules_engine.model.AssetState
import com.menouer.rules_engine.model.EngineResult
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.PlayerState
import com.menouer.rules_engine.model.TradeProposal
import com.menouer.rules_engine.model.TurnPhase
import java.time.Instant

/**
 * One joined lobby member, before or after the match has started.
 * `displayName`/`ready` have no equivalent on rules-engine's `PlayerState` —
 * a display name and lobby readiness are protocol/lobby concerns, not
 * rules concepts — so this is the only place either is tracked, both
 * before and after the match starts (see `SnapshotBuilder`, which looks
 * this up by id to fill in a post-start snapshot's display names).
 */
data class JoinedPlayer(
    val id: PlayerId,
    val displayName: String,
    val token: String,
    val ready: Boolean = false
)

/** One committed change: the `stateVersion` it produced, and what to broadcast for it. */
data class HostBroadcast(val stateVersion: Long, val messages: List<HostMessage>)

/** The outcome of handling one client request or host-local action. */
sealed class DispatchResult {
    data class Rejected(val errorCode: ErrorCode) : DispatchResult()
    data class Applied(val broadcasts: List<HostBroadcast>) : DispatchResult()

    /**
     * `JoinRequest`'s success case. [assignedPlayerId] is what the
     * transport layer (Session 5) needs to address this connection as
     * going forward. [joinAccepted] is what should be sent back to that one
     * client only; [broadcasts] is the lobby roster update sent to
     * everyone else.
     */
    data class Joined(
        val assignedPlayerId: PlayerId,
        val broadcasts: List<HostBroadcast>,
        val joinAccepted: HostMessage.JoinAccepted
    ) : DispatchResult()

    /**
     * The result of `SnapshotRequest` or `ReconnectRequest`: a snapshot for
     * the requesting client only — never broadcast, and (deliberately)
     * carries no `stateVersion` bump of its own, since reading current
     * state doesn't change it (see `HostSession.handleReconnect`'s doc for
     * why this matters specifically for reconnection).
     */
    data class SnapshotSent(val message: HostMessage.Snapshot) : DispatchResult()
}

/**
 * Owns one match end-to-end: lobby membership, the authoritative
 * `GameState` once started, and every committed change to either. Sessions
 * 1-2 built the wire types and the validation pipeline; this is where they
 * get wired to `RulesEngine` (TechnicalSpecification.md §3/§7).
 *
 * Constructed with a real `RulesEngine`/`DiceSource`/`BoardConfig` — nothing
 * here knows or cares whether those are talking to a real game or a
 * `ScriptedDiceSource`-driven test. There is still no real transport: every
 * public method here is a plain function call, matching this milestone's
 * "in-memory transport double first" scope (Session 5 supplies the actual
 * double; this class doesn't know a transport exists at all).
 */
class HostSession(
    private val gameId: String,
    private val protocolVersion: Int,
    private val matchCapacity: Int,
    private val config: BoardConfig,
    private val engine: RulesEngine,
    private val diceSource: DiceSource,
    private val clock: Clock = SystemClock,
    private val timeoutConfig: TimeoutConfig = TimeoutConfig()
) {
    private val deduplicator = RequestDeduplicator()
    private val validator = RequestValidator(deduplicator)
    private val versionCounter = StateVersionCounter()

    /**
     * TechnicalSpecification.md §9: host and client must ship the same
     * economy config, caught the same way as a protocolVersion mismatch.
     * `BoardConfig` is a data class, so its structural `hashCode()` changes
     * whenever any economy/board value does — no hand-maintained version
     * string to forget to bump.
     */
    private val matchConfigId: String = config.hashCode().toString()

    private var matchStatus: MatchStatus = MatchStatus.LOBBY
    private val joinedPlayers = mutableListOf<JoinedPlayer>()
    private val connectedPlayerIds = mutableSetOf<PlayerId>()

    /**
     * Players who have sent `ReconnectRequest` and been sent a snapshot,
     * but haven't yet sent the `ClientAcknowledgement` that completes the
     * reconnect (MultiplayerProtocol.md §4/§8) — see [handleReconnect] and
     * [handleAcknowledgement].
     */
    private val pendingReconnects = mutableSetOf<PlayerId>()

    /** Deadline for the current phase's `normalActionTimeout` default, or null if the phase has none (see [TimeoutPolicy]). */
    private var pendingActionDeadline: Instant? = null

    /** Deadline for the current auction round's `auctionResponseTimeout`; separate from [pendingActionDeadline] since IN_AUCTION has its own dedicated timeout. */
    private var auctionDeadline: Instant? = null

    /** When each currently-disconnected player dropped, for `reconnectionGracePeriod` tracking (MultiplayerProtocol.md §13/§14). */
    private val disconnectedAt = mutableMapOf<PlayerId, Instant>()

    private var lastHeartbeatSentAt: Instant? = null

    private var gameState: GameState? = null

    // ---- Read-only views, for callers/tests --------------------------------

    fun currentMatchStatus(): MatchStatus = matchStatus
    fun currentStateVersion(): Long = versionCounter.current
    fun currentGameState(): GameState? = gameState
    fun joinedPlayerIds(): Set<PlayerId> = joinedPlayers.map { it.id }.toSet()
    fun currentGameId(): String = gameId
    fun currentProtocolVersion(): Int = protocolVersion

    // ---- Host-local actions (no corresponding ClientMessage) ---------------

    /**
     * Starts the match, per SRS.md FR-004: requires at least two joined
     * players. The lobby-capacity ceiling is already enforced by
     * `RequestValidator` refusing joins past [matchCapacity], so it isn't
     * re-checked here. This has no corresponding `ClientMessage` — only the
     * host device's own UI can trigger it (MultiplayerProtocol.md §3
     * describes "host start controls" in the lobby, not a client request).
     *
     * Mirrors the initial-`GameState` construction already established by
     * the M3 hotseat prototype (`GameSessionViewModel.startNewGame`) — same
     * shape, just sourced from joined network players instead of a
     * synchronously-provided name list.
     *
     * Broadcasts a full [HostMessage.Snapshot] once started — every already-
     * connected client needs the initial `GameState`, not just the "lobby
     * just closed" roster update `LobbyStateChanged` would give them. This
     * supersedes Session 3's placeholder, exactly as flagged there.
     */
    fun startMatch(): DispatchResult {
        if (matchStatus != MatchStatus.LOBBY) return DispatchResult.Rejected(ErrorCode.GAME_STARTED)
        if (joinedPlayers.size < 2) return DispatchResult.Rejected(ErrorCode.INVALID_PAYLOAD)

        val players = joinedPlayers.map {
            PlayerState(id = it.id, balance = config.constants.startingMoney, position = 0)
        }
        val assets: Map<String, AssetState> =
            (config.properties.map { it.id } + config.stations.map { it.id } + config.utilities.map { it.id })
                .associateWith { AssetState(id = it) }

        gameState = GameState(
            stateVersion = 0,
            config = config,
            players = players,
            assets = assets,
            activePlayerId = players.first().id,
            phase = TurnPhase.AWAITING_ROLL,
            bankHouses = config.constants.totalHouses,
            bankHotels = config.constants.totalHotels,
            chanceDeck = config.chanceDeck.map { it.id },
            chestDeck = config.chestDeck.map { it.id }
        )
        matchStatus = MatchStatus.IN_PROGRESS
        resetDeadlinesForCurrentPhase()

        val version = versionCounter.incrementAndGet()
        val broadcast = HostBroadcast(version, listOf(HostMessage.Snapshot(buildSnapshot())))
        return DispatchResult.Applied(listOf(broadcast))
    }

    /** For the transport layer (Session 5/7) to call when a connection drops. */
    fun markDisconnected(playerId: PlayerId): HostBroadcast {
        connectedPlayerIds -= playerId
        pendingReconnects -= playerId // defensive: clears a stale in-flight reconnect, if any
        disconnectedAt[playerId] = clock.now()
        val version = versionCounter.incrementAndGet()
        return HostBroadcast(version, listOf(HostMessage.PlayerConnectionChanged(playerId, connected = false)))
    }

    // ---- Network message handling -------------------------------------------

    fun handle(envelope: ClientEnvelope): DispatchResult {
        val context = buildValidationContext()
        validator.validate(envelope, context)?.let { return DispatchResult.Rejected(it) }

        return when (val message = envelope.payload) {
            is ClientMessage.JoinRequest -> handleJoin(message)
            is ClientMessage.ReadyChanged -> handleReadyChanged(envelope.senderId, message)
            is ClientMessage.ReconnectRequest -> handleReconnect(envelope.senderId)
            is ClientMessage.SnapshotRequest -> handleSnapshotRequest()
            is ClientMessage.ClientAcknowledgement -> handleAcknowledgement(envelope.senderId)
            else -> handleGameplay(envelope.senderId, message)
        }
    }

    private fun handleJoin(message: ClientMessage.JoinRequest): DispatchResult {
        val assignedId = "p${joinedPlayers.size}"
        joinedPlayers += JoinedPlayer(id = assignedId, displayName = message.displayName, token = message.token)
        connectedPlayerIds += assignedId

        val lobbyBroadcast = commitLobbyChange()
        // Read after commitLobbyChange() so the snapshot's own stateVersion
        // matches what everyone else just received.
        val joinAccepted = HostMessage.JoinAccepted(assignedId, buildSnapshot())
        return DispatchResult.Joined(assignedId, listOf(lobbyBroadcast), joinAccepted)
    }

    private fun handleReadyChanged(senderId: PlayerId, message: ClientMessage.ReadyChanged): DispatchResult {
        val index = joinedPlayers.indexOfFirst { it.id == senderId }
        // Defensive only: RequestValidator already guarantees senderId is a known player.
        if (index < 0) return DispatchResult.Rejected(ErrorCode.UNAUTHORIZED_PLAYER)
        joinedPlayers[index] = joinedPlayers[index].copy(ready = message.ready)
        return DispatchResult.Applied(listOf(commitLobbyChange()))
    }

    /**
     * Per MultiplayerProtocol.md §4/§8: the host sends a fresh snapshot on
     * reconnect and only "restores connected status" once the client's
     * `ClientAcknowledgement` confirms it applied that snapshot — see
     * [handleAcknowledgement]. Deliberately does NOT mark [senderId]
     * connected or broadcast anything here; both happen on acknowledgement.
     * No `stateVersion` bump either: sending a snapshot reports current
     * state, it doesn't change it.
     */
    private fun handleReconnect(senderId: PlayerId): DispatchResult {
        pendingReconnects += senderId
        return DispatchResult.SnapshotSent(HostMessage.Snapshot(buildSnapshot()))
    }

    /** A query, not a mutation — no `stateVersion` bump, no broadcast, per §11. */
    private fun handleSnapshotRequest(): DispatchResult =
        DispatchResult.SnapshotSent(HostMessage.Snapshot(buildSnapshot()))

    /**
     * Finalizes a reconnect if [senderId] has one pending (see
     * [handleReconnect]); otherwise this is just the routine post-snapshot
     * acknowledgement §3 step 6 describes for an ordinary join, which needs
     * no further action.
     */
    private fun handleAcknowledgement(senderId: PlayerId): DispatchResult {
        if (senderId !in pendingReconnects) return DispatchResult.Applied(emptyList())

        pendingReconnects -= senderId
        connectedPlayerIds += senderId
        disconnectedAt -= senderId
        val version = versionCounter.incrementAndGet()
        val broadcast = HostBroadcast(version, listOf(HostMessage.PlayerConnectionChanged(senderId, connected = true)))
        return DispatchResult.Applied(listOf(broadcast))
    }

    private fun handleGameplay(senderId: PlayerId, message: ClientMessage): DispatchResult {
        // RequestValidator already guarantees gameState != null for any message
        // that reaches this branch; the elvis is defensive, not expected to fire.
        val state = gameState ?: return DispatchResult.Rejected(ErrorCode.INVALID_PHASE)

        return when (val result = callEngine(state, senderId, message)) {
            is EngineResult.Rejected -> DispatchResult.Rejected(EngineErrorMapper.toErrorCode(result.reason))
            is EngineResult.Applied -> DispatchResult.Applied(commitEngineResultChain(result))
        }
    }

    /**
     * Maps one `ClientMessage` to its corresponding `RulesEngine` call.
     * `RollDiceRequest` is the one case that supplies its own input (the
     * host-generated dice) rather than just forwarding payload fields —
     * MultiplayerProtocol.md §1/§16: the host alone produces official rolls.
     */
    private fun callEngine(state: GameState, senderId: PlayerId, message: ClientMessage): EngineResult =
        when (message) {
            is ClientMessage.RollDiceRequest -> engine.applyRoll(state, senderId, diceSource.roll())
            is ClientMessage.BuyAssetRequest -> engine.buyAsset(state, senderId, message.assetId)
            is ClientMessage.DeclinePurchaseRequest -> engine.declinePurchase(state, senderId)
            is ClientMessage.AuctionBidRequest -> engine.placeBid(state, senderId, message.amount)
            is ClientMessage.AuctionPassRequest -> engine.passAuction(state, senderId)
            is ClientMessage.BuildRequest -> engine.build(state, senderId, message.assetId)
            is ClientMessage.SellBuildingRequest -> engine.sellBuilding(state, senderId, message.assetId)
            is ClientMessage.MortgageRequest -> engine.mortgage(state, senderId, message.assetId)
            is ClientMessage.UnmortgageRequest -> engine.unmortgage(state, senderId, message.assetId)
            is ClientMessage.TradeProposalRequest -> engine.proposeTrade(
                state,
                TradeProposal(
                    fromPlayerId = senderId,
                    toPlayerId = message.toPlayerId,
                    offeredCash = message.offeredCash,
                    offeredAssets = message.offeredAssets,
                    offeredGetOutOfJailCards = message.offeredGetOutOfJailCards,
                    requestedCash = message.requestedCash,
                    requestedAssets = message.requestedAssets,
                    requestedGetOutOfJailCards = message.requestedGetOutOfJailCards
                )
            )
            is ClientMessage.TradeResponseRequest -> engine.resolveTrade(state, message.accept)
            is ClientMessage.JailActionRequest -> engine.jailAction(state, senderId, message.action)
            is ClientMessage.EndTurnRequest -> engine.endTurn(state)
            else -> error("unreachable: $message is a lobby/resync message, handled before callEngine")
        }

    /**
     * Commits [first], then — matching the exact pattern already
     * established by the M3 prototype's `applyAndChain` — follows up with
     * exactly one `resolveLanding` call if the committed state is left in
     * `RESOLVING_LANDING`. Verified against the current rules-engine
     * source before writing this: every path that sets that phase
     * (`applyNormalRoll`, `releaseFromJailAndMove`) is always followed by a
     * `resolveLanding` call that fully resolves the landing internally —
     * including any card-chained movement (`resolveCardDraw`'s
     * `MoveToPosition`/`MoveRelative` branches recurse into `resolveSpaceAt`
     * themselves) — so one follow-up call is guaranteed sufficient under
     * the engine as it exists today.
     *
     * The bounded loop (not a plain `if`) is a deliberate safety net, not
     * evidence a loop is expected: if a future rules-engine change ever
     * left `resolveLanding` needing a second call, this fails loudly via
     * [MAX_AUTO_RESOLVE_ITERATIONS] instead of silently relying on an
     * invariant that's no longer true — or worse, hanging.
     *
     * Each individual commit is its own `stateVersion` bump and its own
     * broadcast entry (MultiplayerProtocol.md §12; TechnicalSpecification.md
     * §7's sequence diagram) — unlike the UI-facing prototype, which merges
     * them into one UI-state update since that distinction doesn't matter
     * to a single local screen but does matter to what gets versioned and
     * broadcast over the wire.
     */
    private fun commitEngineResultChain(first: EngineResult.Applied): List<HostBroadcast> {
        val broadcasts = mutableListOf<HostBroadcast>()
        broadcasts += commitEngineResult(first)

        var iterations = 0
        while (iterations < MAX_AUTO_RESOLVE_ITERATIONS) {
            val state = gameState ?: break
            if (state.phase != TurnPhase.RESOLVING_LANDING) break
            when (val landingResult = engine.resolveLanding(state)) {
                is EngineResult.Applied -> broadcasts += commitEngineResult(landingResult)
                is EngineResult.Rejected -> break // shouldn't happen; phase already guarantees resolveLanding accepts
            }
            iterations++
        }
        return broadcasts
    }

    private fun commitEngineResult(result: EngineResult.Applied): HostBroadcast {
        gameState = result.newState
        resetDeadlinesForCurrentPhase()
        val version = versionCounter.incrementAndGet()
        return HostBroadcast(version, result.events.map { HostMessage.GameEventMessage(it) })
    }

    /**
     * Recomputes [pendingActionDeadline]/[auctionDeadline] for whatever
     * phase [gameState] is now in. Called after every committed engine
     * result and once from [startMatch] — the only two places `gameState`'s
     * phase can change.
     */
    private fun resetDeadlinesForCurrentPhase() {
        val phase = gameState?.phase ?: return
        val now = clock.now()
        if (phase == TurnPhase.IN_AUCTION) {
            auctionDeadline = now.plusSeconds(timeoutConfig.auctionResponseTimeoutSeconds)
            pendingActionDeadline = null
        } else {
            auctionDeadline = null
            pendingActionDeadline = TimeoutPolicy.defaultActionFor(phase)
                ?.let { now.plusSeconds(timeoutConfig.normalActionTimeoutSeconds) }
        }
    }

    /**
     * Applies whichever timeout is currently due, if any. Nothing calls
     * this automatically — there's no coroutines/scheduler dependency in
     * this project (Session 6 uses an injectable [Clock] instead, mirroring
     * `DiceSource`'s own seedability), so a real driving loop is left to
     * whatever schedules it later (Session 7's transport wiring, or a real
     * timer in M5); tests call it directly after advancing a `FakeClock`.
     *
     * Checks in this order:
     * 1. Reconnection grace period — if the *active* player is disconnected
     *    and has been gone longer than `reconnectionGracePeriodSeconds`,
     *    this takes priority over the phase's normal timeout (see the
     *    LIMITATION note below) and returns immediately: one auto-action
     *    per call, re-checked on the next call.
     * 2. Auction round timeout — if in `IN_AUCTION` and the round deadline
     *    has passed, every still-eligible, not-yet-passed bidder is
     *    auto-passed.
     * 3. Normal per-phase timeout — the [TimeoutPolicy] default for
     *    whatever phase [gameState] is currently in.
     *
     * LIMITATION (flagged, not silently worked around): MultiplayerProtocol.md
     * §13 specifies a disconnected, grace-period-expired player is
     * "auto-passed through their turns (no dice rolled, no purchases
     * made)". `RulesEngine`'s current public API has no way to end a turn
     * without rolling — `applyRoll` is the only method that transitions
     * away from `AWAITING_ROLL`, and there's no dedicated "skip this
     * player's turn" call. Implementing the literal "no dice rolled"
     * behavior would need a new `RulesEngine` capability (e.g.
     * `skipTurn(state, playerId): EngineResult`) — real rules-engine
     * production code, out of scope to add unilaterally per this project's
     * working agreement (flag a real gap, don't silently patch around it).
     * Until that's decided, grace-period expiry reuses the exact same
     * [TimeoutPolicy] default as an ordinary `normalActionTimeout` —
     * still "no player choice exercised on their behalf", but not
     * literally dice-free at `AWAITING_ROLL`/`AWAITING_JAIL_DECISION`.
     */
    fun checkTimeouts(): List<HostBroadcast> {
        val state = gameState ?: return emptyList()
        val now = clock.now()
        val activePlayerId = state.activePlayerId

        val disconnectedSince = disconnectedAt[activePlayerId]
        if (disconnectedSince != null &&
            !now.isBefore(disconnectedSince.plusSeconds(timeoutConfig.reconnectionGracePeriodSeconds))
        ) {
            return applyPhaseDefault(activePlayerId)
        }

        if (state.phase == TurnPhase.IN_AUCTION) {
            val deadline = auctionDeadline ?: return emptyList()
            return if (!now.isBefore(deadline)) applyAuctionTimeout() else emptyList()
        }

        val deadline = pendingActionDeadline ?: return emptyList()
        return if (!now.isBefore(deadline)) applyPhaseDefault(activePlayerId) else emptyList()
    }

    private fun applyPhaseDefault(playerId: PlayerId): List<HostBroadcast> {
        val state = gameState ?: return emptyList()
        val action = TimeoutPolicy.defaultActionFor(state.phase) ?: return emptyList()
        val result = when (action) {
            TimeoutAction.AUTO_ROLL -> engine.applyRoll(state, playerId, diceSource.roll())
            TimeoutAction.AUTO_DECLINE_PURCHASE -> engine.declinePurchase(state, playerId)
            TimeoutAction.AUTO_END_TURN -> engine.endTurn(state)
            TimeoutAction.AUTO_DECLINE_TRADE -> engine.resolveTrade(state, accept = false)
        }
        return when (result) {
            is EngineResult.Applied -> commitEngineResultChain(result)
            is EngineResult.Rejected -> emptyList() // defensive: phase/actor already guaranteed valid by construction
        }
    }

    /**
     * §13: "the host treats the unresponsive bidder as having passed for
     * that round." This engine's auction model lets any eligible,
     * not-yet-passed bidder act in any order rather than tracking whose
     * turn it is to bid (ProjectStatus.md's own decision log: auction
     * turn-taking is a UI/host convention, not an engine rule) — so
     * "the unresponsive bidder" (singular) is adapted here to "every
     * bidder who hasn't acted this round", auto-passing each of them.
     * Re-checks `gameState.pendingAuction` between each pass: passing one
     * bidder can conclude the auction entirely (e.g. only one eligible
     * bidder remained), which must stop this loop rather than continuing
     * to pass ids from a now-stale bidder list.
     */
    private fun applyAuctionTimeout(): List<HostBroadcast> {
        val broadcasts = mutableListOf<HostBroadcast>()
        val initialAuction = gameState?.pendingAuction ?: return emptyList()
        val stillEligible = initialAuction.eligibleBidders - initialAuction.passedBidders

        for (bidderId in stillEligible) {
            val currentState = gameState ?: break
            if (currentState.pendingAuction == null) break
            when (val result = engine.passAuction(currentState, bidderId)) {
                is EngineResult.Applied -> broadcasts += commitEngineResultChain(result)
                is EngineResult.Rejected -> {} // already passed / no longer eligible by this point; skip
            }
        }
        return broadcasts
    }

    /**
     * Proof of liveness for a client's `HostLossDetector` (Session 6),
     * per MultiplayerProtocol.md §13's `hostLossHeartbeatIntervalSeconds`.
     * Returns null when a heartbeat isn't due yet; the transport layer is
     * expected to call this periodically (e.g. on its own tick) and only
     * deliver a broadcast when non-null.
     *
     * Doesn't bump `stateVersion` — a heartbeat isn't a state change, same
     * reasoning as a `Snapshot` query (Session 4).
     */
    fun maybeSendHeartbeat(): HostBroadcast? {
        val now = clock.now()
        val last = lastHeartbeatSentAt
        if (last != null && now.isBefore(last.plusSeconds(timeoutConfig.hostLossHeartbeatIntervalSeconds))) {
            return null
        }
        lastHeartbeatSentAt = now
        return HostBroadcast(versionCounter.current, listOf(HostMessage.Heartbeat))
    }

    private fun commitLobbyChange(): HostBroadcast {
        val version = versionCounter.incrementAndGet()
        val roster = joinedPlayers.map {
            HostMessage.LobbyPlayerView(
                playerId = it.id,
                displayName = it.displayName,
                ready = it.ready,
                connected = it.id in connectedPlayerIds
            )
        }
        return HostBroadcast(version, listOf(HostMessage.LobbyStateChanged(roster, matchCapacity)))
    }

    private fun buildValidationContext(): ValidationContext = ValidationContext(
        gameId = gameId,
        matchStatus = matchStatus,
        supportedProtocolVersion = protocolVersion,
        matchCapacity = matchCapacity,
        knownPlayerIds = joinedPlayers.map { it.id }.toSet(),
        connectedPlayerIds = connectedPlayerIds.toSet(),
        currentStateVersion = versionCounter.current,
        gameState = gameState
    )

    private fun buildSnapshot(): GameStateSnapshot = SnapshotBuilder.build(
        SnapshotContext(
            gameId = gameId,
            matchStatus = matchStatus,
            matchConfigId = matchConfigId,
            matchCapacity = matchCapacity,
            stateVersion = versionCounter.current,
            lobbyPlayers = joinedPlayers.toList(),
            connectedPlayerIds = connectedPlayerIds.toSet(),
            gameState = gameState
        )
    )

    private companion object {
        const val MAX_AUTO_RESOLVE_ITERATIONS = 10
    }
}
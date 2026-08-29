package com.menouer.protocol.host

import com.menouer.economy_data.BoardConfig
import com.menouer.protocol.dedupe.RequestDeduplicator
import com.menouer.protocol.envelope.ClientEnvelope
import com.menouer.protocol.error.EngineErrorMapper
import com.menouer.protocol.message.ClientMessage
import com.menouer.protocol.message.ErrorCode
import com.menouer.protocol.message.HostMessage
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

/** One joined lobby member, before or after the match has started. */
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
     * going forward. A real `JoinAccepted` reply carrying a full snapshot
     * is Session 4's job — [broadcasts] here only carries the lobby
     * roster update sent to everyone else.
     */
    data class Joined(val assignedPlayerId: PlayerId, val broadcasts: List<HostBroadcast>) : DispatchResult()
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
    private val diceSource: DiceSource
) {
    private val deduplicator = RequestDeduplicator()
    private val validator = RequestValidator(deduplicator)
    private val versionCounter = StateVersionCounter()

    private var matchStatus: MatchStatus = MatchStatus.LOBBY
    private val joinedPlayers = mutableListOf<JoinedPlayer>()
    private val connectedPlayerIds = mutableSetOf<PlayerId>()
    private var gameState: GameState? = null

    // ---- Read-only views, for callers/tests --------------------------------

    fun currentMatchStatus(): MatchStatus = matchStatus
    fun currentStateVersion(): Long = versionCounter.current
    fun currentGameState(): GameState? = gameState
    fun joinedPlayerIds(): Set<PlayerId> = joinedPlayers.map { it.id }.toSet()

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
     * Broadcasts via `LobbyStateChanged` one last time rather than a real
     * `GameStateSnapshot`/match-started message — Session 4 supersedes this
     * once a proper snapshot type exists.
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

        return DispatchResult.Applied(listOf(commitLobbyChange()))
    }

    /** For the transport layer (Session 5/7) to call when a connection drops. */
    fun markDisconnected(playerId: PlayerId): HostBroadcast {
        connectedPlayerIds -= playerId
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
            is ClientMessage.SnapshotRequest -> handleResyncPlaceholder()
            is ClientMessage.ClientAcknowledgement -> handleResyncPlaceholder()
            else -> handleGameplay(envelope.senderId, message)
        }
    }

    /** Validated but a no-op for now: real snapshot content is Session 4's job. */
    private fun handleResyncPlaceholder(): DispatchResult = DispatchResult.Applied(emptyList())

    private fun handleJoin(message: ClientMessage.JoinRequest): DispatchResult {
        val assignedId = "p${joinedPlayers.size}"
        joinedPlayers += JoinedPlayer(id = assignedId, displayName = message.displayName, token = message.token)
        connectedPlayerIds += assignedId
        return DispatchResult.Joined(assignedId, listOf(commitLobbyChange()))
    }

    private fun handleReadyChanged(senderId: PlayerId, message: ClientMessage.ReadyChanged): DispatchResult {
        val index = joinedPlayers.indexOfFirst { it.id == senderId }
        // Defensive only: RequestValidator already guarantees senderId is a known player.
        if (index < 0) return DispatchResult.Rejected(ErrorCode.UNAUTHORIZED_PLAYER)
        joinedPlayers[index] = joinedPlayers[index].copy(ready = message.ready)
        return DispatchResult.Applied(listOf(commitLobbyChange()))
    }

    private fun handleReconnect(senderId: PlayerId): DispatchResult {
        connectedPlayerIds += senderId
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
        val version = versionCounter.incrementAndGet()
        return HostBroadcast(version, result.events.map { HostMessage.GameEventMessage(it) })
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

    private companion object {
        const val MAX_AUTO_RESOLVE_ITERATIONS = 10
    }
}
package com.menouer.protocol.validation

import com.menouer.protocol.dedupe.RequestDeduplicator
import com.menouer.protocol.envelope.ClientEnvelope
import com.menouer.protocol.message.ClientMessage
import com.menouer.protocol.message.ErrorCode
import com.menouer.rules_engine.model.AssetId
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.TurnPhase

/**
 * The host-side request-validation pipeline, per MultiplayerProtocol.md §8
 * and TechnicalSpecification.md §6: every client request passes through
 * this, in order, before it is allowed to reach `RulesEngine` (Session 3's
 * job). A rejection at any step returns the corresponding [ErrorCode] (§15)
 * — never a silent drop.
 *
 * The spec's 9 ordered checks don't apply uniformly to every message type.
 * A handful of messages exist specifically to bootstrap or resynchronize a
 * client that couldn't possibly satisfy some of them yet:
 * - `JoinRequest` has no assigned `PlayerId` yet, so "sender belongs to the
 *   match" / connection-state / turn / phase / entity checks are all
 *   meaningless for it.
 * - `ReconnectRequest` is sent *because* the sender isn't currently marked
 *   connected — requiring it to already be connected would make
 *   reconnection impossible.
 * - `SnapshotRequest` / `ClientAcknowledgement` carry no turn/phase meaning
 *   and are exempt from the stateVersion-staleness check, since resyncing a
 *   client that's behind is the entire point of both messages.
 *
 * Rather than one generic 9-step checklist with a pile of per-type
 * exemption flags, [validate] dispatches by message category into a small
 * dedicated function per category. Every category function is documented
 * with exactly which of the 9 numbered checks it performs (and, implicitly,
 * which it skips and why) — nothing here reorders or skips a check that
 * legitimately applies.
 *
 * Holds no mutable state of its own beyond the injected [deduplicator]
 * (intentionally stateful and shared across calls — see
 * `RequestDeduplicator`'s own doc). Everything else needed to validate one
 * request comes in fresh via [ValidationContext], so this class is
 * unit-testable without a real running host.
 */
class RequestValidator(private val deduplicator: RequestDeduplicator) {

    fun validate(envelope: ClientEnvelope, context: ValidationContext): ErrorCode? {
        // Applies before any of the 9 numbered checks mean anything: a
        // request naming the wrong match can't be evaluated against this
        // context at all.
        if (envelope.gameId != context.gameId) return ErrorCode.INVALID_PAYLOAD

        return when (envelope.payload) {
            is ClientMessage.JoinRequest -> validateJoin(envelope, context)
            is ClientMessage.ReconnectRequest -> validateReconnect(envelope, context)
            is ClientMessage.SnapshotRequest,
            is ClientMessage.ClientAcknowledgement -> validateResyncOnly(envelope, context)
            is ClientMessage.ReadyChanged -> validateReadyChanged(envelope, context)
            else -> validateGameplay(envelope, context)
        }
    }

    /**
     * §8 steps performed: 3 (protocol version), 4 (duplicate messageId),
     * plus the two join-specific preconditions from §15's error list that
     * exist for exactly this message (`GameStarted`, `GameFull`).
     *
     * Steps 2 (connection state), 5-6 (turn/phase — no `GameState` exists
     * yet), 7-8 (entities/ownership), and 9 (stateVersion staleness — a
     * brand-new client has no prior state to be stale relative to) do not
     * apply to a message sent before the sender has an assigned identity.
     */
    private fun validateJoin(envelope: ClientEnvelope, context: ValidationContext): ErrorCode? {
        checkProtocolVersion(envelope, context)?.let { return it }
        checkDuplicate(envelope)?.let { return it }

        if (context.matchStatus != MatchStatus.LOBBY) return ErrorCode.GAME_STARTED
        if (context.knownPlayerIds.size >= context.matchCapacity) return ErrorCode.GAME_FULL
        return null
    }

    /**
     * §8 steps performed: 2 (connection state — deliberately checking
     * *membership*, not *currently connected*, since reconnecting is only
     * ever attempted by someone who isn't), 3, 4.
     *
     * Also rejects with `MatchUnavailable` if the match has already ended —
     * there's nothing left to reconnect to (MultiplayerProtocol.md §14).
     *
     * Steps 5-9 don't apply: this message exists to resynchronize, so it
     * can't be gated by the very state the sender is out of sync on.
     */
    private fun validateReconnect(envelope: ClientEnvelope, context: ValidationContext): ErrorCode? {
        if (envelope.senderId !in context.knownPlayerIds) return ErrorCode.UNAUTHORIZED_PLAYER
        if (context.matchStatus == MatchStatus.ENDED) return ErrorCode.MATCH_UNAVAILABLE

        checkProtocolVersion(envelope, context)?.let { return it }
        checkDuplicate(envelope)?.let { return it }
        return null
    }

    /**
     * Shared by `SnapshotRequest` and `ClientAcknowledgement`. §8 steps
     * performed: 1's membership half (2: sender must be a known match
     * participant), 3, 4.
     *
     * Deliberately does NOT also require the sender to be currently marked
     * connected. This was originally checked (`isKnownAndConnected`) until
     * Session 4's reconnect sequencing exposed it as a real bug:
     * MultiplayerProtocol.md §4/§8 has the host send a fresh snapshot on
     * `ReconnectRequest`, wait for the client's `ClientAcknowledgement`, and
     * only THEN "restore connected status" — meaning the very
     * `ClientAcknowledgement` that's supposed to complete a reconnect would
     * always arrive from a sender not yet marked connected. Requiring
     * connected-ness here made finishing a reconnect impossible. See
     * `HostSession.handleAcknowledgement`'s reconnect-ack bookkeeping.
     *
     * Steps 5-9 don't apply, same reasoning as [validateReconnect] —
     * these messages are how a client gets (or confirms it is) in sync, so
     * they can't themselves require being already in sync.
     */
    private fun validateResyncOnly(envelope: ClientEnvelope, context: ValidationContext): ErrorCode? {
        if (envelope.senderId !in context.knownPlayerIds) return ErrorCode.UNAUTHORIZED_PLAYER

        checkProtocolVersion(envelope, context)?.let { return it }
        checkDuplicate(envelope)?.let { return it }
        return null
    }

    /**
     * §8 steps performed: 2, 3, 4, 6 (phase — "phase" here means lobby vs.
     * started, since `ReadyChanged` predates `GameState`/`TurnPhase`
     * entirely), 9.
     *
     * Step 5 (turn ownership) doesn't apply — readiness isn't a turn
     * concept. Steps 7-8 (entities/ownership) don't apply — there's nothing
     * for this message to reference.
     */
    private fun validateReadyChanged(envelope: ClientEnvelope, context: ValidationContext): ErrorCode? {
        if (!isKnownAndConnected(envelope.senderId, context)) return ErrorCode.UNAUTHORIZED_PLAYER

        checkProtocolVersion(envelope, context)?.let { return it }
        checkDuplicate(envelope)?.let { return it }

        if (context.matchStatus != MatchStatus.LOBBY) return ErrorCode.INVALID_PHASE

        checkStaleness(envelope, context)?.let { return it }
        return null
    }

    /**
     * Every remaining message type (`RollDiceRequest`, `BuyAssetRequest`,
     * `DeclinePurchaseRequest`, `AuctionBidRequest`, `AuctionPassRequest`,
     * `BuildRequest`, `SellBuildingRequest`, `MortgageRequest`,
     * `UnmortgageRequest`, `TradeProposalRequest`, `TradeResponseRequest`,
     * `JailActionRequest`, `EndTurnRequest`) is a genuine in-match gameplay
     * action, and gets the full 9-step pipeline:
     * 1. (handled by [validate] itself — gameId)
     * 2. sender known + connected
     * 3. protocol version
     * 4. duplicate messageId
     * 5. turn ownership — who is allowed to send *this* message type right
     *    now (not always "the active player": auction bids/passes check
     *    eligibility against the pending auction, and trade responses check
     *    the pending trade's counterparty — see [isAuthorizedActor])
     * 6. phase — which `TurnPhase`(s) this message type is legal in
     * 7-8. referenced entities exist, with basic ownership/state sanity
     *    (deep business-rule enforcement, e.g. exact minimum-bid math or
     *    trade building-encumbrance rules, is deliberately left to
     *    `RulesEngine` itself — see [checkEntitiesAndOwnership]'s doc)
     * 9. stateVersion not stale
     *
     * A match that hasn't started (or has ended) has no `GameState` to
     * check any of this against, so that's checked first and reported as
     * `InvalidPhase`.
     */
    private fun validateGameplay(envelope: ClientEnvelope, context: ValidationContext): ErrorCode? {
        val state = context.gameState ?: return ErrorCode.INVALID_PHASE

        if (!isKnownAndConnected(envelope.senderId, context)) return ErrorCode.UNAUTHORIZED_PLAYER
        checkProtocolVersion(envelope, context)?.let { return it }
        checkDuplicate(envelope)?.let { return it }

        if (!isAuthorizedActor(envelope.payload, envelope.senderId, state)) {
            return ErrorCode.UNAUTHORIZED_PLAYER
        }
        if (state.phase !in allowedPhases(envelope.payload)) return ErrorCode.INVALID_PHASE

        checkEntitiesAndOwnership(envelope.payload, envelope.senderId, state)?.let { return it }
        checkStaleness(envelope, context)?.let { return it }
        return null
    }

    // ---- Shared step implementations -------------------------------------

    private fun checkProtocolVersion(envelope: ClientEnvelope, context: ValidationContext): ErrorCode? =
        if (envelope.protocolVersion != context.supportedProtocolVersion) {
            ErrorCode.PROTOCOL_VERSION_MISMATCH
        } else {
            null
        }

    private fun checkDuplicate(envelope: ClientEnvelope): ErrorCode? =
        if (deduplicator.isDuplicate(envelope.senderId, envelope.messageId)) {
            ErrorCode.DUPLICATE_REQUEST
        } else {
            null
        }

    /**
     * Equality, not "not less than": a correctly-behaving client always
     * sends its next request stamped with the exact stateVersion of the
     * last snapshot/event it applied. Anything else — behind *or* ahead of
     * the host's current version — means the client's view can't be
     * trusted for this request, so both cases resync the same way rather
     * than needing separate "behind" vs. "corrupt/ahead" handling.
     */
    private fun checkStaleness(envelope: ClientEnvelope, context: ValidationContext): ErrorCode? =
        if (envelope.stateVersion != context.currentStateVersion) ErrorCode.STALE_STATE else null

    private fun isKnownAndConnected(senderId: PlayerId, context: ValidationContext): Boolean =
        senderId in context.knownPlayerIds && senderId in context.connectedPlayerIds

    // ---- Gameplay-only: per-message-type policy ---------------------------

    private fun allowedPhases(message: ClientMessage): Set<TurnPhase> = when (message) {
        is ClientMessage.RollDiceRequest ->
            setOf(TurnPhase.AWAITING_ROLL, TurnPhase.AWAITING_JAIL_DECISION)

        is ClientMessage.BuyAssetRequest,
        is ClientMessage.DeclinePurchaseRequest ->
            setOf(TurnPhase.AWAITING_PURCHASE_DECISION)

        is ClientMessage.AuctionBidRequest,
        is ClientMessage.AuctionPassRequest ->
            setOf(TurnPhase.IN_AUCTION)

        is ClientMessage.BuildRequest,
        is ClientMessage.SellBuildingRequest,
        is ClientMessage.MortgageRequest,
        is ClientMessage.UnmortgageRequest,
        is ClientMessage.TradeProposalRequest,
        is ClientMessage.EndTurnRequest ->
            setOf(TurnPhase.AWAITING_OPTIONAL_ACTIONS)

        is ClientMessage.TradeResponseRequest ->
            setOf(TurnPhase.IN_TRADE)

        is ClientMessage.JailActionRequest ->
            setOf(TurnPhase.AWAITING_JAIL_DECISION)

        else -> emptySet()
    }

    /**
     * Who is allowed to send this message type right now. Most gameplay
     * actions require the sender to be the active player, but two don't:
     * - Auction bids/passes: any player in the pending auction's
     *   `eligibleBidders` who hasn't already passed — `GameRules.md` §7
     *   lets the *declining* active player and every other non-bankrupt
     *   player bid, not just whoever's turn it is.
     * - Trade responses: only the pending trade's `toPlayerId` —
     *   `RulesEngine.resolveTrade` itself takes no `playerId` parameter at
     *   all and assumes exactly this, so enforcing it here is required, not
     *   just good practice.
     */
    private fun isAuthorizedActor(message: ClientMessage, senderId: PlayerId, state: GameState): Boolean =
        when (message) {
            is ClientMessage.AuctionBidRequest,
            is ClientMessage.AuctionPassRequest -> {
                val auction = state.pendingAuction
                auction != null && senderId in auction.eligibleBidders && senderId !in auction.passedBidders
            }

            is ClientMessage.TradeResponseRequest ->
                state.pendingTrade?.proposal?.toPlayerId == senderId

            is ClientMessage.RollDiceRequest,
            is ClientMessage.BuyAssetRequest,
            is ClientMessage.DeclinePurchaseRequest,
            is ClientMessage.BuildRequest,
            is ClientMessage.SellBuildingRequest,
            is ClientMessage.MortgageRequest,
            is ClientMessage.UnmortgageRequest,
            is ClientMessage.TradeProposalRequest,
            is ClientMessage.JailActionRequest,
            is ClientMessage.EndTurnRequest ->
                senderId == state.activePlayerId

            else -> true
        }

    /**
     * §8 steps 7 ("referenced entities exist") and 8 ("funds/ownership
     * preconditions") combined per message type, since for every type here
     * they're checked together against the same looked-up entity.
     *
     * Deliberately shallow by design: this catches obviously-invalid
     * requests (an asset ID that doesn't exist, bidding on nothing, trying
     * to mortgage a property you don't own) before bothering the engine,
     * but does NOT re-implement `RulesEngine`'s actual business rules (exact
     * minimum-bid/increment math, building-encumbrance trade rules, jail
     * card possession, etc.) — those already have dedicated `EngineError`
     * values and stay exclusively in rules-engine, per this project's
     * separation-of-concerns rule. Session 3 maps any `EngineError` the
     * engine itself raises onto an `ErrorCode` for the client.
     */
    private fun checkEntitiesAndOwnership(
        message: ClientMessage,
        senderId: PlayerId,
        state: GameState
    ): ErrorCode? = when (message) {
        is ClientMessage.BuyAssetRequest ->
            assetUnavailableUnless(state, message.assetId) { it.ownerId == null }

        is ClientMessage.BuildRequest -> requireOwnedAsset(state, message.assetId, senderId)
        is ClientMessage.SellBuildingRequest -> requireOwnedAsset(state, message.assetId, senderId)
        is ClientMessage.MortgageRequest -> requireOwnedAsset(state, message.assetId, senderId)
        is ClientMessage.UnmortgageRequest -> requireOwnedAsset(state, message.assetId, senderId)

        is ClientMessage.AuctionBidRequest ->
            if (message.amount <= 0) ErrorCode.INVALID_BID else null

        is ClientMessage.TradeProposalRequest -> checkTradeProposalEntities(message, senderId, state)

        else -> null
    }

    private fun assetUnavailableUnless(
        state: GameState,
        assetId: AssetId,
        condition: (com.menouer.rules_engine.model.AssetState) -> Boolean
    ): ErrorCode? {
        val asset = state.assets[assetId] ?: return ErrorCode.ASSET_UNAVAILABLE
        return if (!condition(asset)) ErrorCode.ASSET_UNAVAILABLE else null
    }

    /** Asset must exist AND be owned by [senderId] — the shared shape behind build/sell/mortgage/unmortgage. */
    private fun requireOwnedAsset(state: GameState, assetId: AssetId, senderId: PlayerId): ErrorCode? {
        val asset = state.assets[assetId] ?: return ErrorCode.ASSET_UNAVAILABLE
        return if (asset.ownerId != senderId) ErrorCode.UNAUTHORIZED_PLAYER else null
    }

    private fun checkTradeProposalEntities(
        message: ClientMessage.TradeProposalRequest,
        senderId: PlayerId,
        state: GameState
    ): ErrorCode? {
        if (message.toPlayerId == senderId) return ErrorCode.INVALID_PAYLOAD
        val counterparty = state.playerOrNull(message.toPlayerId)
        if (counterparty == null || counterparty.bankrupt) return ErrorCode.INVALID_PAYLOAD

        val offeredOwnedBySender = message.offeredAssets.all { state.assets[it]?.ownerId == senderId }
        if (!offeredOwnedBySender) return ErrorCode.ASSET_UNAVAILABLE

        val requestedOwnedByCounterparty =
            message.requestedAssets.all { state.assets[it]?.ownerId == message.toPlayerId }
        if (!requestedOwnedByCounterparty) return ErrorCode.ASSET_UNAVAILABLE

        return null
    }
}
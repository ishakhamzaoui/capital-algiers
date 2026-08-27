package com.menouer.capitalalgiers.game

import androidx.lifecycle.ViewModel
import com.menouer.economy_data.BoardConfig
import com.menouer.economy_data.EconomyConfigLoader
import com.menouer.economy_data.EconomyConfigValidator
import com.menouer.rules_engine.RulesEngine
import com.menouer.rules_engine.RulesEngineImpl
import com.menouer.rules_engine.dice.SeededDiceSource
import com.menouer.rules_engine.model.AssetId
import com.menouer.rules_engine.model.AssetState
import com.menouer.rules_engine.model.EngineResult
import com.menouer.rules_engine.model.GameEvent
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.PlayerState
import com.menouer.rules_engine.model.TurnPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the hotseat prototype needs to know about the current match,
 * bundled together since RulesEngine's GameState deliberately has no concept
 * of a player's display name (PlayerAndAssetState.kt: "static config (token
 * choice, display name) lives outside the engine's concern").
 *
 * [recentEvents] holds every GameEvent produced by the most recent user
 * action, INCLUDING any events from an automatically-chained resolveLanding
 * call (see GameSessionViewModel.applyAndChain) — so a single "Roll" tap
 * shows dice -> move -> GO -> rent/tax/card -> landing-resolved as one
 * readable sequence, matching how a human would narrate a turn.
 */
data class GameSessionUiState(
    val gameState: GameState,
    val playerNames: Map<PlayerId, String>,
    val recentEvents: List<GameEvent> = emptyList()
)

/** A ready-to-render buy/decline offer, resolved from GameState + BoardConfig so the UI doesn't need to. */
data class PurchaseOffer(
    val assetId: AssetId,
    val displayName: String,
    val price: Int
)

/**
 * Owns the single authoritative [GameState] for the local hotseat prototype
 * (DevelopmentRoadmap.md M3). There is exactly one "host" here — this
 * ViewModel — since there's no networking involved; every RulesEngine call
 * is made directly, in-process, on whatever thread Compose calls it from
 * (RulesEngine methods are pure and fast, no I/O, so no dispatcher needed).
 *
 * Dice are supplied by this ViewModel's [DiceSource], never invented by the
 * UI layer, keeping with RulesEngine.applyRoll's contract that the caller
 * (here, the "host") produces the roll.
 */
class GameSessionViewModel : ViewModel() {

    private val engine: RulesEngine = RulesEngineImpl()
    private val diceSource = SeededDiceSource()

    private val _uiState = MutableStateFlow<GameSessionUiState?>(null)
    val uiState: StateFlow<GameSessionUiState?> = _uiState.asStateFlow()

    private val _lastRejection = MutableStateFlow<String?>(null)
    val lastRejection: StateFlow<String?> = _lastRejection.asStateFlow()

    /**
     * Loads and validates the real bundled economy config the same way
     * production code should — never a hand-rolled/sample config — per
     * SRS.md FR-008/FR-009. Thrown [EconomyConfigException] is deliberately
     * NOT caught here: a startup config problem is a build/asset defect, not
     * a recoverable in-game state, so it should surface loudly rather than
     * be silently swallowed into an empty board.
     */
    private val config: BoardConfig by lazy {
        EconomyConfigLoader.loadDefault().also { EconomyConfigValidator.validate(it, "economy-config.json") }
    }

    /** Exposed so the setup screen can show starting money, etc. before a game exists. */
    fun peekConfig(): BoardConfig = config

    /**
     * Starts a brand-new match with the given display names, per SRS.md §3
     * (Setup): all tokens start on GO, starting money from BoardEconomy.md,
     * this ViewModel acts as the Bank/host. Player ids are simply "p0".."pN"
     * — display names are a pure UI-layer concern, kept alongside GameState
     * in [GameSessionUiState] rather than inside it.
     */
    fun startNewGame(playerNames: List<String>) {
        require(playerNames.size in 2..4) { "Capital Algiers hotseat supports 2-4 players (SRS.md FR-004 requires >= 2)" }

        val cfg = config
        val ids = playerNames.indices.map { "p$it" }

        val players = ids.map { id ->
            PlayerState(id = id, balance = cfg.constants.startingMoney, position = 0)
        }

        val assets: Map<String, AssetState> =
            (cfg.properties.map { it.id } + cfg.stations.map { it.id } + cfg.utilities.map { it.id })
                .associateWith { AssetState(id = it) }

        val newState = GameState(
            stateVersion = 0,
            config = cfg,
            players = players,
            assets = assets,
            activePlayerId = players.first().id,
            phase = TurnPhase.AWAITING_ROLL,
            bankHouses = cfg.constants.totalHouses,
            bankHotels = cfg.constants.totalHotels,
            chanceDeck = cfg.chanceDeck.map { it.id },
            chestDeck = cfg.chestDeck.map { it.id }
        )

        _uiState.value = GameSessionUiState(
            gameState = newState,
            playerNames = ids.zip(playerNames).toMap()
        )
        _lastRejection.value = null
    }

    /** Ends the current match and returns to the setup screen. */
    fun exitToSetup() {
        _uiState.value = null
        _lastRejection.value = null
    }

    // --- Turn flow (GameRules.md §4) ---

    /**
     * Rolls for the active player. Valid in both AWAITING_ROLL (normal turn
     * start) and AWAITING_JAIL_DECISION (a jailed player attempting doubles,
     * or the forced turn-3 roll) — RulesEngine.applyRoll itself dispatches on
     * phase, so one button covers both. PAY_FINE / USE_GET_OUT_OF_JAIL_CARD
     * as alternatives to rolling arrive in the dedicated jail-actions session.
     */
    fun rollDice() {
        val current = currentState() ?: return
        if (current.phase != TurnPhase.AWAITING_ROLL && current.phase != TurnPhase.AWAITING_JAIL_DECISION) return
        applyAndChain(engine.applyRoll(current, current.activePlayerId, diceSource.roll()))
    }

    /**
     * Ends the active player's turn (GameRules.md §4 step 10). If a valid
     * double is still pending, the engine grants the SAME player a bonus
     * roll instead of advancing (§5) — reflected automatically since that's
     * just a new phase on the returned state, not a special case here.
     */
    fun endTurn() {
        val current = currentState() ?: return
        applyAndChain(engine.endTurn(current))
    }

    // --- Purchase decision (GameRules.md §7) ---

    /** Everything the UI needs to render the current buy/decline prompt, or null outside that phase. */
    fun pendingPurchaseOffer(): PurchaseOffer? {
        val current = currentState() ?: return null
        if (current.phase != TurnPhase.AWAITING_PURCHASE_DECISION) return null
        val assetId = current.config.spacesByIndex[current.activePlayer.position]?.assetId ?: return null
        val price = purchasePriceFor(current.config, assetId) ?: return null
        val displayName = current.config.spaces.firstOrNull { it.assetId == assetId }?.developerName ?: assetId
        return PurchaseOffer(assetId = assetId, displayName = displayName, price = price)
    }

    fun buyPendingAsset() {
        val current = currentState() ?: return
        val assetId = pendingPurchaseOffer()?.assetId ?: return
        applyAndChain(engine.buyAsset(current, current.activePlayerId, assetId))
    }

    fun declinePendingAsset() {
        val current = currentState() ?: return
        applyAndChain(engine.declinePurchase(current, current.activePlayerId))
    }

    private fun purchasePriceFor(config: BoardConfig, assetId: AssetId): Int? {
        config.propertiesById[assetId]?.let { return it.purchasePrice }
        config.stationsById[assetId]?.let { return it.purchasePrice }
        config.utilitiesById[assetId]?.let { return it.purchasePrice }
        return null
    }

    // --- Auctions (GameRules.md §7) ---
    // TEMPORARY for this session: real interactive bidding is Session 4's job.
    // This has every eligible bidder pass in turn, which the engine treats as a
    // completely legitimate outcome ("If nobody makes a valid bid, the asset
    // remains unowned") — it's not a shortcut around the rule, just a stand-in
    // for a bid/pass UI that doesn't exist yet.

    fun skipAuctionWithAllPasses() {
        var state = currentState() ?: return
        if (state.phase != TurnPhase.IN_AUCTION) return

        val events = mutableListOf<GameEvent>()
        while (state.phase == TurnPhase.IN_AUCTION) {
            val auction = state.pendingAuction ?: break
            val remaining = auction.eligibleBidders - auction.passedBidders - setOfNotNull(auction.highestBidderId)
            val nextBidder = remaining.firstOrNull() ?: break
            when (val result = engine.passAuction(state, nextBidder)) {
                is EngineResult.Applied -> {
                    events += result.events
                    state = result.newState
                }
                is EngineResult.Rejected -> {
                    _lastRejection.value = result.reason.name
                    break
                }
            }
        }
        commit(state, events)
    }

    // --- Internal plumbing ---

    private fun currentState(): GameState? = _uiState.value?.gameState

    /**
     * Applies one engine result, then — if the resulting phase is
     * RESOLVING_LANDING — immediately calls resolveLanding so the UI never
     * has to expose a separate "continue" step for something the rules
     * always resolve deterministically. Events from both calls are
     * concatenated so the UI can show the whole roll-to-rest sequence at
     * once. resolveLanding's own internal recursion (e.g. a card that forces
     * another move) is already handled inside that single engine call.
     */
    private fun applyAndChain(result: EngineResult) {
        when (result) {
            is EngineResult.Rejected -> _lastRejection.value = result.reason.name
            is EngineResult.Applied -> {
                val events = result.events.toMutableList()
                var state = result.newState
                if (state.phase == TurnPhase.RESOLVING_LANDING) {
                    when (val landingResult = engine.resolveLanding(state)) {
                        is EngineResult.Applied -> {
                            events += landingResult.events
                            state = landingResult.newState
                        }
                        is EngineResult.Rejected -> _lastRejection.value = landingResult.reason.name
                    }
                }
                commit(state, events)
            }
        }
    }

    private fun commit(newState: GameState, events: List<GameEvent>) {
        _uiState.value = _uiState.value?.copy(gameState = newState, recentEvents = events)
        _lastRejection.value = null
    }
}
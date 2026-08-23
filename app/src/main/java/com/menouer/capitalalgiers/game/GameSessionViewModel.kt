package com.menouer.capitalalgiers.game

import androidx.lifecycle.ViewModel
import com.menouer.economy_data.BoardConfig
import com.menouer.economy_data.EconomyConfigLoader
import com.menouer.economy_data.EconomyConfigValidator
import com.menouer.rules_engine.RulesEngine
import com.menouer.rules_engine.RulesEngineImpl
import com.menouer.rules_engine.dice.SeededDiceSource
import com.menouer.rules_engine.model.AssetState
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
 * [recentEvents] is a rolling log of GameEvents from the most recent engine
 * call, purely for a simple on-screen "what just happened" feed in later
 * sessions — Session 1 doesn't render it yet, but the plumbing starts here so
 * later sessions don't need to touch the ViewModel's core shape.
 */
data class GameSessionUiState(
    val gameState: GameState,
    val playerNames: Map<PlayerId, String>,
    val recentEvents: List<GameEvent> = emptyList()
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

    // Session 2 adds rollDice()/endTurn() etc. here, each following the same
    // shape: call the engine, and on Applied replace gameState + append
    // events; on Rejected, surface lastRejection without touching gameState.
    // Kept out of Session 1 deliberately since there's no roll UI yet to call it.
}
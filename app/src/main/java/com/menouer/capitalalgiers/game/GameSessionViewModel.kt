package com.menouer.capitalalgiers.game

import androidx.lifecycle.ViewModel
import com.menouer.economy_data.BoardConfig
import com.menouer.economy_data.Deck
import com.menouer.economy_data.EconomyConfigLoader
import com.menouer.economy_data.EconomyConfigValidator
import com.menouer.rules_engine.JailAction
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
import com.menouer.rules_engine.model.TradeProposal
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
 * What the active player can do about being in jail besides rolling for
 * doubles (GameRules.md §12): pay the fine voluntarily, or use a held Get
 * Out of Jail Free card. canAffordFine is a plain balance check the engine
 * itself re-verifies (payFineVoluntarily rejects with INSUFFICIENT_FUNDS if
 * wrong) — shown here only so the UI can grey out an obviously-doomed tap.
 */
data class JailOptions(
    val fineAmount: Int,
    val canAffordFine: Boolean,
    val heldGoojfDecks: List<Deck>
)

/**
 * Everything the UI needs to render the current auction step: whose turn it
 * is to act, the asset/current price, and the minimum a bid must clear.
 * "Whose turn" is a hotseat-UI convenience the engine itself doesn't
 * enforce (GameRules.md §7's AuctionState lets any eligible, non-passed
 * bidder act in any order) — cycling through seating order one at a time is
 * simply how a pass-and-play device hands control between players.
 */
data class AuctionOffer(
    val assetId: AssetId,
    val displayName: String,
    val currentBidderId: PlayerId,
    val currentBidderName: String,
    val highestBid: Int,
    val highestBidderName: String?,
    val minimumValidBid: Int
)

/**
 * One asset the active player owns, ready-to-render for the property
 * manager screen (GameRules.md §13-16, §18). mortgageValue/unmortgageCost
 * are always shown regardless of type, since stations/utilities can be
 * mortgaged too (§18) even though only properties (group != null) can
 * carry houses/hotels (§13).
 */
data class OwnedAssetSummary(
    val assetId: AssetId,
    val displayName: String,
    val group: String?,
    val houses: Int,
    val hasHotel: Boolean,
    val mortgaged: Boolean,
    val mortgageValue: Int,
    val unmortgageCost: Int,
    val houseCost: Int?
)

/** A player the active player could propose a trade to, or the other side of a pending trade. */
data class TradeParty(val playerId: PlayerId, val name: String)

/** One tradeable asset offered by whichever side owns it. */
data class TradeAssetOption(val assetId: AssetId, val displayName: String)

/**
 * Everything the trade-proposal screen needs: who you could trade with, and
 * — once a counterparty is chosen — what's available from each side.
 * Excludes any asset whose property GROUP currently carries a building
 * (GameRules.md §17: "Properties in a group containing buildings cannot be
 * transferred until required buildings in that group have been sold") and
 * excludes bankrupt players entirely — both are plain state reads, not rule
 * interpretation, so filtering them here follows the same "trivial
 * state-only filtering only" line the rest of this ViewModel draws.
 */
data class TradeBuilderContext(
    val fromPlayerId: PlayerId,
    val fromName: String,
    val fromBalance: Int,
    val fromTradeableAssets: List<TradeAssetOption>,
    val fromGoojfDecks: List<Deck>,
    val counterparties: List<TradeParty>
)

data class CounterpartyTradeContext(
    val toPlayerId: PlayerId,
    val toBalance: Int,
    val toTradeableAssets: List<TradeAssetOption>,
    val toGoojfDecks: List<Deck>
)

/** A pending trade awaiting the counterparty's accept/decline (GameRules.md §17). */
data class PendingTradeSummary(
    val fromName: String,
    val toName: String,
    val offeredCash: Int,
    val requestedCash: Int,
    val offeredAssetNames: List<String>,
    val requestedAssetNames: List<String>,
    val offeredGoojf: List<Deck>,
    val requestedGoojf: List<Deck>
)

/**
 * One player's final line on the match-results screen (GameRules.md §19-20,
 * SRS.md FR-013's "Match results" screen). isWinner is simply "the one
 * non-bankrupt player left" (GameState.nonBankruptPlayers, §20) — GAME_OVER
 * is only ever reached with exactly one such player in V1 (no draws, no
 * timer variant per DecisionLog.md #7).
 */
data class FinalStanding(
    val playerId: PlayerId,
    val name: String,
    val balance: Int,
    val bankrupt: Boolean,
    val isWinner: Boolean
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
     * phase, so one button covers both. See payJailFineVoluntarily() /
     * useGetOutOfJailCard() below for the other two jail exits.
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

    // --- Jail actions (GameRules.md §12) ---
    // Rolling for doubles is already covered by rollDice() above (applyRoll
    // dispatches to the jail-roll path itself when phase is
    // AWAITING_JAIL_DECISION). These two cover the other exits: paying the
    // fine voluntarily, or using a held Get Out of Jail Free card. Both
    // release the player but grant a normal roll THIS SAME TURN rather than
    // moving immediately — that's TechnicalSpecification.md §5's point about
    // keeping the voluntary-payment and forced-turn-3 paths distinct.

    /** Whether the active player is jailed and can act via jailAction (as opposed to only rolling). */
    fun activePlayerJailOptions(): JailOptions? {
        val current = currentState() ?: return null
        if (current.phase != TurnPhase.AWAITING_JAIL_DECISION) return null
        val player = current.activePlayer
        if (!player.inJail) return null
        return JailOptions(
            fineAmount = current.config.constants.jailFine,
            canAffordFine = player.balance >= current.config.constants.jailFine,
            heldGoojfDecks = player.getOutOfJailCards.distinct()
        )
    }

    fun payJailFineVoluntarily() = act { engine.jailAction(it, it.activePlayerId, JailAction.PAY_FINE) }

    fun useGetOutOfJailCard() = act { engine.jailAction(it, it.activePlayerId, JailAction.USE_GET_OUT_OF_JAIL_CARD) }

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
    // The engine itself doesn't sequence bidders (any eligible, non-passed
    // player may act at any time — that's a networked-lobby assumption from
    // MultiplayerProtocol.md). For a pass-and-play device we still need SOME
    // order to hand control to one player at a time, so pendingAuctionOffer()
    // cycles through seating order (GameState.players) among whoever hasn't
    // passed and isn't the current highest bidder.

    /** Everything the UI needs to render the current auction step, or null outside that phase. */
    fun pendingAuctionOffer(): AuctionOffer? {
        val current = currentState() ?: return null
        val auction = current.pendingAuction ?: return null
        val remaining = auction.eligibleBidders - auction.passedBidders - setOfNotNull(auction.highestBidderId)
        val bidderId = current.players.map { it.id }.firstOrNull { it in remaining } ?: return null

        val minimumValid = if (auction.highestBidderId == null) {
            current.config.constants.auctionMinimumBid
        } else {
            auction.highestBid + current.config.constants.auctionMinimumIncrement
        }

        return AuctionOffer(
            assetId = auction.assetId,
            displayName = current.config.spaces.firstOrNull { it.assetId == auction.assetId }?.developerName
                ?: auction.assetId,
            currentBidderId = bidderId,
            currentBidderName = displayNameFor(bidderId),
            highestBid = auction.highestBid,
            highestBidderName = auction.highestBidderId?.let { displayNameFor(it) },
            minimumValidBid = minimumValid
        )
    }

    /** Places [amount] on behalf of whichever player pendingAuctionOffer() says is currently up. */
    fun placeBid(amount: Int) {
        val current = currentState() ?: return
        val bidderId = pendingAuctionOffer()?.currentBidderId ?: return
        applyAndChain(engine.placeBid(current, bidderId, amount))
    }

    /** Passes on behalf of whichever player pendingAuctionOffer() says is currently up. */
    fun passCurrentBidder() {
        val current = currentState() ?: return
        val bidderId = pendingAuctionOffer()?.currentBidderId ?: return
        applyAndChain(engine.passAuction(current, bidderId))
    }

    private fun displayNameFor(playerId: PlayerId): String =
        _uiState.value?.playerNames?.get(playerId) ?: playerId

    // --- Building & mortgages (GameRules.md §13-16, §18) ---
    // No client-side pre-validation of even-building, group-completeness,
    // mortgage-blocks-building, etc. — same approach as the rest of this
    // ViewModel: the engine is the single source of truth for those rules,
    // so an invalid tap just comes back as a lastRejection message rather
    // than being silently disabled. The only client-side filtering here is
    // trivial, state-only stuff (don't show "Unmortgage" on a asset that
    // isn't mortgaged) that needs no rules knowledge to get right.

    /** Every asset the active player currently owns, for the property manager screen. */
    fun ownedAssetSummaries(): List<OwnedAssetSummary> {
        val current = currentState() ?: return emptyList()
        val playerId = current.activePlayerId
        return current.assets.values
            .filter { it.ownerId == playerId }
            .sortedBy { asset -> current.config.spaces.firstOrNull { it.assetId == asset.id }?.index ?: Int.MAX_VALUE }
            .map { asset ->
                val propertyConfig = current.config.propertiesById[asset.id]
                val mortgageValue = propertyConfig?.mortgageValue
                    ?: current.config.stationsById[asset.id]?.mortgageValue
                    ?: current.config.utilitiesById[asset.id]?.mortgageValue
                    ?: 0
                OwnedAssetSummary(
                    assetId = asset.id,
                    displayName = current.config.spaces.firstOrNull { it.assetId == asset.id }?.developerName ?: asset.id,
                    group = propertyConfig?.group?.name,
                    houses = asset.houses,
                    hasHotel = asset.hasHotel,
                    mortgaged = asset.mortgaged,
                    mortgageValue = mortgageValue,
                    unmortgageCost = mortgageValue + mortgageValue / 10,
                    houseCost = propertyConfig?.houseCost
                )
            }
    }

    fun buildOnAsset(assetId: AssetId) = act { engine.build(it, it.activePlayerId, assetId) }

    fun sellBuildingOnAsset(assetId: AssetId) = act { engine.sellBuilding(it, it.activePlayerId, assetId) }

    fun mortgageAsset(assetId: AssetId) = act { engine.mortgage(it, it.activePlayerId, assetId) }

    fun unmortgageAsset(assetId: AssetId) = act { engine.unmortgage(it, it.activePlayerId, assetId) }

    private fun act(call: (GameState) -> EngineResult) {
        val current = currentState() ?: return
        applyAndChain(call(current))
    }

    // --- Trading (GameRules.md §17) ---
    // RulesEngineImpl.proposeTrade allows a trade to be proposed in any
    // non-GAME_OVER phase (real trading isn't restricted to your own turn),
    // but this hotseat UI only surfaces "Propose trade" during the active
    // player's own AWAITING_OPTIONAL_ACTIONS window (BoardScreen's turn
    // panel) — a UX choice to keep the pass-and-play flow predictable, not
    // an engine restriction.

    /** Static context for building a new trade proposal: who's offering, who they could trade with. */
    fun tradeBuilderContext(): TradeBuilderContext? {
        val current = currentState() ?: return null
        val fromId = current.activePlayerId
        return TradeBuilderContext(
            fromPlayerId = fromId,
            fromName = displayNameFor(fromId),
            fromBalance = current.player(fromId).balance,
            fromTradeableAssets = tradeableAssetsOwnedBy(current, fromId),
            fromGoojfDecks = current.player(fromId).getOutOfJailCards,
            counterparties = current.nonBankruptPlayers
                .filter { it.id != fromId }
                .map { TradeParty(it.id, displayNameFor(it.id)) }
        )
    }

    /** What a chosen counterparty brings to the table, fetched once picked in the trade-proposal UI. */
    fun counterpartyTradeContext(toPlayerId: PlayerId): CounterpartyTradeContext? {
        val current = currentState() ?: return null
        val to = current.playerOrNull(toPlayerId) ?: return null
        return CounterpartyTradeContext(
            toPlayerId = toPlayerId,
            toBalance = to.balance,
            toTradeableAssets = tradeableAssetsOwnedBy(current, toPlayerId),
            toGoojfDecks = to.getOutOfJailCards
        )
    }

    private fun tradeableAssetsOwnedBy(state: GameState, playerId: PlayerId): List<TradeAssetOption> =
        state.assets.values
            .filter { it.ownerId == playerId }
            .filterNot { groupHasBuildings(state, it.id) }
            .sortedBy { asset -> state.config.spaces.firstOrNull { it.assetId == asset.id }?.index ?: Int.MAX_VALUE }
            .map { TradeAssetOption(it.id, state.config.spaces.firstOrNull { s -> s.assetId == it.id }?.developerName ?: it.id) }

    private fun groupHasBuildings(state: GameState, assetId: AssetId): Boolean {
        val propertyConfig = state.config.propertiesById[assetId] ?: return false
        return state.config.propertiesInGroup(propertyConfig.group).any {
            val asset = state.assets.getValue(it.id)
            asset.houses > 0 || asset.hasHotel
        }
    }

    /**
     * Proposes a trade from the active player to [toPlayerId]. Pauses the game
     * (phase -> IN_TRADE) until answered. Returns true iff the engine actually
     * applied it — false on rejection (e.g. an invalid combination caught by
     * validateTradeProposal), so the caller can decide whether to close the
     * proposal editor or keep it open to show the rejection inline, the same
     * way PropertyManagerDialog already does for build/mortgage actions.
     */
    fun proposeTrade(
        toPlayerId: PlayerId,
        offeredCash: Int,
        requestedCash: Int,
        offeredAssets: Set<AssetId>,
        requestedAssets: Set<AssetId>,
        offeredGoojf: List<Deck>,
        requestedGoojf: List<Deck>
    ): Boolean {
        val current = currentState() ?: return false
        val proposal = TradeProposal(
            fromPlayerId = current.activePlayerId,
            toPlayerId = toPlayerId,
            offeredCash = offeredCash,
            offeredAssets = offeredAssets,
            offeredGetOutOfJailCards = offeredGoojf,
            requestedCash = requestedCash,
            requestedAssets = requestedAssets,
            requestedGetOutOfJailCards = requestedGoojf
        )
        val result = engine.proposeTrade(current, proposal)
        applyAndChain(result)
        return result is EngineResult.Applied
    }

    /** Everything the response screen needs to show the pending trade, or null outside IN_TRADE. */
    fun pendingTradeSummary(): PendingTradeSummary? {
        val current = currentState() ?: return null
        val trade = current.pendingTrade?.proposal ?: return null
        fun assetName(id: AssetId) = current.config.spaces.firstOrNull { it.assetId == id }?.developerName ?: id
        return PendingTradeSummary(
            fromName = displayNameFor(trade.fromPlayerId),
            toName = displayNameFor(trade.toPlayerId),
            offeredCash = trade.offeredCash,
            requestedCash = trade.requestedCash,
            offeredAssetNames = trade.offeredAssets.map(::assetName),
            requestedAssetNames = trade.requestedAssets.map(::assetName),
            offeredGoojf = trade.offeredGetOutOfJailCards,
            requestedGoojf = trade.requestedGetOutOfJailCards
        )
    }

    fun respondToTrade(accept: Boolean) = act { engine.resolveTrade(it, accept) }

    // --- Match results (GameRules.md §19-20) ---

    /** Final standings for the match-results screen, or null before GAME_OVER. */
    fun finalStandings(): List<FinalStanding>? {
        val current = currentState() ?: return null
        if (current.phase != TurnPhase.GAME_OVER) return null
        val winnerId = current.nonBankruptPlayers.firstOrNull()?.id
        return current.players.map { p ->
            FinalStanding(
                playerId = p.id,
                name = displayNameFor(p.id),
                balance = p.balance,
                bankrupt = p.bankrupt,
                isWinner = p.id == winnerId
            )
        }
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
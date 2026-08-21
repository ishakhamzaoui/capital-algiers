package com.menouer.rules_engine

import com.menouer.rules_engine.dice.DiceRoll
import com.menouer.rules_engine.model.AssetId
import com.menouer.rules_engine.model.EngineResult
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.TradeProposal

/**
 * Pure, side-effect-free gameplay rules per GameRules.md, per
 * TechnicalSpecification.md §3. Every method is `(state, input) -> EngineResult`
 * with no internal mutable state and no I/O — this is what makes the engine
 * independently unit-testable and lets tests chain calls to script a full game.
 *
 * Method bodies are implemented incrementally across the M1 sessions:
 * Session 1 (applyRoll/resolveLanding/endTurn — turn flow, movement, GO),
 * Session 2 (rent folded into resolveLanding),
 * Session 3 (jailAction, plus jail's doubles-attempt/forced-turn-3 handling
 * folded into applyRoll — see its doc),
 * Session 4 (build/sellBuilding — houses, hotels, bank inventory limits, the
 * even-building constraint, and the mortgage-blocks-building rule),
 * Session 5 (mortgage/unmortgage),
 * Session 6 (proposeTrade/resolveTrade),
 * Session 7 (bankruptcy, cross-cutting),
 * Session 8 (buyAsset/declinePurchase/placeBid — auctions).
 */
interface RulesEngine {
    fun applyRoll(state: GameState, playerId: PlayerId, dice: DiceRoll): EngineResult
    fun resolveLanding(state: GameState): EngineResult

    fun buyAsset(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult
    fun declinePurchase(state: GameState, playerId: PlayerId): EngineResult
    fun placeBid(state: GameState, playerId: PlayerId, amount: Int): EngineResult

    fun build(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult
    fun sellBuilding(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult

    fun mortgage(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult
    fun unmortgage(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult

    fun proposeTrade(state: GameState, trade: TradeProposal): EngineResult
    fun resolveTrade(state: GameState, accept: Boolean): EngineResult

    fun jailAction(state: GameState, playerId: PlayerId, action: JailAction): EngineResult

    fun endTurn(state: GameState): EngineResult
}

/**
 * The two ways a jailed player may voluntarily leave before rolling, per
 * GameRules.md §12. "Attempting doubles" is deliberately NOT a third value
 * here — it's just a normal RollDiceRequest/applyRoll call made while the
 * player is in jail; applyRoll itself dispatches on GameState.phase
 * (AWAITING_JAIL_DECISION vs AWAITING_ROLL) rather than needing a second
 * dice-carrying entrypoint through this method.
 */
enum class JailAction {
    PAY_FINE,
    USE_GET_OUT_OF_JAIL_CARD
}
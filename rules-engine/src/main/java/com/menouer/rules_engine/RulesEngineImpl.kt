package com.menouer.rules_engine

import com.menouer.economy_data.SpaceType
import com.menouer.rules_engine.dice.DiceRoll
import com.menouer.rules_engine.model.AssetId
import com.menouer.rules_engine.model.EngineError
import com.menouer.rules_engine.model.EngineResult
import com.menouer.rules_engine.model.GameEvent
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.JailReason
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.PlayerState
import com.menouer.rules_engine.model.TradeProposal
import com.menouer.rules_engine.model.TurnPhase

/**
 * Session 1 implements: applyRoll, resolveLanding (only for the space types
 * that don't depend on rent/ownership — TAX, FREE_PARKING, the visiting side
 * of JAIL, and GO_TO_JAIL), and endTurn (including the doubles-bonus-roll
 * rule and basic win detection).
 *
 * PROPERTY / STATION / UTILITY / CHANCE / COMMUNITY_CHEST landing resolution
 * is completed in Session 2, since it depends on rent calculation. Auctions,
 * building, mortgages, trading, jail actions, and full bankruptcy handling
 * are completed in their respective later sessions (see RulesEngine.kt's
 * class doc). Calling those before their session lands throws NotImplementedError
 * on purpose, so a gap is loud rather than silently wrong.
 */
class RulesEngineImpl : RulesEngine {

    override fun applyRoll(state: GameState, playerId: PlayerId, dice: DiceRoll): EngineResult {
        if (state.phase != TurnPhase.AWAITING_ROLL) return EngineResult.Rejected(EngineError.WRONG_PHASE)
        if (playerId != state.activePlayerId) return EngineResult.Rejected(EngineError.NOT_ACTIVE_PLAYER)
        val player = state.playerOrNull(playerId) ?: return EngineResult.Rejected(EngineError.PLAYER_NOT_FOUND)
        if (player.bankrupt) return EngineResult.Rejected(EngineError.PLAYER_BANKRUPT)

        val events = mutableListOf<GameEvent>(GameEvent.DiceRolled(playerId, dice))

        val doublesCount = if (dice.isDouble) state.consecutiveDoublesCount + 1 else state.consecutiveDoublesCount

        if (dice.isDouble && doublesCount == 3) {
            // GameRules.md §5: on the 3rd consecutive double, the player does NOT make
            // the movement associated with that roll — they go directly to jail, the
            // turn ends immediately, and no GO payment occurs regardless of route.
            val jailSpace = state.config.spaces.first { it.type == SpaceType.JAIL }
            val jailedPlayer = player.copy(position = jailSpace.index, inJail = true, jailTurnsUsed = 0)
            events += GameEvent.SentToJail(playerId, JailReason.THREE_CONSECUTIVE_DOUBLES)

            val stateAfterJailing = state.copy(
                players = state.players.replace(jailedPlayer),
                lastRoll = dice,
                consecutiveDoublesCount = 0
            )
            val (finalState, turnEvents) = advanceToNextPlayer(stateAfterJailing)
            return EngineResult.Applied(finalState, events + turnEvents)
        }

        val (newPosition, passedGo) = movePosition(player.position, dice.total)
        var movedPlayer = player.copy(position = newPosition)
        events += GameEvent.PlayerMoved(playerId, player.position, newPosition, passedGo)

        if (passedGo) {
            movedPlayer = movedPlayer.copy(balance = movedPlayer.balance + state.config.constants.goReward)
            events += GameEvent.GoCollected(playerId, state.config.constants.goReward)
        }

        val newState = state.copy(
            players = state.players.replace(movedPlayer),
            phase = TurnPhase.RESOLVING_LANDING,
            lastRoll = dice,
            consecutiveDoublesCount = doublesCount
        )
        return EngineResult.Applied(newState, events)
    }

    override fun resolveLanding(state: GameState): EngineResult {
        if (state.phase != TurnPhase.RESOLVING_LANDING) return EngineResult.Rejected(EngineError.WRONG_PHASE)
        val player = state.activePlayer
        val space = state.config.spacesByIndex.getValue(player.position)
        val events = mutableListOf<GameEvent>()

        return when (space.type) {
            SpaceType.GO, SpaceType.FREE_PARKING, SpaceType.JAIL -> {
                // GO: nothing further beyond the payment already applied in applyRoll.
                // FREE_PARKING (§11): no jackpot, reward, or penalty in Version 1.
                // JAIL landed on normally is "زيارة فقط" (visiting only, §12): no penalty.
                events += GameEvent.LandingResolved(player.id, player.position)
                EngineResult.Applied(
                    state.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS),
                    events
                )
            }

            SpaceType.TAX -> {
                val amount = when (space.developerName) {
                    "IncomeTax" -> state.config.constants.incomeTax
                    "LuxuryTax" -> state.config.constants.luxuryTax
                    else -> error("unrecognized tax space: ${space.developerName}")
                }
                check(player.balance >= amount) {
                    "Session 7 will implement the bankruptcy path for a player who " +
                            "can't cover a mandatory tax payment (GameRules.md §19)."
                }
                val paidPlayer = player.copy(balance = player.balance - amount)
                events += GameEvent.TaxPaid(player.id, amount)
                events += GameEvent.LandingResolved(player.id, player.position)
                EngineResult.Applied(
                    state.copy(
                        players = state.players.replace(paidPlayer),
                        phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS
                    ),
                    events
                )
            }

            SpaceType.GO_TO_JAIL -> {
                // §6/§12: landing here sends the player directly to jail; no GO payment
                // even though the conceptual route crosses index 0, and the turn ends
                // immediately (no optional actions phase).
                val jailSpace = state.config.spaces.first { it.type == SpaceType.JAIL }
                val jailedPlayer = player.copy(position = jailSpace.index, inJail = true, jailTurnsUsed = 0)
                events += GameEvent.SentToJail(player.id, JailReason.LANDED_ON_GO_TO_JAIL_SPACE)
                val stateWithJailedPlayer = state.copy(players = state.players.replace(jailedPlayer))
                val (finalState, turnEvents) = advanceToNextPlayer(stateWithJailedPlayer)
                EngineResult.Applied(finalState, events + turnEvents)
            }

            SpaceType.PROPERTY, SpaceType.STATION, SpaceType.UTILITY ->
                TODO("Session 2: purchase offer / rent resolution for ${space.developerName}")

            SpaceType.CHANCE, SpaceType.COMMUNITY_CHEST ->
                TODO("Session 2: card draw and effect resolution for ${space.developerName}")
        }
    }

    override fun endTurn(state: GameState): EngineResult {
        if (state.phase != TurnPhase.AWAITING_OPTIONAL_ACTIONS) return EngineResult.Rejected(EngineError.WRONG_PHASE)

        val lastRoll = state.lastRoll
        val grantsBonusRoll = lastRoll != null && lastRoll.isDouble && state.consecutiveDoublesCount < 3
        if (grantsBonusRoll) {
            // GameRules.md §4 step 9 / §5: a valid double grants another roll to the
            // SAME player once the landed space is fully resolved. This does not
            // advance activePlayerId and does not reset consecutiveDoublesCount, since
            // that counter must keep tracking doubles across the whole turn.
            return EngineResult.Applied(
                state.copy(phase = TurnPhase.AWAITING_ROLL),
                listOf(GameEvent.BonusRollGranted(state.activePlayerId))
            )
        }

        val (finalState, events) = advanceToNextPlayer(state)
        return EngineResult.Applied(finalState, events)
    }

    // --- Stubs for later sessions ---

    override fun buyAsset(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult =
        TODO("Session 8: auctions/purchase")

    override fun declinePurchase(state: GameState, playerId: PlayerId): EngineResult =
        TODO("Session 8: auctions/purchase")

    override fun placeBid(state: GameState, playerId: PlayerId, amount: Int): EngineResult =
        TODO("Session 8: auctions/purchase")

    override fun build(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult =
        TODO("Session 4: building")

    override fun sellBuilding(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult =
        TODO("Session 4: building")

    override fun mortgage(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult =
        TODO("Session 5: mortgages")

    override fun unmortgage(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult =
        TODO("Session 5: mortgages")

    override fun proposeTrade(state: GameState, trade: TradeProposal): EngineResult =
        TODO("Session 6: trading")

    override fun resolveTrade(state: GameState, accept: Boolean): EngineResult =
        TODO("Session 6: trading")

    override fun jailAction(state: GameState, playerId: PlayerId, action: JailAction): EngineResult =
        TODO("Session 3: jail")

    // --- Internal helpers ---

    /**
     * Computes the destination of a forward move of [spaces] from [position] on the
     * 40-space board, and whether GO was passed or landed on. No "already collected
     * GO this turn" state is tracked or checked anywhere in the engine — this is
     * deliberate: GameRules.md §6 confirms multiple qualifying movement effects in
     * the same turn (e.g. a card-forced move plus normal movement) may each
     * legitimately pay the GO reward.
     */
    internal fun movePosition(position: Int, spaces: Int): Pair<Int, Boolean> {
        require(spaces > 0) { "movePosition is for forward movement only; use a dedicated path for backward MoveRelative cards" }
        val raw = position + spaces
        val passedGo = raw >= BOARD_SIZE
        val newPosition = raw % BOARD_SIZE
        return newPosition to passedGo
    }

    /**
     * Forcibly ends the current turn without going through AWAITING_OPTIONAL_ACTIONS —
     * used when a rule ends the turn immediately (3-consecutive-doubles or landing on
     * GO_TO_JAIL, both per GameRules.md), as opposed to the player choosing to end it.
     * Advances to the next non-bankrupt player, resets per-turn doubles tracking, and
     * checks for a win per GameRules.md §20.
     */
    internal fun advanceToNextPlayer(state: GameState): Pair<GameState, List<GameEvent>> {
        val nonBankrupt = state.nonBankruptPlayers
        if (nonBankrupt.size <= 1) {
            val winnerState = state.copy(phase = TurnPhase.GAME_OVER, lastRoll = null, consecutiveDoublesCount = 0)
            return winnerState to listOf(GameEvent.GameEnded)
        }

        val currentIndex = nonBankrupt.indexOfFirst { it.id == state.activePlayerId }
        // If the active player just went bankrupt as part of this same transition in a
        // future session, currentIndex could be -1; fall back to wrapping from the start.
        val nextPlayer = if (currentIndex == -1) {
            nonBankrupt.first()
        } else {
            nonBankrupt[(currentIndex + 1) % nonBankrupt.size]
        }

        val nextPhase = if (nextPlayer.inJail) TurnPhase.AWAITING_JAIL_DECISION else TurnPhase.AWAITING_ROLL

        val newState = state.copy(
            activePlayerId = nextPlayer.id,
            phase = nextPhase,
            lastRoll = null,
            consecutiveDoublesCount = 0
        )
        return newState to listOf(GameEvent.TurnChanged(nextPlayer.id))
    }

    private companion object {
        const val BOARD_SIZE = 40
    }
}

/** Returns a copy of the list with the element matching [updated]'s id replaced. */
internal fun List<PlayerState>.replace(updated: PlayerState): List<PlayerState> =
    map { if (it.id == updated.id) updated else it }
package com.menouer.rules_engine

import com.menouer.economy_data.CardEffect
import com.menouer.economy_data.Deck
import com.menouer.economy_data.SpaceType
import com.menouer.rules_engine.dice.DiceRoll
import com.menouer.rules_engine.model.AssetId
import com.menouer.rules_engine.model.AssetState
import com.menouer.rules_engine.model.EngineError
import com.menouer.rules_engine.model.EngineResult
import com.menouer.rules_engine.model.GameEvent
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.JailReason
import com.menouer.rules_engine.model.JailReleaseMethod
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.PlayerState
import com.menouer.rules_engine.model.TradeProposal
import com.menouer.rules_engine.model.TurnPhase

/**
 * Session 1 implemented: applyRoll (normal path), endTurn, and the parts of
 * landing resolution that don't depend on rent/ownership.
 *
 * Session 2 added: PROPERTY/STATION/UTILITY landing (purchase offer or rent),
 * and full Chance/Community Chest card resolution, recursively resolving
 * wherever a card-forced move lands.
 *
 * Session 3 (this pass) adds jail: jailAction for the two voluntary exits
 * (PAY_FINE, USE_GET_OUT_OF_JAIL_CARD), and — folded into applyRoll rather
 * than jailAction, see RulesEngine.kt's JailAction doc — the doubles-attempt
 * roll (jail-turns 1/2) and the forced jail-turn-3 roll (Addendum A2). A
 * roll-based jail exit never grants a bonus roll even if it's a double
 * (GameRules.md §12); this is expressed via GameState.pendingBonusRoll,
 * which every roll-producing path sets explicitly rather than endTurn
 * re-deriving "was that double special" from lastRoll alone.
 *
 * Building, mortgages, trading, auctions/purchase decisions, and full
 * bankruptcy remain TODO stubs for their own sessions (see RulesEngine.kt's
 * class doc).
 */
class RulesEngineImpl : RulesEngine {

    override fun applyRoll(state: GameState, playerId: PlayerId, dice: DiceRoll): EngineResult {
        if (playerId != state.activePlayerId) return EngineResult.Rejected(EngineError.NOT_ACTIVE_PLAYER)
        val player = state.playerOrNull(playerId) ?: return EngineResult.Rejected(EngineError.PLAYER_NOT_FOUND)
        if (player.bankrupt) return EngineResult.Rejected(EngineError.PLAYER_BANKRUPT)

        return when (state.phase) {
            TurnPhase.AWAITING_ROLL -> applyNormalRoll(state, player, dice)
            TurnPhase.AWAITING_JAIL_DECISION -> applyJailRoll(state, player, dice)
            else -> EngineResult.Rejected(EngineError.WRONG_PHASE)
        }
    }

    override fun resolveLanding(state: GameState): EngineResult {
        if (state.phase != TurnPhase.RESOLVING_LANDING) return EngineResult.Rejected(EngineError.WRONG_PHASE)
        return resolveSpaceAt(state)
    }

    override fun endTurn(state: GameState): EngineResult {
        if (state.phase != TurnPhase.AWAITING_OPTIONAL_ACTIONS) return EngineResult.Rejected(EngineError.WRONG_PHASE)

        if (state.pendingBonusRoll) {
            // GameRules.md §4 step 9 / §5: a valid double grants another roll to the
            // SAME player once the landed space is fully resolved.
            return EngineResult.Applied(
                state.copy(phase = TurnPhase.AWAITING_ROLL, pendingBonusRoll = false),
                listOf(GameEvent.BonusRollGranted(state.activePlayerId))
            )
        }

        val (finalState, events) = advanceToNextPlayer(state)
        return EngineResult.Applied(finalState, events)
    }

    override fun jailAction(state: GameState, playerId: PlayerId, action: JailAction): EngineResult {
        if (state.phase != TurnPhase.AWAITING_JAIL_DECISION) return EngineResult.Rejected(EngineError.WRONG_PHASE)
        if (playerId != state.activePlayerId) return EngineResult.Rejected(EngineError.NOT_ACTIVE_PLAYER)
        val player = state.playerOrNull(playerId) ?: return EngineResult.Rejected(EngineError.PLAYER_NOT_FOUND)
        if (!player.inJail) return EngineResult.Rejected(EngineError.PLAYER_NOT_IN_JAIL)

        return when (action) {
            JailAction.PAY_FINE -> payFineVoluntarily(state, player)
            JailAction.USE_GET_OUT_OF_JAIL_CARD -> useGetOutOfJailCard(state, player)
        }
    }

    // --- Stubs for later sessions ---

    override fun buyAsset(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult =
        TODO("Session 8: auctions/purchase")

    override fun declinePurchase(state: GameState, playerId: PlayerId): EngineResult =
        TODO("Session 8: auctions/purchase")

    override fun placeBid(state: GameState, playerId: PlayerId, amount: Int): EngineResult =
        TODO("Session 8: auctions/purchase")

    override fun build(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult {
        if (state.phase != TurnPhase.AWAITING_OPTIONAL_ACTIONS) return EngineResult.Rejected(EngineError.WRONG_PHASE)
        if (playerId != state.activePlayerId) return EngineResult.Rejected(EngineError.NOT_ACTIVE_PLAYER)

        val propertyConfig = state.config.propertiesById[assetId] ?: return EngineResult.Rejected(EngineError.ASSET_NOT_FOUND)
        val asset = state.assets[assetId] ?: return EngineResult.Rejected(EngineError.ASSET_NOT_FOUND)
        if (asset.ownerId != playerId) return EngineResult.Rejected(EngineError.ASSET_NOT_OWNED_BY_PLAYER)

        val groupAssets = state.config.propertiesInGroup(propertyConfig.group).map { state.assets.getValue(it.id) }
        if (groupAssets.any { it.ownerId != playerId }) return EngineResult.Rejected(EngineError.GROUP_NOT_COMPLETE)
        if (groupAssets.any { it.mortgaged }) return EngineResult.Rejected(EngineError.GROUP_HAS_MORTGAGED_PROPERTY)

        val currentLevel = buildingLevel(asset)
        if (currentLevel >= HOTEL_LEVEL) return EngineResult.Rejected(EngineError.MAX_BUILDINGS_REACHED)

        // Even-building (§13/§14): this property must not already be ahead of any
        // sibling in the group — i.e. every other property must be at least at its level.
        val evenOk = groupAssets.all { it.id == assetId || buildingLevel(it) >= currentLevel }
        if (!evenOk) return EngineResult.Rejected(EngineError.UNEVEN_BUILDING)

        val player = state.player(playerId)
        val cost = propertyConfig.houseCost // BoardEconomy.md has one building-cost field; a hotel costs the same as a house.
        if (player.balance < cost) return EngineResult.Rejected(EngineError.INSUFFICIENT_FUNDS)

        return if (currentLevel < HOTEL_LEVEL - 1) {
            if (state.bankHouses <= 0) return EngineResult.Rejected(EngineError.BUILDING_SUPPLY_EXHAUSTED)
            val updatedAsset = asset.copy(houses = asset.houses + 1)
            val updatedPlayer = player.copy(balance = player.balance - cost)
            val newState = state.copy(
                players = state.players.replace(updatedPlayer),
                assets = state.assets + (assetId to updatedAsset),
                bankHouses = state.bankHouses - 1
            )
            EngineResult.Applied(newState, listOf(GameEvent.HouseBuilt(playerId, assetId, updatedAsset.houses)))
        } else {
            // currentLevel == 4 (four houses): upgrading to a hotel returns the 4 houses to the bank.
            if (state.bankHotels <= 0) return EngineResult.Rejected(EngineError.BUILDING_SUPPLY_EXHAUSTED)
            val updatedAsset = asset.copy(houses = 0, hasHotel = true)
            val updatedPlayer = player.copy(balance = player.balance - cost)
            val newState = state.copy(
                players = state.players.replace(updatedPlayer),
                assets = state.assets + (assetId to updatedAsset),
                bankHouses = state.bankHouses + 4,
                bankHotels = state.bankHotels - 1
            )
            EngineResult.Applied(newState, listOf(GameEvent.HotelBuilt(playerId, assetId)))
        }
    }

    override fun sellBuilding(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult {
        if (state.phase != TurnPhase.AWAITING_OPTIONAL_ACTIONS) return EngineResult.Rejected(EngineError.WRONG_PHASE)
        if (playerId != state.activePlayerId) return EngineResult.Rejected(EngineError.NOT_ACTIVE_PLAYER)

        val propertyConfig = state.config.propertiesById[assetId] ?: return EngineResult.Rejected(EngineError.ASSET_NOT_FOUND)
        val asset = state.assets[assetId] ?: return EngineResult.Rejected(EngineError.ASSET_NOT_FOUND)
        if (asset.ownerId != playerId) return EngineResult.Rejected(EngineError.ASSET_NOT_OWNED_BY_PLAYER)

        val currentLevel = buildingLevel(asset)
        if (currentLevel <= 0) return EngineResult.Rejected(EngineError.NO_BUILDING_TO_SELL)

        val groupAssets = state.config.propertiesInGroup(propertyConfig.group).map { state.assets.getValue(it.id) }
        // Even-selling (§16): must sell from the property at (or above) the group's
        // highest level first — this property must not already be behind a sibling.
        val evenOk = groupAssets.all { buildingLevel(it) <= currentLevel }
        if (!evenOk) return EngineResult.Rejected(EngineError.UNEVEN_BUILDING)

        val player = state.player(playerId)
        val refund = (propertyConfig.houseCost * state.config.constants.buildingResaleRate).toInt()

        return if (currentLevel == HOTEL_LEVEL) {
            // Selling a hotel converts it back to 4 houses (GameRules.md §16) rather than
            // clearing the property outright — that conversion needs 4 houses in bank supply.
            if (state.bankHouses < 4) return EngineResult.Rejected(EngineError.BUILDING_SUPPLY_EXHAUSTED)
            val updatedAsset = asset.copy(houses = 4, hasHotel = false)
            val updatedPlayer = player.copy(balance = player.balance + refund)
            val newState = state.copy(
                players = state.players.replace(updatedPlayer),
                assets = state.assets + (assetId to updatedAsset),
                bankHouses = state.bankHouses - 4,
                bankHotels = state.bankHotels + 1
            )
            EngineResult.Applied(newState, listOf(GameEvent.HotelSold(playerId, assetId)))
        } else {
            val updatedAsset = asset.copy(houses = asset.houses - 1)
            val updatedPlayer = player.copy(balance = player.balance + refund)
            val newState = state.copy(
                players = state.players.replace(updatedPlayer),
                assets = state.assets + (assetId to updatedAsset),
                bankHouses = state.bankHouses + 1
            )
            EngineResult.Applied(newState, listOf(GameEvent.HouseSold(playerId, assetId, updatedAsset.houses)))
        }
    }

    override fun mortgage(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult {
        if (state.phase != TurnPhase.AWAITING_OPTIONAL_ACTIONS) return EngineResult.Rejected(EngineError.WRONG_PHASE)
        if (playerId != state.activePlayerId) return EngineResult.Rejected(EngineError.NOT_ACTIVE_PLAYER)

        val mortgageValue = mortgageValueFor(state, assetId) ?: return EngineResult.Rejected(EngineError.ASSET_NOT_FOUND)
        val asset = state.assets.getValue(assetId)
        if (asset.ownerId != playerId) return EngineResult.Rejected(EngineError.ASSET_NOT_OWNED_BY_PLAYER)
        if (asset.mortgaged) return EngineResult.Rejected(EngineError.ASSET_MORTGAGED)

        // GameRules.md §18/§13: a developed property's buildings must be sold to the
        // Bank before it can be mortgaged. Stations/utilities never carry buildings,
        // so this check is a harmless no-op for them.
        if (asset.houses > 0 || asset.hasHotel) return EngineResult.Rejected(EngineError.MUST_SELL_BUILDINGS_FIRST)

        val player = state.player(playerId)
        val updatedAsset = asset.copy(mortgaged = true)
        val updatedPlayer = player.copy(balance = player.balance + mortgageValue)
        val newState = state.copy(
            players = state.players.replace(updatedPlayer),
            assets = state.assets + (assetId to updatedAsset)
        )
        return EngineResult.Applied(newState, listOf(GameEvent.MortgagePlaced(playerId, assetId, mortgageValue)))
    }

    override fun unmortgage(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult {
        if (state.phase != TurnPhase.AWAITING_OPTIONAL_ACTIONS) return EngineResult.Rejected(EngineError.WRONG_PHASE)
        if (playerId != state.activePlayerId) return EngineResult.Rejected(EngineError.NOT_ACTIVE_PLAYER)

        val mortgageValue = mortgageValueFor(state, assetId) ?: return EngineResult.Rejected(EngineError.ASSET_NOT_FOUND)
        val asset = state.assets.getValue(assetId)
        if (asset.ownerId != playerId) return EngineResult.Rejected(EngineError.ASSET_NOT_OWNED_BY_PLAYER)
        if (!asset.mortgaged) return EngineResult.Rejected(EngineError.ASSET_NOT_MORTGAGED)

        // GameRules.md §18: mortgage value plus 10% interest. Every mortgageValue in
        // BoardEconomy.md is a multiple of 500, so integer division here is exact —
        // no floating-point rounding involved.
        val interest = mortgageValue / 10
        val totalCost = mortgageValue + interest

        val player = state.player(playerId)
        if (player.balance < totalCost) return EngineResult.Rejected(EngineError.INSUFFICIENT_FUNDS)

        val updatedAsset = asset.copy(mortgaged = false)
        val updatedPlayer = player.copy(balance = player.balance - totalCost)
        val newState = state.copy(
            players = state.players.replace(updatedPlayer),
            assets = state.assets + (assetId to updatedAsset)
        )
        return EngineResult.Applied(newState, listOf(GameEvent.MortgageLifted(playerId, assetId, totalCost)))
    }

    override fun proposeTrade(state: GameState, trade: TradeProposal): EngineResult =
        TODO("Session 6: trading")

    override fun resolveTrade(state: GameState, accept: Boolean): EngineResult =
        TODO("Session 6: trading")

    // --- Normal (not-in-jail) roll ---

    private fun applyNormalRoll(state: GameState, player: PlayerState, dice: DiceRoll): EngineResult {
        val events = mutableListOf<GameEvent>(GameEvent.DiceRolled(player.id, dice))
        val doublesCount = if (dice.isDouble) state.consecutiveDoublesCount + 1 else state.consecutiveDoublesCount

        if (dice.isDouble && doublesCount == 3) {
            // GameRules.md §5: on the 3rd consecutive double, the player does NOT make
            // the movement associated with that roll — they go directly to jail, the
            // turn ends immediately, and no GO payment occurs regardless of route.
            val stateWithRoll = state.copy(lastRoll = dice, consecutiveDoublesCount = 0, pendingBonusRoll = false)
            val (finalState, jailEvents) = sendPlayerToJailAndEndTurn(stateWithRoll, player.id, JailReason.THREE_CONSECUTIVE_DOUBLES)
            return EngineResult.Applied(finalState, events + jailEvents)
        }

        val (newPosition, passedGo) = movePosition(player.position, dice.total)
        var movedPlayer = player.copy(position = newPosition)
        events += GameEvent.PlayerMoved(player.id, player.position, newPosition, passedGo)

        if (passedGo) {
            movedPlayer = movedPlayer.copy(balance = movedPlayer.balance + state.config.constants.goReward)
            events += GameEvent.GoCollected(player.id, state.config.constants.goReward)
        }

        val newState = state.copy(
            players = state.players.replace(movedPlayer),
            phase = TurnPhase.RESOLVING_LANDING,
            lastRoll = dice,
            consecutiveDoublesCount = doublesCount,
            pendingBonusRoll = dice.isDouble && doublesCount < 3
        )
        return EngineResult.Applied(newState, events)
    }

    // --- Jail: roll-based exits (doubles-attempt on turns 1/2, forced roll on turn 3) ---

    private fun applyJailRoll(state: GameState, player: PlayerState, dice: DiceRoll): EngineResult {
        val events = mutableListOf<GameEvent>(GameEvent.DiceRolled(player.id, dice))

        if (player.jailTurnsUsed < 2) {
            return if (dice.isDouble) {
                releaseFromJailAndMove(state, player, dice, events, JailReleaseMethod.DOUBLES_ATTEMPT)
            } else {
                val updated = player.copy(jailTurnsUsed = player.jailTurnsUsed + 1)
                events += GameEvent.JailRollFailed(player.id, updated.jailTurnsUsed)
                val stateWithUpdate = state.copy(players = state.players.replace(updated), lastRoll = dice)
                val (finalState, turnEvents) = advanceToNextPlayer(stateWithUpdate)
                EngineResult.Applied(finalState, events + turnEvents)
            }
        }

        // jailTurnsUsed == 2: forced jail-turn 3 (TechnicalSpecification.md §5 / GameRules.md §12).
        // The fine is deducted automatically before movement, and this roll releases and
        // moves the player regardless of doubles — no bonus roll either way.
        val fine = state.config.constants.jailFine
        check(player.balance >= fine) {
            "Session 7 will implement the bankruptcy path for a player who can't cover " +
                    "the forced jail-turn-3 fine (GameRules.md §12, using the fine as the triggering debt)."
        }
        val finedPlayer = player.copy(balance = player.balance - fine)
        events += GameEvent.JailFinePaid(player.id, fine, forced = true)
        val stateWithFine = state.copy(players = state.players.replace(finedPlayer))
        return releaseFromJailAndMove(stateWithFine, finedPlayer, dice, events, JailReleaseMethod.FORCED_TURN_THREE)
    }

    private fun releaseFromJailAndMove(
        state: GameState,
        player: PlayerState,
        dice: DiceRoll,
        events: MutableList<GameEvent>,
        method: JailReleaseMethod
    ): EngineResult {
        val released = player.copy(inJail = false, jailTurnsUsed = 0)
        val (newPosition, passedGo) = movePosition(released.position, dice.total)
        var movedPlayer = released.copy(position = newPosition)

        events += GameEvent.ReleasedFromJail(player.id, method)
        events += GameEvent.PlayerMoved(player.id, released.position, newPosition, passedGo)
        if (passedGo) {
            movedPlayer = movedPlayer.copy(balance = movedPlayer.balance + state.config.constants.goReward)
            events += GameEvent.GoCollected(player.id, state.config.constants.goReward)
        }

        val finalState = state.copy(
            players = state.players.replace(movedPlayer),
            phase = TurnPhase.RESOLVING_LANDING,
            lastRoll = dice,
            consecutiveDoublesCount = 0,
            pendingBonusRoll = false // GameRules.md §12: never a bonus roll on a jail-exit roll, double or not.
        )
        return EngineResult.Applied(finalState, events)
    }

    // --- Jail: voluntary exits before rolling ---

    private fun payFineVoluntarily(state: GameState, player: PlayerState): EngineResult {
        val fine = state.config.constants.jailFine
        if (player.balance < fine) return EngineResult.Rejected(EngineError.INSUFFICIENT_FUNDS)

        // Unlike the FORCED turn-3 fine, a voluntary payment the player can't afford is
        // simply an invalid request (they can attempt doubles or use a card instead) —
        // not a bankruptcy trigger.
        val paid = player.copy(balance = player.balance - fine, inJail = false, jailTurnsUsed = 0)

        // TechnicalSpecification.md §5: voluntary payment releases the player but grants
        // a NORMAL subsequent roll this same turn — no movement happens as part of this
        // action itself (unlike the doubles/forced-roll exits, which move as they release).
        val newState = state.copy(players = state.players.replace(paid), phase = TurnPhase.AWAITING_ROLL)
        return EngineResult.Applied(newState, listOf(GameEvent.JailFinePaid(player.id, fine, forced = false)))
    }

    private fun useGetOutOfJailCard(state: GameState, player: PlayerState): EngineResult {
        val deckUsed = player.getOutOfJailCards.firstOrNull()
            ?: return EngineResult.Rejected(EngineError.NO_GET_OUT_OF_JAIL_CARD)

        val released = player.copy(
            getOutOfJailCards = player.getOutOfJailCards.drop(1),
            inJail = false,
            jailTurnsUsed = 0
        )

        // Each deck has exactly one GetOutOfJailFree card (Cards.md), so the deck alone
        // identifies which specific card id to return to that deck's bottom (Cards.md §3).
        val cardId = deckSourceFor(state, deckUsed).first { it.effect == CardEffect.GetOutOfJailFree }.id
        val stateWithCardReturned = if (deckUsed == Deck.CHANCE) {
            state.copy(chanceDeck = state.chanceDeck + cardId)
        } else {
            state.copy(chestDeck = state.chestDeck + cardId)
        }

        val newState = stateWithCardReturned.copy(
            players = stateWithCardReturned.players.replace(released),
            phase = TurnPhase.AWAITING_ROLL
        )
        return EngineResult.Applied(newState, listOf(GameEvent.GetOutOfJailCardUsed(player.id, deckUsed)))
    }

    private fun deckSourceFor(state: GameState, deck: Deck) =
        if (deck == Deck.CHANCE) state.config.chanceDeck else state.config.chestDeck

    // --- Landing resolution (recursive: a card-forced move fully resolves its destination) ---

    private fun resolveSpaceAt(state: GameState): EngineResult {
        val player = state.activePlayer
        val space = state.config.spacesByIndex.getValue(player.position)
        val events = mutableListOf<GameEvent>()

        return when (space.type) {
            SpaceType.GO, SpaceType.FREE_PARKING, SpaceType.JAIL -> {
                // GO: nothing further beyond the payment already applied on arrival.
                // FREE_PARKING (§11): no jackpot, reward, or penalty in Version 1.
                // JAIL landed on normally is "زيارة فقط" (visiting only, §12): no penalty.
                events += GameEvent.LandingResolved(player.id, player.position)
                EngineResult.Applied(state.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
            }

            SpaceType.TAX -> {
                val amount = taxAmountFor(state, space.developerName)
                check(player.balance >= amount) {
                    "Session 7 will implement the bankruptcy path for a player who " +
                            "can't cover a mandatory tax payment (GameRules.md §19)."
                }
                val paidPlayer = player.copy(balance = player.balance - amount)
                events += GameEvent.TaxPaid(player.id, amount)
                events += GameEvent.LandingResolved(player.id, player.position)
                EngineResult.Applied(
                    state.copy(players = state.players.replace(paidPlayer), phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS),
                    events
                )
            }

            SpaceType.GO_TO_JAIL -> {
                // §6/§12: landing here sends the player directly to jail; no GO payment
                // even though the conceptual route crosses index 0, and the turn ends
                // immediately (no optional actions phase).
                val (finalState, jailEvents) = sendPlayerToJailAndEndTurn(state, player.id, JailReason.LANDED_ON_GO_TO_JAIL_SPACE)
                EngineResult.Applied(finalState, jailEvents)
            }

            SpaceType.PROPERTY, SpaceType.STATION, SpaceType.UTILITY ->
                resolveOwnableLanding(state, player.id, space.assetId!!, events)

            SpaceType.CHANCE, SpaceType.COMMUNITY_CHEST ->
                resolveCardDraw(state, player.id, space.type)
        }
    }

    /** Purchase offer (unowned), no-op (self-owned/mortgaged), or standard rent (§8). */
    private fun resolveOwnableLanding(
        state: GameState,
        playerId: PlayerId,
        assetId: AssetId,
        events: MutableList<GameEvent>
    ): EngineResult {
        val player = state.player(playerId)
        val asset = state.assets.getValue(assetId)

        return when {
            asset.ownerId == null -> {
                events += GameEvent.PurchaseDecisionPending(playerId, assetId)
                EngineResult.Applied(state.copy(phase = TurnPhase.AWAITING_PURCHASE_DECISION), events)
            }

            asset.ownerId == playerId || asset.mortgaged -> {
                events += GameEvent.LandingResolved(playerId, player.position)
                EngineResult.Applied(state.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
            }

            else -> {
                val diceTotal = state.lastRoll?.total ?: 0
                val rent = RentCalculator.rentFor(state.config, state.assets, assetId, diceTotal)
                val (updatedPlayers, paidEvent) = chargeRent(state, playerId, asset.ownerId, assetId, rent)
                events += paidEvent
                events += GameEvent.LandingResolved(playerId, player.position)
                EngineResult.Applied(state.copy(players = updatedPlayers, phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
            }
        }
    }

    private fun chargeRent(
        state: GameState,
        payerId: PlayerId,
        ownerId: PlayerId,
        assetId: AssetId,
        rent: Int
    ): Pair<List<PlayerState>, GameEvent.RentPaid> {
        val payer = state.player(payerId)
        check(payer.balance >= rent) {
            "Session 7 will implement the bankruptcy path for a player who can't cover rent (GameRules.md §19)."
        }
        val owner = state.player(ownerId)
        val updatedPlayers = state.players
            .replace(payer.copy(balance = payer.balance - rent))
            .replace(owner.copy(balance = owner.balance + rent))
        return updatedPlayers to GameEvent.RentPaid(payerId, ownerId, assetId, rent)
    }

    private fun taxAmountFor(state: GameState, developerName: String): Int = when (developerName) {
        "IncomeTax" -> state.config.constants.incomeTax
        "LuxuryTax" -> state.config.constants.luxuryTax
        else -> error("unrecognized tax space: $developerName")
    }

    /** 0..4 houses, or 5 meaning "has a hotel" — lets even-building be one rule at every tier. */
    private fun buildingLevel(asset: AssetState): Int =
        if (asset.hasHotel) HOTEL_LEVEL else asset.houses

    /** Looks up the configured mortgage value regardless of which of the three asset types this is. */
    private fun mortgageValueFor(state: GameState, assetId: AssetId): Int? {
        state.config.propertiesById[assetId]?.let { return it.mortgageValue }
        state.config.stationsById[assetId]?.let { return it.mortgageValue }
        state.config.utilitiesById[assetId]?.let { return it.mortgageValue }
        return null
    }

    // --- Card resolution (Cards.md) ---

    private fun resolveCardDraw(state: GameState, playerId: PlayerId, deckSpaceType: SpaceType): EngineResult {
        val isChance = deckSpaceType == SpaceType.CHANCE
        val deckList = if (isChance) state.chanceDeck else state.chestDeck
        check(deckList.isNotEmpty()) { "deck for $deckSpaceType is unexpectedly empty" }

        val drawnId = deckList.first()
        val card = state.config.cardsById.getValue(drawnId)
        val events = mutableListOf<GameEvent>(GameEvent.CardDrawn(playerId, card.id, card.deck))

        // Non-retained cards return to the bottom of their OWN deck (never merged,
        // Cards.md §3 / GameRules.md §9). GetOutOfJailFree is retained by the player
        // instead — removed from circulation until jailAction (use) or Session 6
        // (trade) returns it to this same deck's bottom.
        val remaining = deckList.drop(1)
        val newDeckList = if (card.effect == CardEffect.GetOutOfJailFree) remaining else remaining + card.id
        val currentState = if (isChance) state.copy(chanceDeck = newDeckList) else state.copy(chestDeck = newDeckList)

        val player = currentState.player(playerId)

        return when (val effect = card.effect) {
            is CardEffect.MoveToPosition -> {
                val distance = forwardDistance(player.position, effect.target)
                val (newPos, passedGo) = movePosition(player.position, distance)
                val (movedState, moveEvents) = moveAndPayGo(currentState, playerId, newPos, passedGo)
                mergeEvents(resolveSpaceAt(movedState), events + moveEvents)
            }

            is CardEffect.MoveRelative -> {
                val (movedState, moveEvents) = if (effect.delta > 0) {
                    val (newPos, passedGo) = movePosition(player.position, effect.delta)
                    moveAndPayGo(currentState, playerId, newPos, passedGo)
                } else {
                    // Backward moves never collect GO, regardless of wrap (Cards.md §3).
                    val newPos = ((player.position + effect.delta) % BOARD_SIZE + BOARD_SIZE) % BOARD_SIZE
                    moveAndPayGo(currentState, playerId, newPos, passedGo = false)
                }
                mergeEvents(resolveSpaceAt(movedState), events + moveEvents)
            }

            is CardEffect.MoveToNearestStation -> {
                val (targetIndex, passedGo) = advanceToNearestSpaceType(currentState, player.position, SpaceType.STATION)
                resolveNearestAssetLanding(currentState, playerId, targetIndex, passedGo, events, rentMultiplier = effect.rentMultiplier, diceMultiplier = null)
            }

            is CardEffect.MoveToNearestUtility -> {
                val (targetIndex, passedGo) = advanceToNearestSpaceType(currentState, player.position, SpaceType.UTILITY)
                resolveNearestAssetLanding(currentState, playerId, targetIndex, passedGo, events, rentMultiplier = null, diceMultiplier = effect.multiplierOverride)
            }

            is CardEffect.CollectFromBank -> {
                val paid = player.copy(balance = player.balance + effect.amount)
                events += GameEvent.CardBankPayout(playerId, effect.amount)
                events += GameEvent.LandingResolved(playerId, player.position)
                EngineResult.Applied(currentState.copy(players = currentState.players.replace(paid), phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
            }

            is CardEffect.PayToBank -> {
                check(player.balance >= effect.amount) { BANKRUPTCY_TODO_MESSAGE }
                val paid = player.copy(balance = player.balance - effect.amount)
                events += GameEvent.CardBankCharge(playerId, effect.amount)
                events += GameEvent.LandingResolved(playerId, player.position)
                EngineResult.Applied(currentState.copy(players = currentState.players.replace(paid), phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
            }

            is CardEffect.PayEachPlayer -> {
                val others = currentState.nonBankruptPlayers.filter { it.id != playerId }
                val total = effect.amount * others.size
                check(player.balance >= total) { BANKRUPTCY_TODO_MESSAGE }

                var updatedPlayers = currentState.players.replace(player.copy(balance = player.balance - total))
                others.forEach { other ->
                    val current = updatedPlayers.first { it.id == other.id }
                    updatedPlayers = updatedPlayers.replace(current.copy(balance = current.balance + effect.amount))
                    events += GameEvent.CardPlayerToPlayerPayment(playerId, other.id, effect.amount)
                }
                events += GameEvent.LandingResolved(playerId, player.position)
                EngineResult.Applied(currentState.copy(players = updatedPlayers, phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
            }

            is CardEffect.CollectFromEachPlayer -> {
                val others = currentState.nonBankruptPlayers.filter { it.id != playerId }
                others.forEach { other -> check(other.balance >= effect.amount) { BANKRUPTCY_TODO_MESSAGE } }

                var updatedPlayers = currentState.players
                others.forEach { other ->
                    val current = updatedPlayers.first { it.id == other.id }
                    updatedPlayers = updatedPlayers.replace(current.copy(balance = current.balance - effect.amount))
                    events += GameEvent.CardPlayerToPlayerPayment(other.id, playerId, effect.amount)
                }
                val collector = updatedPlayers.first { it.id == playerId }
                updatedPlayers = updatedPlayers.replace(collector.copy(balance = collector.balance + effect.amount * others.size))
                events += GameEvent.LandingResolved(playerId, player.position)
                EngineResult.Applied(currentState.copy(players = updatedPlayers, phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
            }

            is CardEffect.PropertyRepairs -> {
                val ownedAssets = currentState.assetsOwnedBy(playerId)
                val houses = ownedAssets.sumOf { it.houses }
                val hotels = ownedAssets.count { it.hasHotel }
                val amount = effect.perHouse * houses + effect.perHotel * hotels
                check(player.balance >= amount) { BANKRUPTCY_TODO_MESSAGE }
                val paid = player.copy(balance = player.balance - amount)
                events += GameEvent.CardBankCharge(playerId, amount)
                events += GameEvent.LandingResolved(playerId, player.position)
                EngineResult.Applied(currentState.copy(players = currentState.players.replace(paid), phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
            }

            CardEffect.GoToJail -> {
                // §6/Cards.md §3: never pays GO even though the conceptual route may cross it.
                val (finalState, jailEvents) = sendPlayerToJailAndEndTurn(currentState, playerId, JailReason.CARD_EFFECT)
                EngineResult.Applied(finalState, events + jailEvents)
            }

            CardEffect.GetOutOfJailFree -> {
                val updated = player.copy(getOutOfJailCards = player.getOutOfJailCards + card.deck)
                events += GameEvent.GetOutOfJailCardReceived(playerId)
                events += GameEvent.LandingResolved(playerId, player.position)
                EngineResult.Applied(currentState.copy(players = currentState.players.replace(updated), phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
            }
        }
    }

    /**
     * Handles the destination of a MoveToNearestStation/Utility card. Unowned,
     * self-owned, and mortgaged destinations behave exactly like a normal landing
     * (deferred to [resolveSpaceAt]). Owned-by-someone-else-and-not-mortgaged is the
     * one case with a card-specific rent override (double the usual station rent, or
     * a flat dice-multiplier for utilities) instead of the standard RentCalculator formula.
     */
    private fun resolveNearestAssetLanding(
        state: GameState,
        playerId: PlayerId,
        targetIndex: Int,
        passedGo: Boolean,
        cardEvents: MutableList<GameEvent>,
        rentMultiplier: Int?,
        diceMultiplier: Int?
    ): EngineResult {
        val (movedState, moveEvents) = moveAndPayGo(state, playerId, targetIndex, passedGo)
        val space = movedState.config.spacesByIndex.getValue(targetIndex)
        val assetId = space.assetId!!
        val asset = movedState.assets.getValue(assetId)

        if (asset.ownerId == null || asset.ownerId == playerId || asset.mortgaged) {
            return mergeEvents(resolveSpaceAt(movedState), cardEvents + moveEvents)
        }

        val diceTotal = movedState.lastRoll?.total ?: 0
        val rent = when {
            rentMultiplier != null -> RentCalculator.rentFor(movedState.config, movedState.assets, assetId, diceTotal) * rentMultiplier
            diceMultiplier != null -> diceTotal * diceMultiplier
            else -> error("resolveNearestAssetLanding requires exactly one rent override")
        }

        val (updatedPlayers, paidEvent) = chargeRent(movedState, playerId, asset.ownerId, assetId, rent)
        val events = cardEvents + moveEvents + paidEvent + GameEvent.LandingResolved(playerId, targetIndex)
        return EngineResult.Applied(movedState.copy(players = updatedPlayers, phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
    }

    private fun mergeEvents(result: EngineResult, prefixEvents: List<GameEvent>): EngineResult = when (result) {
        is EngineResult.Applied -> EngineResult.Applied(result.newState, prefixEvents + result.events)
        is EngineResult.Rejected -> result
    }

    private fun moveAndPayGo(state: GameState, playerId: PlayerId, newPosition: Int, passedGo: Boolean): Pair<GameState, List<GameEvent>> {
        val player = state.player(playerId)
        var updated = player.copy(position = newPosition)
        val events = mutableListOf<GameEvent>(GameEvent.PlayerMoved(playerId, player.position, newPosition, passedGo))
        if (passedGo) {
            updated = updated.copy(balance = updated.balance + state.config.constants.goReward)
            events += GameEvent.GoCollected(playerId, state.config.constants.goReward)
        }
        return state.copy(players = state.players.replace(updated)) to events
    }

    /** Forward-only distance from [from] to [target], per the classic "advance to X" card convention. */
    private fun forwardDistance(from: Int, target: Int): Int {
        val distance = ((target - from) % BOARD_SIZE + BOARD_SIZE) % BOARD_SIZE
        return if (distance == 0) BOARD_SIZE else distance
    }

    private fun advanceToNearestSpaceType(state: GameState, from: Int, type: SpaceType): Pair<Int, Boolean> {
        for (offset in 1..BOARD_SIZE) {
            val idx = (from + offset) % BOARD_SIZE
            if (state.config.spacesByIndex.getValue(idx).type == type) {
                return movePosition(from, offset)
            }
        }
        error("no space of type $type exists on the board")
    }

    // --- Shared jail-entry / turn-advancement helpers ---

    private fun sendPlayerToJailAndEndTurn(state: GameState, playerId: PlayerId, reason: JailReason): Pair<GameState, List<GameEvent>> {
        val jailSpace = state.config.spaces.first { it.type == SpaceType.JAIL }
        val player = state.player(playerId)
        val jailedPlayer = player.copy(position = jailSpace.index, inJail = true, jailTurnsUsed = 0)
        val events = mutableListOf<GameEvent>(GameEvent.SentToJail(playerId, reason))
        val stateWithJailedPlayer = state.copy(players = state.players.replace(jailedPlayer))
        val (finalState, turnEvents) = advanceToNextPlayer(stateWithJailedPlayer)
        return finalState to (events + turnEvents)
    }

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
     * used when a rule ends the turn immediately (3-consecutive-doubles, landing on
     * GO_TO_JAIL, or a GoToJail card), as opposed to the player choosing to end it.
     * Advances to the next non-bankrupt player, resets per-turn doubles/bonus-roll
     * tracking, and checks for a win per GameRules.md §20.
     */
    internal fun advanceToNextPlayer(state: GameState): Pair<GameState, List<GameEvent>> {
        val nonBankrupt = state.nonBankruptPlayers
        if (nonBankrupt.size <= 1) {
            val winnerState = state.copy(
                phase = TurnPhase.GAME_OVER,
                lastRoll = null,
                consecutiveDoublesCount = 0,
                pendingBonusRoll = false
            )
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
            consecutiveDoublesCount = 0,
            pendingBonusRoll = false
        )
        return newState to listOf(GameEvent.TurnChanged(nextPlayer.id))
    }

    private companion object {
        const val BOARD_SIZE = 40
        const val HOTEL_LEVEL = 5
        const val BANKRUPTCY_TODO_MESSAGE =
            "Session 7 will implement the bankruptcy path for a player who can't cover a mandatory card payment (GameRules.md §19)."
    }
}

/** Returns a copy of the list with the element matching [updated]'s id replaced. */
internal fun List<PlayerState>.replace(updated: PlayerState): List<PlayerState> =
    map { if (it.id == updated.id) updated else it }
package com.menouer.rules_engine

import com.menouer.economy_data.CardEffect
import com.menouer.economy_data.Deck
import com.menouer.economy_data.SpaceType
import com.menouer.rules_engine.dice.DiceRoll
import com.menouer.rules_engine.model.AssetId
import com.menouer.rules_engine.model.AssetState
import com.menouer.rules_engine.model.AuctionState
import com.menouer.rules_engine.model.EngineError
import com.menouer.rules_engine.model.EngineResult
import com.menouer.rules_engine.model.GameEvent
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.JailReason
import com.menouer.rules_engine.model.JailReleaseMethod
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.PlayerState
import com.menouer.rules_engine.model.TradeProposal
import com.menouer.rules_engine.model.TradeState
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

    // --- Purchase / auctions (GameRules.md §7) ---

    override fun buyAsset(state: GameState, playerId: PlayerId, assetId: AssetId): EngineResult {
        if (state.phase != TurnPhase.AWAITING_PURCHASE_DECISION) return EngineResult.Rejected(EngineError.WRONG_PHASE)
        if (playerId != state.activePlayerId) return EngineResult.Rejected(EngineError.NOT_ACTIVE_PLAYER)

        val pendingAssetId = pendingPurchaseAssetId(state) ?: return EngineResult.Rejected(EngineError.INVALID_REQUEST)
        if (assetId != pendingAssetId) return EngineResult.Rejected(EngineError.INVALID_REQUEST)

        val asset = state.assets.getValue(assetId)
        if (asset.ownerId != null) return EngineResult.Rejected(EngineError.ASSET_ALREADY_OWNED)

        val price = purchasePriceFor(state, assetId) ?: return EngineResult.Rejected(EngineError.ASSET_NOT_FOUND)
        val player = state.player(playerId)
        if (player.balance < price) return EngineResult.Rejected(EngineError.INSUFFICIENT_FUNDS)

        val updatedPlayer = player.copy(balance = player.balance - price)
        val updatedAsset = asset.copy(ownerId = playerId)
        val newState = state.copy(
            players = state.players.replace(updatedPlayer),
            assets = state.assets + (assetId to updatedAsset),
            phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS
        )
        return EngineResult.Applied(newState, listOf(GameEvent.AssetPurchased(playerId, assetId, price)))
    }

    override fun declinePurchase(state: GameState, playerId: PlayerId): EngineResult {
        if (state.phase != TurnPhase.AWAITING_PURCHASE_DECISION) return EngineResult.Rejected(EngineError.WRONG_PHASE)
        if (playerId != state.activePlayerId) return EngineResult.Rejected(EngineError.NOT_ACTIVE_PLAYER)

        val assetId = pendingPurchaseAssetId(state) ?: return EngineResult.Rejected(EngineError.INVALID_REQUEST)
        val minimumBid = state.config.constants.auctionMinimumBid

        // GameRules.md §7: the player who declined may still participate in the auction.
        val auction = AuctionState(
            assetId = assetId,
            highestBid = 0,
            highestBidderId = null,
            eligibleBidders = state.nonBankruptPlayers.map { it.id }.toSet()
        )
        val newState = state.copy(phase = TurnPhase.IN_AUCTION, pendingAuction = auction)
        return EngineResult.Applied(newState, listOf(GameEvent.AuctionStarted(assetId, minimumBid)))
    }

    override fun placeBid(state: GameState, playerId: PlayerId, amount: Int): EngineResult {
        if (state.phase != TurnPhase.IN_AUCTION) return EngineResult.Rejected(EngineError.WRONG_PHASE)
        val auction = state.pendingAuction ?: return EngineResult.Rejected(EngineError.NOT_IN_AUCTION)
        if (playerId !in auction.eligibleBidders || playerId in auction.passedBidders) {
            return EngineResult.Rejected(EngineError.NOT_ELIGIBLE_TO_BID)
        }

        val minimumValid = if (auction.highestBidderId == null) {
            state.config.constants.auctionMinimumBid
        } else {
            auction.highestBid + state.config.constants.auctionMinimumIncrement
        }
        if (amount < minimumValid) return EngineResult.Rejected(EngineError.INVALID_BID)

        val player = state.player(playerId)
        if (player.balance < amount) return EngineResult.Rejected(EngineError.INSUFFICIENT_FUNDS)

        val updatedAuction = auction.copy(highestBid = amount, highestBidderId = playerId)
        val stateWithBid = state.copy(pendingAuction = updatedAuction)
        return concludeAuctionIfDone(stateWithBid, listOf(GameEvent.AuctionBidPlaced(playerId, auction.assetId, amount)))
    }

    override fun passAuction(state: GameState, playerId: PlayerId): EngineResult {
        if (state.phase != TurnPhase.IN_AUCTION) return EngineResult.Rejected(EngineError.WRONG_PHASE)
        val auction = state.pendingAuction ?: return EngineResult.Rejected(EngineError.NOT_IN_AUCTION)
        if (playerId !in auction.eligibleBidders || playerId in auction.passedBidders) {
            return EngineResult.Rejected(EngineError.NOT_ELIGIBLE_TO_BID)
        }

        val updatedAuction = auction.copy(passedBidders = auction.passedBidders + playerId)
        val stateWithPass = state.copy(pendingAuction = updatedAuction)
        return concludeAuctionIfDone(stateWithPass, listOf(GameEvent.AuctionPassed(playerId, auction.assetId)))
    }

    /**
     * GameRules.md §7: "The auction ends when all other eligible bidders have
     * passed or timed out" (timeouts are MultiplayerProtocol.md's concern, resolved
     * into an equivalent passAuction call before it ever reaches the engine). Here
     * that means: everyone except the current highest bidder (if any) has passed.
     */
    private fun concludeAuctionIfDone(state: GameState, priorEvents: List<GameEvent>): EngineResult {
        val auction = state.pendingAuction!!
        val remaining = auction.eligibleBidders - auction.passedBidders - setOfNotNull(auction.highestBidderId)
        if (remaining.isNotEmpty()) return EngineResult.Applied(state, priorEvents) // still waiting on someone

        val events = priorEvents.toMutableList()
        val winnerId = auction.highestBidderId

        return if (winnerId != null) {
            val winner = state.player(winnerId)
            val updatedWinner = winner.copy(balance = winner.balance - auction.highestBid)
            val updatedAsset = state.assets.getValue(auction.assetId).copy(ownerId = winnerId)
            events += GameEvent.AuctionWon(winnerId, auction.assetId, auction.highestBid)
            val newState = state.copy(
                players = state.players.replace(updatedWinner),
                assets = state.assets + (auction.assetId to updatedAsset),
                phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS,
                pendingAuction = null
            )
            EngineResult.Applied(newState, events)
        } else {
            events += GameEvent.AuctionEndedWithNoBids(auction.assetId)
            val newState = state.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS, pendingAuction = null)
            EngineResult.Applied(newState, events)
        }
    }

    /** The asset at the active player's current position — always correct since nothing moves between landing and this decision. */
    private fun pendingPurchaseAssetId(state: GameState): AssetId? =
        state.config.spacesByIndex[state.activePlayer.position]?.assetId

    private fun purchasePriceFor(state: GameState, assetId: AssetId): Int? {
        state.config.propertiesById[assetId]?.let { return it.purchasePrice }
        state.config.stationsById[assetId]?.let { return it.purchasePrice }
        state.config.utilitiesById[assetId]?.let { return it.purchasePrice }
        return null
    }

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

    override fun proposeTrade(state: GameState, trade: TradeProposal): EngineResult {
        if (state.phase == TurnPhase.GAME_OVER) return EngineResult.Rejected(EngineError.WRONG_PHASE)
        if (state.pendingTrade != null) return EngineResult.Rejected(EngineError.TRADE_ALREADY_PENDING)

        val validationError = validateTradeProposal(state, trade)
        if (validationError != null) return EngineResult.Rejected(validationError)

        // Pauses the game (one global phase, no per-player tracking) until the
        // counterparty responds; previousPhase is restored either way in resolveTrade.
        val newState = state.copy(
            pendingTrade = TradeState(trade, previousPhase = state.phase),
            phase = TurnPhase.IN_TRADE
        )
        return EngineResult.Applied(newState, listOf(GameEvent.TradeProposed(trade.fromPlayerId, trade.toPlayerId)))
    }

    override fun resolveTrade(state: GameState, accept: Boolean): EngineResult {
        if (state.phase != TurnPhase.IN_TRADE) return EngineResult.Rejected(EngineError.WRONG_PHASE)
        val pending = state.pendingTrade ?: return EngineResult.Rejected(EngineError.INVALID_TRADE)

        if (!accept) {
            val restoredState = state.copy(pendingTrade = null, phase = pending.previousPhase)
            return EngineResult.Applied(
                restoredState,
                listOf(GameEvent.TradeResolved(pending.proposal.fromPlayerId, pending.proposal.toPlayerId, accepted = false))
            )
        }

        // MultiplayerProtocol.md §12: a trade commits atomically — all agreed assets
        // transfer or none do. Re-validating here (rather than trusting the check at
        // proposal time) keeps that guarantee honest even though nothing else could
        // have changed while the game was paused IN_TRADE.
        val validationError = validateTradeProposal(state.copy(pendingTrade = null), pending.proposal)
        if (validationError != null) return EngineResult.Rejected(validationError)

        val executedState = executeTrade(state, pending.proposal)
        val newState = executedState.copy(pendingTrade = null, phase = pending.previousPhase)
        return EngineResult.Applied(
            newState,
            listOf(GameEvent.TradeResolved(pending.proposal.fromPlayerId, pending.proposal.toPlayerId, accepted = true))
        )
    }

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
        val (settledState, debtEvents) = settleDebt(state, player.id, creditorId = null, fine)
        if (settledState.player(player.id).bankrupt) {
            events += debtEvents
            val (finalState, turnEvents) = advanceToNextPlayer(settledState)
            return EngineResult.Applied(finalState, events + turnEvents)
        }
        events += GameEvent.JailFinePaid(player.id, fine, forced = true)
        events += debtEvents
        return releaseFromJailAndMove(settledState, settledState.player(player.id), dice, events, JailReleaseMethod.FORCED_TURN_THREE)
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
                val (settledState, debtEvents) = settleDebt(state, player.id, creditorId = null, amount)
                if (settledState.player(player.id).bankrupt) {
                    events += debtEvents
                    val (finalState, turnEvents) = advanceToNextPlayer(settledState)
                    EngineResult.Applied(finalState, events + turnEvents)
                } else {
                    events += GameEvent.TaxPaid(player.id, amount)
                    events += debtEvents
                    events += GameEvent.LandingResolved(player.id, player.position)
                    EngineResult.Applied(settledState.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
                }
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
                val (settledState, debtEvents) = settleDebt(state, playerId, asset.ownerId, rent)
                if (settledState.player(playerId).bankrupt) {
                    events += debtEvents
                    val (finalState, turnEvents) = advanceToNextPlayer(settledState)
                    EngineResult.Applied(finalState, events + turnEvents)
                } else {
                    events += GameEvent.RentPaid(playerId, asset.ownerId, assetId, rent)
                    events += debtEvents
                    events += GameEvent.LandingResolved(playerId, player.position)
                    EngineResult.Applied(settledState.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
                }
            }
        }
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

    // --- Trading (GameRules.md §17) ---

    private fun validateTradeProposal(state: GameState, trade: TradeProposal): EngineError? {
        if (trade.fromPlayerId == trade.toPlayerId) return EngineError.INVALID_TRADE
        val from = state.playerOrNull(trade.fromPlayerId) ?: return EngineError.PLAYER_NOT_FOUND
        val to = state.playerOrNull(trade.toPlayerId) ?: return EngineError.PLAYER_NOT_FOUND
        if (from.bankrupt || to.bankrupt) return EngineError.PLAYER_BANKRUPT

        if (trade.offeredCash < 0 || trade.requestedCash < 0) return EngineError.INVALID_TRADE
        if (from.balance < trade.offeredCash) return EngineError.INSUFFICIENT_FUNDS
        if (to.balance < trade.requestedCash) return EngineError.INSUFFICIENT_FUNDS

        for (assetId in trade.offeredAssets) {
            val asset = state.assets[assetId] ?: return EngineError.ASSET_NOT_FOUND
            if (asset.ownerId != trade.fromPlayerId) return EngineError.ASSET_NOT_OWNED_BY_PLAYER
            if (assetGroupHasBuildings(state, assetId)) return EngineError.MUST_SELL_BUILDINGS_FIRST
        }
        for (assetId in trade.requestedAssets) {
            val asset = state.assets[assetId] ?: return EngineError.ASSET_NOT_FOUND
            if (asset.ownerId != trade.toPlayerId) return EngineError.ASSET_NOT_OWNED_BY_PLAYER
            if (assetGroupHasBuildings(state, assetId)) return EngineError.MUST_SELL_BUILDINGS_FIRST
        }

        if (!playerHoldsCards(from, trade.offeredGetOutOfJailCards)) return EngineError.NO_GET_OUT_OF_JAIL_CARD
        if (!playerHoldsCards(to, trade.requestedGetOutOfJailCards)) return EngineError.NO_GET_OUT_OF_JAIL_CARD

        return null
    }

    /** GameRules.md §17: a property can't be traded while its group contains ANY buildings, even on a different member. */
    private fun assetGroupHasBuildings(state: GameState, assetId: AssetId): Boolean {
        val propertyConfig = state.config.propertiesById[assetId] ?: return false // stations/utilities never carry buildings
        return state.config.propertiesInGroup(propertyConfig.group).any {
            val a = state.assets.getValue(it.id)
            a.houses > 0 || a.hasHotel
        }
    }

    private fun playerHoldsCards(player: PlayerState, required: List<Deck>): Boolean {
        val available = player.getOutOfJailCards.toMutableList()
        return required.all { available.remove(it) }
    }

    private fun executeTrade(state: GameState, trade: TradeProposal): GameState {
        val from = state.player(trade.fromPlayerId)
        val to = state.player(trade.toPlayerId)

        val fromCardsAfterGiving = from.getOutOfJailCards.toMutableList().apply {
            trade.offeredGetOutOfJailCards.forEach { remove(it) }
        }
        val toCardsAfterGiving = to.getOutOfJailCards.toMutableList().apply {
            trade.requestedGetOutOfJailCards.forEach { remove(it) }
        }

        val updatedFrom = from.copy(
            balance = from.balance - trade.offeredCash + trade.requestedCash,
            getOutOfJailCards = fromCardsAfterGiving + trade.requestedGetOutOfJailCards
        )
        val updatedTo = to.copy(
            balance = to.balance - trade.requestedCash + trade.offeredCash,
            getOutOfJailCards = toCardsAfterGiving + trade.offeredGetOutOfJailCards
        )

        var updatedAssets = state.assets
        trade.offeredAssets.forEach { assetId ->
            updatedAssets = updatedAssets + (assetId to updatedAssets.getValue(assetId).copy(ownerId = trade.toPlayerId))
        }
        trade.requestedAssets.forEach { assetId ->
            updatedAssets = updatedAssets + (assetId to updatedAssets.getValue(assetId).copy(ownerId = trade.fromPlayerId))
        }

        return state.copy(
            players = state.players.replace(updatedFrom).replace(updatedTo),
            assets = updatedAssets
        )
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
                val (settledState, debtEvents) = settleDebt(currentState, playerId, creditorId = null, effect.amount)
                if (settledState.player(playerId).bankrupt) {
                    val (finalState, turnEvents) = advanceToNextPlayer(settledState)
                    EngineResult.Applied(finalState, events + debtEvents + turnEvents)
                } else {
                    events += GameEvent.CardBankCharge(playerId, effect.amount)
                    events += debtEvents
                    events += GameEvent.LandingResolved(playerId, player.position)
                    EngineResult.Applied(settledState.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
                }
            }

            is CardEffect.PayEachPlayer -> {
                val others = currentState.nonBankruptPlayers.filter { it.id != playerId }
                var workingState = currentState
                var payerWentBankrupt = false

                for (other in others) {
                    val (settledState, debtEvents) = settleDebt(workingState, playerId, other.id, effect.amount)
                    workingState = settledState
                    events += debtEvents
                    if (workingState.player(playerId).bankrupt) {
                        payerWentBankrupt = true
                        break
                    }
                    events += GameEvent.CardPlayerToPlayerPayment(playerId, other.id, effect.amount)
                }

                if (payerWentBankrupt) {
                    val (finalState, turnEvents) = advanceToNextPlayer(workingState)
                    EngineResult.Applied(finalState, events + turnEvents)
                } else {
                    events += GameEvent.LandingResolved(playerId, player.position)
                    EngineResult.Applied(workingState.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
                }
            }

            is CardEffect.CollectFromEachPlayer -> {
                val others = currentState.nonBankruptPlayers.filter { it.id != playerId }
                var workingState = currentState

                others.forEach { other ->
                    val (settledState, debtEvents) = settleDebt(workingState, other.id, playerId, effect.amount)
                    workingState = settledState
                    events += debtEvents
                    if (!workingState.player(other.id).bankrupt) {
                        events += GameEvent.CardPlayerToPlayerPayment(other.id, playerId, effect.amount)
                    }
                    // If 'other' went bankrupt, settleDebt already routed their assets/cash
                    // to playerId (the active player, as creditor) — nothing further needed.
                }

                events += GameEvent.LandingResolved(playerId, player.position)
                val (finalState, gameOverEvents) = checkForGameOver(workingState.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS))
                EngineResult.Applied(finalState, events + gameOverEvents)
            }

            is CardEffect.PropertyRepairs -> {
                val ownedAssets = currentState.assetsOwnedBy(playerId)
                val houses = ownedAssets.sumOf { it.houses }
                val hotels = ownedAssets.count { it.hasHotel }
                val amount = effect.perHouse * houses + effect.perHotel * hotels

                val (settledState, debtEvents) = settleDebt(currentState, playerId, creditorId = null, amount)
                if (settledState.player(playerId).bankrupt) {
                    val (finalState, turnEvents) = advanceToNextPlayer(settledState)
                    EngineResult.Applied(finalState, events + debtEvents + turnEvents)
                } else {
                    events += GameEvent.CardBankCharge(playerId, amount)
                    events += debtEvents
                    events += GameEvent.LandingResolved(playerId, player.position)
                    EngineResult.Applied(settledState.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
                }
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

        val (settledState, debtEvents) = settleDebt(movedState, playerId, asset.ownerId, rent)
        return if (settledState.player(playerId).bankrupt) {
            val (finalState, turnEvents) = advanceToNextPlayer(settledState)
            EngineResult.Applied(finalState, cardEvents + moveEvents + debtEvents + turnEvents)
        } else {
            val events = cardEvents + moveEvents +
                    GameEvent.RentPaid(playerId, asset.ownerId, assetId, rent) +
                    debtEvents +
                    GameEvent.LandingResolved(playerId, targetIndex)
            EngineResult.Applied(settledState.copy(phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS), events)
        }
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

    // --- Bankruptcy (GameRules.md §19) ---

    /**
     * Settles a debt of [amount] from [debtorId] to [creditorId] (null = the Bank).
     * If cash alone covers it, this is a simple transfer. If not, GameRules.md §19
     * requires checking what can be raised from cash AND eligible assets before
     * declaring bankruptcy — so this auto-liquidates (selling buildings, then
     * mortgaging assets, ONE STEP AT A TIME, stopping the instant enough has been
     * raised) and only declares bankruptcy if even fully liquidating everything
     * still isn't enough. There is no "let the player choose what to sell first"
     * step: since the engine is a pure function with no paused player-input point
     * mid-payment, liquidation proceeds automatically — but it never sells or
     * mortgages more than the debt actually requires.
     *
     * Callers must check `newState.player(debtorId).bankrupt` afterward — if true,
     * the debtor's turn must end immediately (they can't take optional actions), by
     * calling advanceToNextPlayer rather than proceeding normally.
     */
    private fun settleDebt(state: GameState, debtorId: PlayerId, creditorId: PlayerId?, amount: Int): Pair<GameState, List<GameEvent>> {
        if (amount <= 0) return state to emptyList()

        val debtor = state.player(debtorId)
        if (debtor.balance >= amount) {
            return transferCash(state, debtorId, creditorId, amount) to emptyList()
        }

        val (liquidatedState, liquidationEvents, covered) = liquidateUntilCovered(state, debtorId, amount)
        return if (covered) {
            transferCash(liquidatedState, debtorId, creditorId, amount) to liquidationEvents
        } else {
            declareBankruptcy(liquidatedState, debtorId, creditorId, liquidationEvents)
        }
    }

    private fun transferCash(state: GameState, fromId: PlayerId, toId: PlayerId?, amount: Int): GameState {
        val from = state.player(fromId)
        var newState = state.copy(players = state.players.replace(from.copy(balance = from.balance - amount)))
        if (toId != null) {
            val to = newState.player(toId)
            newState = newState.copy(players = newState.players.replace(to.copy(balance = to.balance + amount)))
        }
        return newState
    }

    /**
     * Sells this player's buildings (hotels convert to 4 houses first, at 50% of
     * houseCost per GameRules.md §16, then those houses sell too) and mortgages
     * their unmortgaged assets (GameRules.md §18) — one building or one mortgage at
     * a time, stopping the moment their balance reaches [amount]. This is NOT the
     * same code path as the player-initiated build/sellBuilding/mortgage methods:
     * those enforce even-selling order for player choice, which doesn't apply here
     * since this is an automatic, last-resort raise rather than a player decision.
     */
    private fun liquidateUntilCovered(state: GameState, playerId: PlayerId, amount: Int): Triple<GameState, List<GameEvent>, Boolean> {
        var currentState = state
        val events = mutableListOf<GameEvent>()
        fun covered() = currentState.player(playerId).balance >= amount

        for (propertyConfig in currentState.assetsOwnedBy(playerId).mapNotNull { currentState.config.propertiesById[it.id] }) {
            if (covered()) break
            var asset = currentState.assets.getValue(propertyConfig.id)
            val refund = (propertyConfig.houseCost * currentState.config.constants.buildingResaleRate).toInt()

            if (asset.hasHotel) {
                val player = currentState.player(playerId)
                currentState = currentState.copy(
                    players = currentState.players.replace(player.copy(balance = player.balance + refund)),
                    assets = currentState.assets + (propertyConfig.id to asset.copy(hasHotel = false, houses = 4)),
                    bankHouses = currentState.bankHouses - 4,
                    bankHotels = currentState.bankHotels + 1
                )
                events += GameEvent.HotelSold(playerId, propertyConfig.id)
                asset = currentState.assets.getValue(propertyConfig.id)
            }

            while (asset.houses > 0 && !covered()) {
                val player = currentState.player(playerId)
                val updatedAsset = asset.copy(houses = asset.houses - 1)
                currentState = currentState.copy(
                    players = currentState.players.replace(player.copy(balance = player.balance + refund)),
                    assets = currentState.assets + (propertyConfig.id to updatedAsset),
                    bankHouses = currentState.bankHouses + 1
                )
                events += GameEvent.HouseSold(playerId, propertyConfig.id, updatedAsset.houses)
                asset = updatedAsset
            }
        }

        if (!covered()) {
            for (asset in currentState.assetsOwnedBy(playerId).filterNot { it.mortgaged }) {
                if (covered()) break
                val mortgageValue = mortgageValueFor(currentState, asset.id) ?: continue
                val player = currentState.player(playerId)
                currentState = currentState.copy(
                    players = currentState.players.replace(player.copy(balance = player.balance + mortgageValue)),
                    assets = currentState.assets + (asset.id to asset.copy(mortgaged = true))
                )
                events += GameEvent.MortgagePlaced(playerId, asset.id, mortgageValue)
            }
        }

        return Triple(currentState, events, covered())
    }

    /**
     * [creditorId] null means debt to the Bank: remaining assets become unowned and
     * unmortgaged (GameRules.md §19 says these should be auctioned — Session 8 will
     * wire that in; for now they're simply returned to the available pool) and any
     * held Get Out of Jail Free cards return to their own decks. A named creditor
     * instead receives every remaining asset and all remaining cash directly.
     * Either way the debtor ends at 0 balance, no assets, no cards, bankrupt = true.
     */
    private fun declareBankruptcy(
        state: GameState,
        debtorId: PlayerId,
        creditorId: PlayerId?,
        priorEvents: List<GameEvent>
    ): Pair<GameState, List<GameEvent>> {
        val debtor = state.player(debtorId)
        val events = priorEvents + GameEvent.PlayerBankrupted(debtorId, creditorId)
        var currentState = state
        val ownedAssetIds = currentState.assetsOwnedBy(debtorId).map { it.id }

        if (creditorId != null) {
            ownedAssetIds.forEach { assetId ->
                currentState = currentState.copy(
                    assets = currentState.assets + (assetId to currentState.assets.getValue(assetId).copy(ownerId = creditorId))
                )
            }
            val creditor = currentState.player(creditorId)
            currentState = currentState.copy(
                players = currentState.players.replace(
                    creditor.copy(
                        balance = creditor.balance + debtor.balance,
                        getOutOfJailCards = creditor.getOutOfJailCards + debtor.getOutOfJailCards
                    )
                )
            )
        } else {
            ownedAssetIds.forEach { assetId ->
                currentState = currentState.copy(
                    assets = currentState.assets + (assetId to currentState.assets.getValue(assetId).copy(ownerId = null, mortgaged = false))
                )
            }
            debtor.getOutOfJailCards.forEach { deck ->
                val cardId = deckSourceFor(currentState, deck).first { it.effect == CardEffect.GetOutOfJailFree }.id
                currentState = if (deck == Deck.CHANCE) {
                    currentState.copy(chanceDeck = currentState.chanceDeck + cardId)
                } else {
                    currentState.copy(chestDeck = currentState.chestDeck + cardId)
                }
            }
        }

        val bankruptedPlayer = debtor.copy(balance = 0, bankrupt = true, getOutOfJailCards = emptyList())
        currentState = currentState.copy(players = currentState.players.replace(bankruptedPlayer))
        return currentState to events
    }

    /** For paths that don't route through advanceToNextPlayer but could still reduce non-bankrupt players to 1. */
    private fun checkForGameOver(state: GameState): Pair<GameState, List<GameEvent>> =
        if (state.nonBankruptPlayers.size <= 1) {
            state.copy(phase = TurnPhase.GAME_OVER) to listOf(GameEvent.GameEnded)
        } else {
            state to emptyList()
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
    }
}

/** Returns a copy of the list with the element matching [updated]'s id replaced. */
internal fun List<PlayerState>.replace(updated: PlayerState): List<PlayerState> =
    map { if (it.id == updated.id) updated else it }
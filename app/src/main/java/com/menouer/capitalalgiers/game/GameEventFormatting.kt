package com.menouer.capitalalgiers.game

import com.menouer.economy_data.BoardConfig
import com.menouer.economy_data.Deck
import com.menouer.rules_engine.model.AssetId
import com.menouer.rules_engine.model.GameEvent
import com.menouer.rules_engine.model.JailReason
import com.menouer.rules_engine.model.JailReleaseMethod
import com.menouer.rules_engine.model.PlayerId

/**
 * One short line per [GameEvent], for the turn panel's event log. Purely
 * cosmetic — never used for anything the engine itself decides. Uses
 * BoardSpace.developerName (Latin) for asset labels, consistent with
 * BoardScreen and with M3's RTL-out-of-scope decision (SRS.md §5/§6).
 * Returns null for events that are pure phase markers with nothing new to
 * say (currently just LandingResolved).
 */
fun GameEvent.describe(config: BoardConfig, playerNames: Map<PlayerId, String>): String? {
    fun name(id: PlayerId) = playerNames[id] ?: id
    fun asset(id: AssetId) = assetDisplayName(config, id)

    return when (this) {
        is GameEvent.DiceRolled ->
            "${name(playerId)} rolled ${roll.die1} + ${roll.die2} = ${roll.total}" + if (roll.isDouble) " (double)" else ""

        is GameEvent.PlayerMoved ->
            "${name(playerId)} moved from $fromPosition to $toPosition" + if (passedGo) " (passing GO)" else ""

        is GameEvent.GoCollected ->
            "${name(playerId)} collected $amount \u062F\u062C for GO"

        is GameEvent.SentToJail ->
            "${name(playerId)} was sent to jail (${jailReasonLabel(reason)})"

        is GameEvent.LandingResolved -> null

        is GameEvent.TaxPaid ->
            "${name(playerId)} paid $amount \u062F\u062C in tax"

        is GameEvent.RentPaid ->
            "${name(payerId)} paid ${name(ownerId)} $amount \u062F\u062C rent for ${asset(assetId)}"

        is GameEvent.PurchaseDecisionPending ->
            "${name(playerId)} can buy ${asset(assetId)}"

        is GameEvent.CardDrawn ->
            "${name(playerId)} drew a ${deckLabel(deck)} card"

        is GameEvent.CardBankPayout ->
            "${name(playerId)} collected $amount \u062F\u062C from the Bank"

        is GameEvent.CardBankCharge ->
            "${name(playerId)} paid $amount \u062F\u062C to the Bank"

        is GameEvent.CardPlayerToPlayerPayment ->
            "${name(fromPlayerId)} paid ${name(toPlayerId)} $amount \u062F\u062C"

        is GameEvent.GetOutOfJailCardReceived ->
            "${name(playerId)} received a Get Out of Jail Free card"

        is GameEvent.JailRollFailed ->
            "${name(playerId)} failed to roll doubles (attempt $jailTurnsUsedNow of 2)"

        is GameEvent.JailFinePaid ->
            "${name(playerId)} paid the $amount \u062F\u062C jail fine" + if (forced) " (forced, turn 3)" else ""

        is GameEvent.GetOutOfJailCardUsed ->
            "${name(playerId)} used a Get Out of Jail Free card"

        is GameEvent.ReleasedFromJail ->
            "${name(playerId)} left jail (${jailReleaseLabel(method)})"

        is GameEvent.HouseBuilt ->
            "${name(playerId)} built a house on ${asset(assetId)} ($housesNow now)"

        is GameEvent.HotelBuilt ->
            "${name(playerId)} built a hotel on ${asset(assetId)}"

        is GameEvent.HouseSold ->
            "${name(playerId)} sold a house on ${asset(assetId)} ($housesNow left)"

        is GameEvent.HotelSold ->
            "${name(playerId)} sold the hotel on ${asset(assetId)} (back to 4 houses)"

        is GameEvent.MortgagePlaced ->
            "${name(playerId)} mortgaged ${asset(assetId)} for $amount \u062F\u062C"

        is GameEvent.MortgageLifted ->
            "${name(playerId)} unmortgaged ${asset(assetId)} for $amountPaid \u062F\u062C"

        is GameEvent.TradeProposed ->
            "${name(fromPlayerId)} proposed a trade to ${name(toPlayerId)}"

        is GameEvent.TradeResolved ->
            "${name(toPlayerId)} " + (if (accepted) "accepted" else "declined") + " the trade from ${name(fromPlayerId)}"

        is GameEvent.PlayerBankrupted ->
            "${name(playerId)} went bankrupt" + (creditorId?.let { " (owed ${name(it)})" } ?: " (owed the Bank)")

        is GameEvent.AssetPurchased ->
            "${name(playerId)} bought ${asset(assetId)} for $price \u062F\u062C"

        is GameEvent.AuctionStarted ->
            "Auction started for ${asset(assetId)} (minimum bid $minimumBid \u062F\u062C)"

        is GameEvent.AuctionBidPlaced ->
            "${name(playerId)} bid $amount \u062F\u062C on ${asset(assetId)}"

        is GameEvent.AuctionPassed ->
            "${name(playerId)} passed on ${asset(assetId)}"

        is GameEvent.AuctionWon ->
            "${name(playerId)} won ${asset(assetId)} at auction for $amount \u062F\u062C"

        is GameEvent.AuctionEndedWithNoBids ->
            "No one bid on ${asset(assetId)} — it stays unowned"

        is GameEvent.BonusRollGranted ->
            "${name(playerId)} rolled a double and gets another roll"

        is GameEvent.TurnChanged ->
            "It's now ${name(newActivePlayerId)}'s turn"

        is GameEvent.GameEnded ->
            "The match has ended"
    }
}

private fun assetDisplayName(config: BoardConfig, assetId: AssetId): String =
    config.spaces.firstOrNull { it.assetId == assetId }?.developerName ?: assetId

private fun jailReasonLabel(reason: JailReason): String = when (reason) {
    JailReason.LANDED_ON_GO_TO_JAIL_SPACE -> "landed on Go To Jail"
    JailReason.THREE_CONSECUTIVE_DOUBLES -> "three doubles in a row"
    JailReason.CARD_EFFECT -> "a card"
}

private fun jailReleaseLabel(method: JailReleaseMethod): String = when (method) {
    JailReleaseMethod.DOUBLES_ATTEMPT -> "rolled doubles"
    JailReleaseMethod.FORCED_TURN_THREE -> "forced turn 3"
}

private fun deckLabel(deck: Deck): String = when (deck) {
    Deck.CHANCE -> "Chance"
    Deck.CAPITAL_CHEST -> "Community Chest"
}
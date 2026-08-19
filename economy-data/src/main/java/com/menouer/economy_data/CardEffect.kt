package com.menouer.economy_data

/**
 * Which deck a card belongs to (Cards.md §1/§2). Decks are never merged
 * (GameRules.md §9) — a card always returns to its own deck's bottom.
 */
enum class Deck {
    CHANCE,
    CAPITAL_CHEST
}

/**
 * The mechanical effect of a card, per Cards.md's "Effect Type Reference".
 * Modeled as a sealed class (rather than an effectType string + generic
 * parameter map) so rules-engine gets compile-time exhaustiveness checking
 * when it resolves a drawn card.
 */
sealed class CardEffect {
    /** Move to a fixed board index; collect GO if the move passes/lands on it. */
    data class MoveToPosition(val target: Int) : CardEffect()

    /** Move forward/back N spaces. Backward moves never collect GO (Cards.md §3). */
    data class MoveRelative(val delta: Int) : CardEffect()

    /** Move to the nearest station; if owned, rent is multiplied by rentMultiplier. */
    data class MoveToNearestStation(val rentMultiplier: Int) : CardEffect()

    /** Move to the nearest utility; if owned, rent uses multiplierOverride * dice total. */
    data class MoveToNearestUtility(val multiplierOverride: Int) : CardEffect()

    data class CollectFromBank(val amount: Int) : CardEffect()
    data class PayToBank(val amount: Int) : CardEffect()
    data class PayEachPlayer(val amount: Int) : CardEffect()
    data class CollectFromEachPlayer(val amount: Int) : CardEffect()

    /** Sends the player directly to jail; never pays GO even if the route crosses it. */
    object GoToJail : CardEffect()

    /** Retained by the player until used/traded, then returns to its own deck's bottom. */
    object GetOutOfJailFree : CardEffect()

    /** Pay perHouse * houseCount + perHotel * hotelCount across all owned buildings. */
    data class PropertyRepairs(val perHouse: Int, val perHotel: Int) : CardEffect()
}

/**
 * One card definition, per BoardEconomy.md §9's schema and Cards.md's tables.
 */
data class CardDef(
    val id: String,
    val deck: Deck,
    val textArabic: String,
    val effect: CardEffect
)

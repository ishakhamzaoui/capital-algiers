package com.menouer.economy_data

/**
 * Static economy data for one of the 2 utilities, per BoardEconomy.md §7.
 * The single/both-utility dice multipliers are global constants (see
 * EconomyConstants), not per-utility, since they only depend on how many
 * utilities the same owner holds.
 */
data class UtilityConfig(
    val id: String,
    val boardPosition: Int,
    val nameArabic: String,
    val purchasePrice: Int,
    val mortgageValue: Int
)

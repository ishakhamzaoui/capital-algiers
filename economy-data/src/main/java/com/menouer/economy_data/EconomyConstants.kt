package com.menouer.economy_data

/**
 * Global economy constants, per BoardEconomy.md §2, §7, §8.
 * mortgageInterestRate and buildingResaleRate are fractions (0.10 = 10%, 0.50 = 50%).
 */
data class EconomyConstants(
    val startingMoney: Int,
    val goReward: Int,
    val jailFine: Int,
    val mortgageInterestRate: Double,
    val buildingResaleRate: Double,
    val totalHouses: Int,
    val totalHotels: Int,
    val auctionMinimumBid: Int,
    val auctionMinimumIncrement: Int,
    val singleUtilityMultiplier: Int,
    val bothUtilitiesMultiplier: Int,
    val incomeTax: Int,
    val luxuryTax: Int
)

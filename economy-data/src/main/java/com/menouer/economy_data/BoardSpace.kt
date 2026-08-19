package com.menouer.economy_data

/**
 * One of the 40 authoritative board positions (BoardEconomy.md §4).
 *
 * [assetId] links a PROPERTY / STATION / UTILITY space to the matching
 * [PropertyConfig.id] / [StationConfig.id] / [UtilityConfig.id]. It is null
 * for non-purchasable spaces (GO, taxes, cards, jail, free parking, go-to-jail).
 */
data class BoardSpace(
    val index: Int,
    val type: SpaceType,
    val nameArabic: String,
    val developerName: String,
    val assetId: String? = null
)

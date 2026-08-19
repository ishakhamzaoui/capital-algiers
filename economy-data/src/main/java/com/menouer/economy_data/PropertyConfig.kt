package com.menouer.economy_data

/**
 * Static economy data for one purchasable property, per BoardEconomy.md §5's schema.
 * mortgageValue is always 50% of purchasePrice, but is stored explicitly rather than
 * derived, since BoardEconomy.md is the single source of truth for the number itself.
 */
data class PropertyConfig(
    val id: String,
    val boardPosition: Int,
    val nameArabic: String,
    val nameLatin: String,
    val group: PropertyGroup,
    val purchasePrice: Int,
    val mortgageValue: Int,
    val baseRent: Int,
    val monopolyRent: Int,
    val rent1House: Int,
    val rent2Houses: Int,
    val rent3Houses: Int,
    val rent4Houses: Int,
    val hotelRent: Int,
    val houseCost: Int
)

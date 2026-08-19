package com.menouer.economy_data

/**
 * Static economy data for one of the 4 transport stations, per BoardEconomy.md §6.
 * All four stations share identical pricing/rent in Version 1, but each is still
 * modeled as its own asset since ownership/mortgage state is per-station.
 */
data class StationConfig(
    val id: String,
    val boardPosition: Int,
    val nameArabic: String,
    val purchasePrice: Int,
    val mortgageValue: Int,
    val rent1Station: Int,
    val rent2Stations: Int,
    val rent3Stations: Int,
    val rent4Stations: Int
)

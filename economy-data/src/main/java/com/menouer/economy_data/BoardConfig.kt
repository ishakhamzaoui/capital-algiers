package com.menouer.economy_data

/**
 * The complete, validated economy configuration consumed by rules-engine.
 *
 * In M1 this is populated in-memory by [SampleEconomyData] with the real,
 * finalized numbers from BoardEconomy.md / Cards.md. In M2 the same shape
 * will be populated by a JSON loader + startup validator instead — nothing
 * in rules-engine should need to change when that swap happens.
 */
data class BoardConfig(
    val constants: EconomyConstants,
    val spaces: List<BoardSpace>,
    val properties: List<PropertyConfig>,
    val stations: List<StationConfig>,
    val utilities: List<UtilityConfig>,
    val chanceDeck: List<CardDef>,
    val chestDeck: List<CardDef>
) {
    val propertiesById: Map<String, PropertyConfig> by lazy { properties.associateBy { it.id } }
    val stationsById: Map<String, StationConfig> by lazy { stations.associateBy { it.id } }
    val utilitiesById: Map<String, UtilityConfig> by lazy { utilities.associateBy { it.id } }
    val spacesByIndex: Map<Int, BoardSpace> by lazy { spaces.associateBy { it.index } }
    val cardsById: Map<String, CardDef> by lazy { (chanceDeck + chestDeck).associateBy { it.id } }

    /** All 22 properties belonging to a given group, useful for monopoly/build checks. */
    fun propertiesInGroup(group: PropertyGroup): List<PropertyConfig> =
        properties.filter { it.group == group }
}
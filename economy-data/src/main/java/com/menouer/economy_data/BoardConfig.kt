package com.menouer.economy_data

/**
 * The complete, validated economy configuration consumed by rules-engine.
 *
 * Populated by [EconomyConfigLoader] reading economy-config.json (the single
 * source of truth transcribed from BoardEconomy.md / Cards.md), and checked
 * by [EconomyConfigValidator] before use. This shape has been stable since
 * M1 — M2 replaced the *source* of the data (JSON + loader/validator instead
 * of the hand-transcribed SampleEconomyData fixture) without changing what
 * rules-engine consumes.
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
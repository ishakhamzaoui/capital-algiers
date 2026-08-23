package com.menouer.economy_data

/**
 * Validates a loaded [BoardConfig] against the structural invariants
 * DevelopmentRoadmap.md's M2 milestone calls out explicitly: 40 positions
 * present, 22 properties in 8 groups, house/hotel supply totals match, and
 * exactly 2 Get-Out-of-Jail-Free cards across both decks — plus a few
 * additional cross-reference checks (asset <-> space consistency, duplicate
 * ids) that the same "don't silently misbehave mid-game" exit criterion
 * implies even though they aren't spelled out as separate bullet points.
 *
 * This is a pure post-parse check: it assumes [EconomyConfigLoader] already
 * produced a well-formed [BoardConfig] (right types, no missing required
 * card parameters) and only checks whether the *content* is internally
 * consistent and matches the finalized shape from BoardEconomy.md / Cards.md.
 *
 * All violations are collected and reported together in one
 * [EconomyConfigException], rather than failing on the first problem found,
 * so a corrupted config file surfaces every issue at once instead of
 * requiring several fix-rerun cycles.
 */
object EconomyConfigValidator {

    const val EXPECTED_SPACE_COUNT = 40
    const val EXPECTED_PROPERTY_COUNT = 22
    const val EXPECTED_GROUP_COUNT = 8
    const val EXPECTED_STATION_COUNT = 4
    const val EXPECTED_UTILITY_COUNT = 2
    const val EXPECTED_CARDS_PER_DECK = 16
    const val EXPECTED_GOOJF_CARD_COUNT = 2

    // GameRules.md §15 "Building Supply" — Version 1's fixed Bank inventory.
    const val EXPECTED_TOTAL_HOUSES = 32
    const val EXPECTED_TOTAL_HOTELS = 12

    /**
     * Validates [config]. Returns normally if every check passes. Throws
     * [EconomyConfigException] with every violation found (not just the
     * first) if anything is wrong. [sourceLabel] is only used to identify
     * the source in the exception message.
     */
    fun validate(config: BoardConfig, sourceLabel: String = "<config>") {
        val errors = mutableListOf<String>()

        validateSpaces(config, errors)
        validateProperties(config, errors)
        validateStations(config, errors)
        validateUtilities(config, errors)
        validateCards(config, errors)
        validateAssetCrossReferences(config, errors)
        validateBankInventory(config, errors)

        if (errors.isNotEmpty()) {
            throw EconomyConfigException(
                "Economy config validation failed for '$sourceLabel' with ${errors.size} " +
                        "error(s):\n" + errors.joinToString("\n") { "  - $it" }
            )
        }
    }

    // --- Spaces --------------------------------------------------------------

    private fun validateSpaces(config: BoardConfig, errors: MutableList<String>) {
        if (config.spaces.size != EXPECTED_SPACE_COUNT) {
            errors += "Expected $EXPECTED_SPACE_COUNT board spaces, found ${config.spaces.size}."
        }

        val expectedIndices = (0 until EXPECTED_SPACE_COUNT).toSet()
        val actualIndices = config.spaces.map { it.index }

        val missing = expectedIndices - actualIndices.toSet()
        if (missing.isNotEmpty()) {
            errors += "Missing board space index(es): ${missing.sorted().joinToString()}."
        }

        val outOfRange = actualIndices.toSet() - expectedIndices
        if (outOfRange.isNotEmpty()) {
            errors += "Board space index(es) outside the valid 0..${EXPECTED_SPACE_COUNT - 1} range: " +
                    outOfRange.sorted().joinToString() + "."
        }

        val duplicateIndices = actualIndices.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIndices.isNotEmpty()) {
            errors += "Duplicate board space index(es): ${duplicateIndices.sorted().joinToString()}."
        }
    }

    // --- Properties ------------------------------------------------------------

    private fun validateProperties(config: BoardConfig, errors: MutableList<String>) {
        if (config.properties.size != EXPECTED_PROPERTY_COUNT) {
            errors += "Expected $EXPECTED_PROPERTY_COUNT properties, found ${config.properties.size}."
        }

        val groupsPresent = config.properties.map { it.group }.toSet()
        val missingGroups = PropertyGroup.entries.toSet() - groupsPresent
        if (missingGroups.isNotEmpty()) {
            errors += "Missing propert(y/ies) for group(s): ${missingGroups.joinToString { it.name }}."
        }

        val duplicateIds = config.properties.map { it.id }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            errors += "Duplicate property id(s): ${duplicateIds.sorted().joinToString()}."
        }

        // BoardEconomy.md §5: "mortgageValue is 50% of purchasePrice for every property."
        config.properties.forEach { property ->
            if (property.mortgageValue != property.purchasePrice / 2) {
                errors += "Property '${property.id}' has mortgageValue ${property.mortgageValue}, " +
                        "expected 50% of purchasePrice ${property.purchasePrice} " +
                        "(= ${property.purchasePrice / 2})."
            }
        }
    }

    // --- Stations / Utilities ---------------------------------------------------

    private fun validateStations(config: BoardConfig, errors: MutableList<String>) {
        if (config.stations.size != EXPECTED_STATION_COUNT) {
            errors += "Expected $EXPECTED_STATION_COUNT stations, found ${config.stations.size}."
        }
        val duplicateIds = config.stations.map { it.id }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            errors += "Duplicate station id(s): ${duplicateIds.sorted().joinToString()}."
        }
    }

    private fun validateUtilities(config: BoardConfig, errors: MutableList<String>) {
        if (config.utilities.size != EXPECTED_UTILITY_COUNT) {
            errors += "Expected $EXPECTED_UTILITY_COUNT utilities, found ${config.utilities.size}."
        }
        val duplicateIds = config.utilities.map { it.id }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            errors += "Duplicate utility id(s): ${duplicateIds.sorted().joinToString()}."
        }
    }

    // --- Cards -----------------------------------------------------------------

    private fun validateCards(config: BoardConfig, errors: MutableList<String>) {
        if (config.chanceDeck.size != EXPECTED_CARDS_PER_DECK) {
            errors += "Expected $EXPECTED_CARDS_PER_DECK Chance cards, found ${config.chanceDeck.size}."
        }
        if (config.chestDeck.size != EXPECTED_CARDS_PER_DECK) {
            errors += "Expected $EXPECTED_CARDS_PER_DECK Capital Chest cards, found ${config.chestDeck.size}."
        }

        val allCards = config.chanceDeck + config.chestDeck
        val duplicateIds = allCards.map { it.id }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            errors += "Duplicate card id(s) across both decks: ${duplicateIds.sorted().joinToString()}."
        }

        // Cards.md: "Each deck contains exactly one Get Out of Jail Free card
        // (2 total across both decks)."
        val goojfCount = allCards.count { it.effect == CardEffect.GetOutOfJailFree }
        if (goojfCount != EXPECTED_GOOJF_CARD_COUNT) {
            errors += "Expected exactly $EXPECTED_GOOJF_CARD_COUNT GetOutOfJailFree card(s) " +
                    "across both decks, found $goojfCount."
        }

        // Each individual deck should also carry exactly one, per Cards.md.
        val chanceGoojf = config.chanceDeck.count { it.effect == CardEffect.GetOutOfJailFree }
        if (chanceGoojf != 1) {
            errors += "Expected exactly 1 GetOutOfJailFree card in the Chance deck, found $chanceGoojf."
        }
        val chestGoojf = config.chestDeck.count { it.effect == CardEffect.GetOutOfJailFree }
        if (chestGoojf != 1) {
            errors += "Expected exactly 1 GetOutOfJailFree card in the Capital Chest deck, found $chestGoojf."
        }
    }

    // --- Asset <-> Space cross-references ---------------------------------------

    private fun validateAssetCrossReferences(config: BoardConfig, errors: MutableList<String>) {
        val referencedPropertyIds = mutableSetOf<String>()
        val referencedStationIds = mutableSetOf<String>()
        val referencedUtilityIds = mutableSetOf<String>()

        config.spaces.forEach { space ->
            when (space.type) {
                SpaceType.PROPERTY -> {
                    val assetId = space.assetId
                    if (assetId == null) {
                        errors += "Board space ${space.index} (${space.developerName}) is type PROPERTY " +
                                "but has no assetId."
                    } else {
                        referencedPropertyIds += assetId
                        val property = config.propertiesById[assetId]
                        if (property == null) {
                            errors += "Board space ${space.index} references property '$assetId', " +
                                    "which does not exist in the properties list."
                        } else if (property.boardPosition != space.index) {
                            errors += "Property '$assetId' has boardPosition ${property.boardPosition}, " +
                                    "but is referenced from board space index ${space.index}."
                        }
                    }
                }
                SpaceType.STATION -> {
                    val assetId = space.assetId
                    if (assetId == null) {
                        errors += "Board space ${space.index} (${space.developerName}) is type STATION " +
                                "but has no assetId."
                    } else {
                        referencedStationIds += assetId
                        val station = config.stationsById[assetId]
                        if (station == null) {
                            errors += "Board space ${space.index} references station '$assetId', " +
                                    "which does not exist in the stations list."
                        } else if (station.boardPosition != space.index) {
                            errors += "Station '$assetId' has boardPosition ${station.boardPosition}, " +
                                    "but is referenced from board space index ${space.index}."
                        }
                    }
                }
                SpaceType.UTILITY -> {
                    val assetId = space.assetId
                    if (assetId == null) {
                        errors += "Board space ${space.index} (${space.developerName}) is type UTILITY " +
                                "but has no assetId."
                    } else {
                        referencedUtilityIds += assetId
                        val utility = config.utilitiesById[assetId]
                        if (utility == null) {
                            errors += "Board space ${space.index} references utility '$assetId', " +
                                    "which does not exist in the utilities list."
                        } else if (utility.boardPosition != space.index) {
                            errors += "Utility '$assetId' has boardPosition ${utility.boardPosition}, " +
                                    "but is referenced from board space index ${space.index}."
                        }
                    }
                }
                else -> {
                    if (space.assetId != null) {
                        errors += "Board space ${space.index} (${space.developerName}) is type " +
                                "${space.type} and should not have an assetId, but has '${space.assetId}'."
                    }
                }
            }
        }

        // Reverse direction: every configured asset must be reachable from some space.
        val orphanedProperties = config.properties.map { it.id }.toSet() - referencedPropertyIds
        if (orphanedProperties.isNotEmpty()) {
            errors += "Propert(y/ies) not referenced by any board space: " +
                    orphanedProperties.sorted().joinToString() + "."
        }
        val orphanedStations = config.stations.map { it.id }.toSet() - referencedStationIds
        if (orphanedStations.isNotEmpty()) {
            errors += "Station(s) not referenced by any board space: " +
                    orphanedStations.sorted().joinToString() + "."
        }
        val orphanedUtilities = config.utilities.map { it.id }.toSet() - referencedUtilityIds
        if (orphanedUtilities.isNotEmpty()) {
            errors += "Utilit(y/ies) not referenced by any board space: " +
                    orphanedUtilities.sorted().joinToString() + "."
        }
    }

    // --- Bank inventory ----------------------------------------------------------

    private fun validateBankInventory(config: BoardConfig, errors: MutableList<String>) {
        if (config.constants.totalHouses != EXPECTED_TOTAL_HOUSES) {
            errors += "Expected totalHouses = $EXPECTED_TOTAL_HOUSES (GameRules.md §15), " +
                    "found ${config.constants.totalHouses}."
        }
        if (config.constants.totalHotels != EXPECTED_TOTAL_HOTELS) {
            errors += "Expected totalHotels = $EXPECTED_TOTAL_HOTELS (GameRules.md §15), " +
                    "found ${config.constants.totalHotels}."
        }
    }
}
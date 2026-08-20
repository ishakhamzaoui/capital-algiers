package com.menouer.rules_engine

import com.menouer.economy_data.BoardConfig
import com.menouer.economy_data.PropertyConfig
import com.menouer.rules_engine.model.AssetId
import com.menouer.rules_engine.model.AssetState
import com.menouer.rules_engine.model.PlayerId

/**
 * Standard rent computation, per TechnicalSpecification.md §4 and GameRules.md §8
 * (including the Monopoly Rent Disqualification by Mortgage rule).
 *
 * This covers *ordinary* landings only. Two Chance card effects (CH04 "double the
 * usual station rent" and CH05 "10x dice total regardless of ownership count")
 * override the normal owned-station/owned-utility formulas — those overrides are
 * applied in RulesEngineImpl's card resolution, not here, so this object stays a
 * faithful, single-purpose implementation of the base §8 rule that a dedicated
 * RentCalculationTest can verify in isolation.
 */
object RentCalculator {

    /**
     * Rent owed for [assetId], assuming it is confirmed owned by someone other than
     * the landing player (callers are responsible for that check — this always
     * returns a rent amount, including 0 for a mortgaged asset, never "no rent
     * because it's unowned/self-owned", since it doesn't know who's landing).
     */
    fun rentFor(config: BoardConfig, assets: Map<AssetId, AssetState>, assetId: AssetId, diceTotal: Int): Int {
        val asset = assets.getValue(assetId)
        if (asset.mortgaged) return 0
        val ownerId = asset.ownerId ?: return 0

        config.propertiesById[assetId]?.let { return propertyRent(config, assets, it, ownerId) }
        config.stationsById[assetId]?.let { return stationRent(config, assets, ownerId) }
        config.utilitiesById[assetId]?.let { return utilityRent(config, assets, ownerId, diceTotal) }
        error("unknown asset id: $assetId")
    }

    private fun propertyRent(
        config: BoardConfig,
        assets: Map<AssetId, AssetState>,
        propertyConfig: PropertyConfig,
        ownerId: PlayerId
    ): Int {
        val asset = assets.getValue(propertyConfig.id)

        if (asset.hasHotel) return propertyConfig.hotelRent
        if (asset.houses > 0) {
            return when (asset.houses) {
                1 -> propertyConfig.rent1House
                2 -> propertyConfig.rent2Houses
                3 -> propertyConfig.rent3Houses
                4 -> propertyConfig.rent4Houses
                else -> error("invalid house count ${asset.houses} on ${propertyConfig.id}")
            }
        }

        // Unimproved: monopoly rent only if the owner holds every property in the
        // group AND no property in that group is mortgaged (GameRules.md §8).
        val groupAssets = config.propertiesInGroup(propertyConfig.group).map { assets.getValue(it.id) }
        val groupDisqualifiedByMortgage = groupAssets.any { it.mortgaged }
        val ownsEntireGroup = groupAssets.all { it.ownerId == ownerId }

        return if (ownsEntireGroup && !groupDisqualifiedByMortgage) propertyConfig.monopolyRent else propertyConfig.baseRent
    }

    private fun stationRent(config: BoardConfig, assets: Map<AssetId, AssetState>, ownerId: PlayerId): Int {
        val ownedCount = config.stations.count { assets.getValue(it.id).ownerId == ownerId }
        val rentTable = config.stations.first() // all 4 stations share identical rent tiers (BoardEconomy.md §6)
        return when (ownedCount) {
            1 -> rentTable.rent1Station
            2 -> rentTable.rent2Stations
            3 -> rentTable.rent3Stations
            else -> rentTable.rent4Stations
        }
    }

    private fun utilityRent(config: BoardConfig, assets: Map<AssetId, AssetState>, ownerId: PlayerId, diceTotal: Int): Int {
        val ownedCount = config.utilities.count { assets.getValue(it.id).ownerId == ownerId }
        val multiplier = if (ownedCount == 2) config.constants.bothUtilitiesMultiplier else config.constants.singleUtilityMultiplier
        return diceTotal * multiplier
    }
}
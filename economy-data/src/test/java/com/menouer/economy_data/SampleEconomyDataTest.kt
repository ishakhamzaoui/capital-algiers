package com.menouer.economy_data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity checks on the hand-transcribed fixture data, mirroring the startup
 * validation checks M2 will later perform on the real JSON loader
 * (TechnicalSpecification.md §9): 40 positions, 22 properties in 8 groups,
 * house/hotel supply totals, exactly 2 GOOJF cards across both decks.
 */
class SampleEconomyDataTest {

    private val config = SampleEconomyData.boardConfig

    @Test
    fun `board has exactly 40 positions`() {
        assertEquals(40, config.spaces.size)
        assertEquals((0..39).toSet(), config.spaces.map { it.index }.toSet())
    }

    @Test
    fun `there are exactly 22 properties across 8 groups`() {
        assertEquals(22, config.properties.size)
        assertEquals(PropertyGroup.entries.toSet(), config.properties.map { it.group }.toSet())
    }

    @Test
    fun `there are exactly 4 stations and 2 utilities`() {
        assertEquals(4, config.stations.size)
        assertEquals(2, config.utilities.size)
    }

    @Test
    fun `mortgage value is always 50 percent of purchase price for properties`() {
        config.properties.forEach {
            assertEquals("mortgage mismatch for ${it.id}", it.purchasePrice / 2, it.mortgageValue)
        }
    }

    @Test
    fun `every purchasable space has a matching asset config`() {
        config.spaces.filter { it.type == SpaceType.PROPERTY }.forEach {
            assertTrue("missing property config for ${it.assetId}", config.propertiesById.containsKey(it.assetId))
        }
        config.spaces.filter { it.type == SpaceType.STATION }.forEach {
            assertTrue("missing station config for ${it.assetId}", config.stationsById.containsKey(it.assetId))
        }
        config.spaces.filter { it.type == SpaceType.UTILITY }.forEach {
            assertTrue("missing utility config for ${it.assetId}", config.utilitiesById.containsKey(it.assetId))
        }
    }

    @Test
    fun `each deck has exactly 16 cards and exactly one GetOutOfJailFree card`() {
        assertEquals(16, config.chanceDeck.size)
        assertEquals(16, config.chestDeck.size)
        assertEquals(1, config.chanceDeck.count { it.effect == CardEffect.GetOutOfJailFree })
        assertEquals(1, config.chestDeck.count { it.effect == CardEffect.GetOutOfJailFree })
    }

    @Test
    fun `bank inventory constants match BoardEconomy`() {
        assertEquals(32, config.constants.totalHouses)
        assertEquals(12, config.constants.totalHotels)
    }
}
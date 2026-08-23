package com.menouer.economy_data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [EconomyConfigLoader]: that it correctly loads the real bundled
 * economy-config.json into the same [BoardConfig] shape rules-engine already
 * consumes, and that malformed input fails loudly with a specific message
 * rather than producing a partially-wrong config.
 *
 * Structural rules (40 spaces, 22 properties/8 groups, house/hotel supply
 * totals, exactly 2 GOOJF cards, asset cross-references) belong to the
 * dedicated validator added later in M2 — this file only covers what the
 * loader itself is responsible for: parsing and DTO -> domain mapping.
 */
class EconomyConfigLoaderTest {

    // --- Happy path against the real bundled resource ----------------------

    @Test
    fun `loads the default bundled economy-config json`() {
        val config = EconomyConfigLoader.loadDefault()

        assertEquals(40, config.spaces.size)
        assertEquals(22, config.properties.size)
        assertEquals(4, config.stations.size)
        assertEquals(2, config.utilities.size)
        assertEquals(16, config.chanceDeck.size)
        assertEquals(16, config.chestDeck.size)
    }

    @Test
    fun `loaded config matches known BoardEconomy md values`() {
        val config = EconomyConfigLoader.loadDefault()

        val dergana = config.propertiesById.getValue("Dergana")
        assertEquals(6_000, dergana.purchasePrice)
        assertEquals(3_000, dergana.mortgageValue)
        assertEquals(PropertyGroup.BROWN, dergana.group)

        assertEquals(150_000, config.constants.startingMoney)
        assertEquals(20_000, config.constants.goReward)
    }

    @Test
    fun `loaded cards carry the correct deck and effect`() {
        val config = EconomyConfigLoader.loadDefault()

        val ch01 = config.cardsById.getValue("CH01")
        assertEquals(Deck.CHANCE, ch01.deck)
        assertEquals(CardEffect.MoveToPosition(0), ch01.effect)

        val cc16 = config.cardsById.getValue("CC16")
        assertEquals(Deck.CAPITAL_CHEST, cc16.deck)
        assertEquals(CardEffect.MoveRelative(-3), cc16.effect)

        val ch12 = config.cardsById.getValue("CH12")
        assertEquals(CardEffect.PropertyRepairs(perHouse = 400, perHotel = 1_150), ch12.effect)
    }

    // --- Error paths ---------------------------------------------------------

    @Test
    fun `missing resource throws a clear EconomyConfigException`() {
        val exception = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigLoader.loadFromResource("does-not-exist.json")
        }
        assertTrue(exception.message!!.contains("does-not-exist.json"))
    }

    @Test
    fun `malformed JSON syntax throws a clear EconomyConfigException`() {
        val brokenJson = validMinimalConfigJson().trim().dropLast(1) // truncate final closing brace

        val exception = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigLoader.loadFromJsonText(brokenJson, sourceLabel = "test-fixture")
        }
        assertTrue(exception.message!!.contains("test-fixture"))
    }

    @Test
    fun `unknown space type throws a clear EconomyConfigException naming the bad value`() {
        val badJson = validMinimalConfigJson().replace("\"type\": \"GO\"", "\"type\": \"NOT_A_REAL_TYPE\"")

        val exception = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigLoader.loadFromJsonText(badJson, sourceLabel = "test-fixture")
        }
        assertTrue(exception.message!!.contains("NOT_A_REAL_TYPE"))
    }

    @Test
    fun `unknown property group throws a clear EconomyConfigException naming the bad value`() {
        val badJson = validMinimalConfigJson().replace("\"group\": \"BROWN\"", "\"group\": \"MAUVE\"")

        val exception = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigLoader.loadFromJsonText(badJson, sourceLabel = "test-fixture")
        }
        assertTrue(exception.message!!.contains("MAUVE"))
    }

    @Test
    fun `card missing a required parameter throws a clear EconomyConfigException naming the card`() {
        val badJson = validMinimalConfigJson()
            .replace("\"effectType\": \"MoveToPosition\", \"target\": 0", "\"effectType\": \"MoveToPosition\"")

        val exception = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigLoader.loadFromJsonText(badJson, sourceLabel = "test-fixture")
        }
        assertTrue(exception.message!!.contains("CH01"))
        assertTrue(exception.message!!.contains("target"))
    }

    @Test
    fun `card with unknown effectType throws a clear EconomyConfigException`() {
        val badJson = validMinimalConfigJson().replace("\"effectType\": \"MoveToPosition\"", "\"effectType\": \"TeleportRandomly\"")

        val exception = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigLoader.loadFromJsonText(badJson, sourceLabel = "test-fixture")
        }
        assertTrue(exception.message!!.contains("TeleportRandomly"))
    }

    // --- Minimal fixture used by the error-path tests above ------------------

    /**
     * A minimal-but-well-formed config (1 of each entity) used as a base for
     * the corruption tests above via targeted string replacement, so each
     * test isolates exactly one broken field rather than depending on the
     * full 40-space real file.
     */
    private fun validMinimalConfigJson(): String = """
        {
          "constants": {
            "startingMoney": 150000, "goReward": 20000, "jailFine": 5000,
            "mortgageInterestRate": 0.10, "buildingResaleRate": 0.50,
            "totalHouses": 32, "totalHotels": 12,
            "auctionMinimumBid": 1000, "auctionMinimumIncrement": 500,
            "singleUtilityMultiplier": 4, "bothUtilitiesMultiplier": 10,
            "incomeTax": 20000, "luxuryTax": 10000
          },
          "spaces": [
            { "index": 0, "type": "GO", "nameArabic": "GO", "developerName": "GO" }
          ],
          "properties": [
            { "id": "Dergana", "boardPosition": 1, "nameArabic": "درڨانة", "nameLatin": "Dergana",
              "group": "BROWN", "purchasePrice": 6000, "mortgageValue": 3000, "baseRent": 200,
              "monopolyRent": 400, "rent1House": 1000, "rent2Houses": 3000, "rent3Houses": 9000,
              "rent4Houses": 16000, "hotelRent": 25000, "houseCost": 5000 }
          ],
          "stations": [
            { "id": "AghaStation", "boardPosition": 5, "nameArabic": "محطة الجزائر – آغا",
              "purchasePrice": 20000, "mortgageValue": 10000, "rent1Station": 2500,
              "rent2Stations": 5000, "rent3Stations": 10000, "rent4Stations": 20000 }
          ],
          "utilities": [
            { "id": "Sonelgaz", "boardPosition": 12, "nameArabic": "سونلغاز",
              "purchasePrice": 15000, "mortgageValue": 7500 }
          ],
          "chanceDeck": [
            { "id": "CH01", "textArabic": "test", "effectType": "MoveToPosition", "target": 0 }
          ],
          "chestDeck": [
            { "id": "CC01", "textArabic": "test", "effectType": "CollectFromBank", "amount": 20000 }
          ]
        }
    """.trimIndent()
}
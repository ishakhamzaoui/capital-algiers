package com.menouer.economy_data

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [EconomyConfigValidator]. Each negative test starts from the
 * real, already-valid loaded config and breaks exactly one invariant via
 * `.copy(...)`, so a failure here always points at a real, isolated
 * validation gap rather than an artifact of a hand-built fixture.
 */
class EconomyConfigValidatorTest {

    private val validConfig = EconomyConfigLoader.loadDefault()

    @Test
    fun `the real bundled config passes validation`() {
        EconomyConfigValidator.validate(validConfig, sourceLabel = "economy-config.json")
        // No exception = pass.
    }

    @Test
    fun `wrong total space count is reported`() {
        val broken = validConfig.copy(spaces = validConfig.spaces.dropLast(1))

        val ex = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigValidator.validate(broken, "test")
        }
        assertTrue(ex.message!!.contains("Expected 40 board spaces, found 39"))
        assertTrue(ex.message!!.contains("Missing board space index"))
    }

    @Test
    fun `duplicate space index is reported`() {
        val broken = validConfig.copy(
            spaces = validConfig.spaces.map { if (it.index == 5) it.copy(index = 0) else it }
        )

        val ex = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigValidator.validate(broken, "test")
        }
        assertTrue(ex.message!!.contains("Duplicate board space index(es): 0"))
        assertTrue(ex.message!!.contains("Missing board space index(es): 5"))
    }

    @Test
    fun `wrong property count is reported`() {
        val broken = validConfig.copy(properties = validConfig.properties.dropLast(1))

        val ex = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigValidator.validate(broken, "test")
        }
        assertTrue(ex.message!!.contains("Expected 22 properties, found 21"))
    }

    @Test
    fun `missing property group is reported`() {
        val broken = validConfig.copy(
            properties = validConfig.properties.filter { it.group != PropertyGroup.DARK_BLUE }
        )

        val ex = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigValidator.validate(broken, "test")
        }
        assertTrue(ex.message!!.contains("Missing propert(y/ies) for group(s): DARK_BLUE"))
    }

    @Test
    fun `duplicate property id is reported`() {
        val dergana = validConfig.propertiesById.getValue("Dergana")
        val broken = validConfig.copy(properties = validConfig.properties + dergana)

        val ex = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigValidator.validate(broken, "test")
        }
        assertTrue(ex.message!!.contains("Duplicate property id(s): Dergana"))
    }

    @Test
    fun `mortgage value not 50 percent of purchase price is reported`() {
        val dergana = validConfig.propertiesById.getValue("Dergana")
        val broken = validConfig.copy(
            properties = validConfig.properties.map {
                if (it.id == "Dergana") it.copy(mortgageValue = it.mortgageValue + 1) else it
            }
        )

        val ex = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigValidator.validate(broken, "test")
        }
        assertTrue(ex.message!!.contains("Property 'Dergana' has mortgageValue ${dergana.mortgageValue + 1}"))
    }

    @Test
    fun `wrong station count is reported`() {
        val broken = validConfig.copy(stations = validConfig.stations.dropLast(1))

        val ex = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigValidator.validate(broken, "test")
        }
        assertTrue(ex.message!!.contains("Expected 4 stations, found 3"))
    }

    @Test
    fun `wrong utility count is reported`() {
        val broken = validConfig.copy(utilities = validConfig.utilities.dropLast(1))

        val ex = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigValidator.validate(broken, "test")
        }
        assertTrue(ex.message!!.contains("Expected 2 utilities, found 1"))
    }

    @Test
    fun `wrong deck size is reported`() {
        val broken = validConfig.copy(chanceDeck = validConfig.chanceDeck.dropLast(1))

        val ex = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigValidator.validate(broken, "test")
        }
        assertTrue(ex.message!!.contains("Expected 16 Chance cards, found 15"))
    }

    @Test
    fun `wrong total GetOutOfJailFree count is reported`() {
        // Turn a second Capital Chest card into a GOOJF card, so the deck now has 2.
        val broken = validConfig.copy(
            chestDeck = validConfig.chestDeck.map {
                if (it.id == "CC02") it.copy(effect = CardEffect.GetOutOfJailFree) else it
            }
        )

        val ex = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigValidator.validate(broken, "test")
        }
        assertTrue(ex.message!!.contains("Expected exactly 2 GetOutOfJailFree card(s) across both decks, found 3"))
        assertTrue(ex.message!!.contains("Expected exactly 1 GetOutOfJailFree card in the Capital Chest deck, found 2"))
    }

    @Test
    fun `space referencing a nonexistent property is reported`() {
        val broken = validConfig.copy(
            spaces = validConfig.spaces.map {
                if (it.index == 1) it.copy(assetId = "NotARealProperty") else it
            }
        )

        val ex = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigValidator.validate(broken, "test")
        }
        assertTrue(ex.message!!.contains("references property 'NotARealProperty', which does not exist"))
        assertTrue(ex.message!!.contains("Propert(y/ies) not referenced by any board space: Dergana"))
    }

    @Test
    fun `mismatched boardPosition between space and property is reported`() {
        val broken = validConfig.copy(
            properties = validConfig.properties.map {
                if (it.id == "Dergana") it.copy(boardPosition = 3) else it
            }
        )

        val ex = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigValidator.validate(broken, "test")
        }
        assertTrue(
            ex.message!!.contains("Property 'Dergana' has boardPosition 3, but is referenced from board space index 1")
        )
    }

    @Test
    fun `assetId present on a non-purchasable space is reported`() {
        val broken = validConfig.copy(
            spaces = validConfig.spaces.map {
                if (it.index == 0) it.copy(assetId = "Dergana") else it
            }
        )

        val ex = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigValidator.validate(broken, "test")
        }
        assertTrue(ex.message!!.contains("is type GO and should not have an assetId"))
    }

    @Test
    fun `wrong bank house and hotel totals are reported`() {
        val broken = validConfig.copy(
            constants = validConfig.constants.copy(totalHouses = 30, totalHotels = 10)
        )

        val ex = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigValidator.validate(broken, "test")
        }
        assertTrue(ex.message!!.contains("Expected totalHouses = 32"))
        assertTrue(ex.message!!.contains("Expected totalHotels = 12"))
    }

    @Test
    fun `multiple simultaneous problems are all reported together`() {
        val broken = validConfig.copy(
            stations = validConfig.stations.dropLast(1),
            constants = validConfig.constants.copy(totalHouses = 999)
        )

        val ex = assertThrows(EconomyConfigException::class.java) {
            EconomyConfigValidator.validate(broken, "test")
        }
        assertTrue(ex.message!!.contains("Expected 4 stations, found 3"))
        assertTrue(ex.message!!.contains("Expected totalHouses = 32"))
    }
}
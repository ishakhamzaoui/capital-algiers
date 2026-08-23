package com.menouer.economy_data

import kotlinx.serialization.Serializable

/**
 * Wire-format DTOs mirroring economy-config.json exactly. These are kept
 * separate from the pure domain models (BoardConfig, PropertyConfig, ...)
 * on purpose: the domain models stay free of serialization annotations and
 * untouched by M2, while these DTOs are allowed to be a little more
 * permissive (String instead of enum, nullable card parameters) so that
 * [EconomyConfigLoader] can produce clear, field-specific error messages
 * instead of a raw kotlinx.serialization stack trace.
 */

@Serializable
internal data class EconomyConfigDto(
    val constants: EconomyConstantsDto,
    val spaces: List<BoardSpaceDto>,
    val properties: List<PropertyConfigDto>,
    val stations: List<StationConfigDto>,
    val utilities: List<UtilityConfigDto>,
    val chanceDeck: List<CardEffectDto>,
    val chestDeck: List<CardEffectDto>
)

@Serializable
internal data class EconomyConstantsDto(
    val startingMoney: Int,
    val goReward: Int,
    val jailFine: Int,
    val mortgageInterestRate: Double,
    val buildingResaleRate: Double,
    val totalHouses: Int,
    val totalHotels: Int,
    val auctionMinimumBid: Int,
    val auctionMinimumIncrement: Int,
    val singleUtilityMultiplier: Int,
    val bothUtilitiesMultiplier: Int,
    val incomeTax: Int,
    val luxuryTax: Int
)

@Serializable
internal data class BoardSpaceDto(
    val index: Int,
    val type: String,
    val nameArabic: String,
    val developerName: String,
    val assetId: String? = null
)

@Serializable
internal data class PropertyConfigDto(
    val id: String,
    val boardPosition: Int,
    val nameArabic: String,
    val nameLatin: String,
    val group: String,
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

@Serializable
internal data class StationConfigDto(
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

@Serializable
internal data class UtilityConfigDto(
    val id: String,
    val boardPosition: Int,
    val nameArabic: String,
    val purchasePrice: Int,
    val mortgageValue: Int
)

/**
 * One card's wire format. [effectType] plus whichever of the optional
 * parameter fields apply — see Cards.md's "Effect Type Reference" for which
 * fields go with which effectType. [deck] is intentionally NOT a field here:
 * it's implied by whether the card appears in economy-config.json's
 * "chanceDeck" or "chestDeck" array, so [EconomyConfigDto.toBoardConfig]
 * assigns it rather than trusting a possibly-inconsistent JSON field.
 */
@Serializable
internal data class CardEffectDto(
    val id: String,
    val textArabic: String,
    val effectType: String,
    val target: Int? = null,
    val delta: Int? = null,
    val rentMultiplier: Int? = null,
    val multiplierOverride: Int? = null,
    val amount: Int? = null,
    val perHouse: Int? = null,
    val perHotel: Int? = null
)

// --- DTO -> domain mapping -------------------------------------------------

internal fun EconomyConfigDto.toBoardConfig(sourceLabel: String): BoardConfig = BoardConfig(
    constants = constants.toDomain(),
    spaces = spaces.map { it.toDomain(sourceLabel) },
    properties = properties.map { it.toDomain(sourceLabel) },
    stations = stations.map { it.toDomain() },
    utilities = utilities.map { it.toDomain() },
    chanceDeck = chanceDeck.map { it.toCardDef(Deck.CHANCE, sourceLabel) },
    chestDeck = chestDeck.map { it.toCardDef(Deck.CAPITAL_CHEST, sourceLabel) }
)

private fun EconomyConstantsDto.toDomain(): EconomyConstants = EconomyConstants(
    startingMoney = startingMoney,
    goReward = goReward,
    jailFine = jailFine,
    mortgageInterestRate = mortgageInterestRate,
    buildingResaleRate = buildingResaleRate,
    totalHouses = totalHouses,
    totalHotels = totalHotels,
    auctionMinimumBid = auctionMinimumBid,
    auctionMinimumIncrement = auctionMinimumIncrement,
    singleUtilityMultiplier = singleUtilityMultiplier,
    bothUtilitiesMultiplier = bothUtilitiesMultiplier,
    incomeTax = incomeTax,
    luxuryTax = luxuryTax
)

private fun BoardSpaceDto.toDomain(sourceLabel: String): BoardSpace = BoardSpace(
    index = index,
    type = parseEnum<SpaceType>(type, "space type for board index $index", sourceLabel),
    nameArabic = nameArabic,
    developerName = developerName,
    assetId = assetId
)

private fun PropertyConfigDto.toDomain(sourceLabel: String): PropertyConfig = PropertyConfig(
    id = id,
    boardPosition = boardPosition,
    nameArabic = nameArabic,
    nameLatin = nameLatin,
    group = parseEnum<PropertyGroup>(group, "property group for '$id'", sourceLabel),
    purchasePrice = purchasePrice,
    mortgageValue = mortgageValue,
    baseRent = baseRent,
    monopolyRent = monopolyRent,
    rent1House = rent1House,
    rent2Houses = rent2Houses,
    rent3Houses = rent3Houses,
    rent4Houses = rent4Houses,
    hotelRent = hotelRent,
    houseCost = houseCost
)

private fun StationConfigDto.toDomain(): StationConfig = StationConfig(
    id = id,
    boardPosition = boardPosition,
    nameArabic = nameArabic,
    purchasePrice = purchasePrice,
    mortgageValue = mortgageValue,
    rent1Station = rent1Station,
    rent2Stations = rent2Stations,
    rent3Stations = rent3Stations,
    rent4Stations = rent4Stations
)

private fun UtilityConfigDto.toDomain(): UtilityConfig = UtilityConfig(
    id = id,
    boardPosition = boardPosition,
    nameArabic = nameArabic,
    purchasePrice = purchasePrice,
    mortgageValue = mortgageValue
)

private fun CardEffectDto.toCardDef(deck: Deck, sourceLabel: String): CardDef =
    CardDef(id = id, deck = deck, textArabic = textArabic, effect = toEffect(sourceLabel))

private fun CardEffectDto.toEffect(sourceLabel: String): CardEffect {
    fun requireParam(value: Int?, paramName: String): Int = value
        ?: throw EconomyConfigException(
            "Card '$id' in '$sourceLabel' has effectType '$effectType' but is missing " +
                    "required parameter '$paramName'."
        )

    return when (effectType) {
        "MoveToPosition" -> CardEffect.MoveToPosition(requireParam(target, "target"))
        "MoveRelative" -> CardEffect.MoveRelative(requireParam(delta, "delta"))
        "MoveToNearestStation" -> CardEffect.MoveToNearestStation(requireParam(rentMultiplier, "rentMultiplier"))
        "MoveToNearestUtility" -> CardEffect.MoveToNearestUtility(requireParam(multiplierOverride, "multiplierOverride"))
        "CollectFromBank" -> CardEffect.CollectFromBank(requireParam(amount, "amount"))
        "PayToBank" -> CardEffect.PayToBank(requireParam(amount, "amount"))
        "PayEachPlayer" -> CardEffect.PayEachPlayer(requireParam(amount, "amount"))
        "CollectFromEachPlayer" -> CardEffect.CollectFromEachPlayer(requireParam(amount, "amount"))
        "GoToJail" -> CardEffect.GoToJail
        "GetOutOfJailFree" -> CardEffect.GetOutOfJailFree
        "PropertyRepairs" -> CardEffect.PropertyRepairs(
            perHouse = requireParam(perHouse, "perHouse"),
            perHotel = requireParam(perHotel, "perHotel")
        )
        else -> throw EconomyConfigException(
            "Card '$id' in '$sourceLabel' has unknown effectType '$effectType'. Expected one of: " +
                    "MoveToPosition, MoveRelative, MoveToNearestStation, MoveToNearestUtility, " +
                    "CollectFromBank, PayToBank, PayEachPlayer, CollectFromEachPlayer, GoToJail, " +
                    "GetOutOfJailFree, PropertyRepairs."
        )
    }
}

private inline fun <reified T : Enum<T>> parseEnum(raw: String, fieldDescription: String, sourceLabel: String): T =
    enumValues<T>().find { it.name == raw }
        ?: throw EconomyConfigException(
            "Invalid $fieldDescription: '$raw' in '$sourceLabel'. Expected one of: " +
                    enumValues<T>().joinToString { it.name } + "."
        )
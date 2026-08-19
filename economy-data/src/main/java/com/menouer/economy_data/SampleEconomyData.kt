package com.menouer.economy_data

/**
 * The single in-memory transcription of BoardEconomy.md and Cards.md's finalized
 * tables, used by rules-engine until M2 replaces the *source* (this object) with
 * a JSON-asset loader + startup validator producing the same [BoardConfig] shape.
 *
 * Every number here must trace back to BoardEconomy.md / Cards.md. Do not hand-tune
 * anything here — if a value needs to change, it changes in BoardEconomy.md /
 * Cards.md first, per those documents' own §10 finalization note.
 */
object SampleEconomyData {

    val constants = EconomyConstants(
        startingMoney = 150_000,
        goReward = 20_000,
        jailFine = 5_000,
        mortgageInterestRate = 0.10,
        buildingResaleRate = 0.50,
        totalHouses = 32,
        totalHotels = 12,
        auctionMinimumBid = 1_000,
        auctionMinimumIncrement = 500,
        singleUtilityMultiplier = 4,
        bothUtilitiesMultiplier = 10,
        incomeTax = 20_000,
        luxuryTax = 10_000
    )

    val properties: List<PropertyConfig> = listOf(
        PropertyConfig("Dergana", 1, "درڨانة", "Dergana", PropertyGroup.BROWN,
            6_000, 3_000, 200, 400, 1_000, 3_000, 9_000, 16_000, 25_000, 5_000),
        PropertyConfig("OuedKoriche", 3, "وادي قريش", "Oued Koriche", PropertyGroup.BROWN,
            6_000, 3_000, 400, 800, 2_000, 6_000, 18_000, 32_000, 45_000, 5_000),

        PropertyConfig("BabElOued", 6, "باب الوادي", "Bab El Oued", PropertyGroup.LIGHT_BLUE,
            10_000, 5_000, 600, 1_200, 3_000, 9_000, 27_000, 40_000, 55_000, 5_000),
        PropertyConfig("Bologhine", 8, "بولوغين", "Bologhine", PropertyGroup.LIGHT_BLUE,
            10_000, 5_000, 600, 1_200, 3_000, 9_000, 27_000, 40_000, 55_000, 5_000),
        PropertyConfig("Casbah", 9, "القصبة", "Casbah", PropertyGroup.LIGHT_BLUE,
            12_000, 6_000, 800, 1_600, 4_000, 10_000, 30_000, 45_000, 60_000, 5_000),

        PropertyConfig("ElHarrach", 11, "الحراش", "El Harrach", PropertyGroup.PINK,
            14_000, 7_000, 1_000, 2_000, 5_000, 15_000, 45_000, 62_500, 75_000, 10_000),
        PropertyConfig("Bourouba", 13, "بوروبة", "Bourouba", PropertyGroup.PINK,
            14_000, 7_000, 1_000, 2_000, 5_000, 15_000, 45_000, 62_500, 75_000, 10_000),
        PropertyConfig("Belouizdad", 14, "بلوزداد", "Belouizdad", PropertyGroup.PINK,
            16_000, 8_000, 1_200, 2_400, 6_000, 18_000, 50_000, 70_000, 90_000, 10_000),

        PropertyConfig("HusseinDey", 16, "حسين داي", "Hussein Dey", PropertyGroup.ORANGE,
            18_000, 9_000, 1_400, 2_800, 7_000, 20_000, 55_000, 75_000, 95_000, 10_000),
        PropertyConfig("Kouba", 18, "القبة", "Kouba", PropertyGroup.ORANGE,
            18_000, 9_000, 1_400, 2_800, 7_000, 20_000, 55_000, 75_000, 95_000, 10_000),
        PropertyConfig("ElMadania", 19, "المدنية", "El Madania", PropertyGroup.ORANGE,
            20_000, 10_000, 1_600, 3_200, 8_000, 22_000, 60_000, 80_000, 100_000, 10_000),

        PropertyConfig("BirMouradRais", 21, "بئر مراد رايس", "Bir Mourad Raïs", PropertyGroup.RED,
            22_000, 11_000, 1_800, 3_600, 9_000, 25_000, 70_000, 87_500, 105_000, 15_000),
        PropertyConfig("BenAknoun", 23, "بن عكنون", "Ben Aknoun", PropertyGroup.RED,
            22_000, 11_000, 1_800, 3_600, 9_000, 25_000, 70_000, 87_500, 105_000, 15_000),
        PropertyConfig("ElBiar", 24, "الأبيار", "El Biar", PropertyGroup.RED,
            24_000, 12_000, 2_000, 4_000, 10_000, 30_000, 75_000, 92_500, 110_000, 15_000),

        PropertyConfig("Cheraga", 26, "الشراقة", "Chéraga", PropertyGroup.YELLOW,
            26_000, 13_000, 2_200, 4_400, 11_000, 33_000, 80_000, 97_500, 115_000, 15_000),
        PropertyConfig("DelyIbrahim", 27, "دالي إبراهيم", "Dely Ibrahim", PropertyGroup.YELLOW,
            26_000, 13_000, 2_200, 4_400, 11_000, 33_000, 80_000, 97_500, 115_000, 15_000),
        PropertyConfig("SidiAbdellah", 29, "سيدي عبد الله", "Sidi Abdellah", PropertyGroup.YELLOW,
            28_000, 14_000, 2_400, 4_800, 12_000, 36_000, 85_000, 102_500, 120_000, 15_000),

        PropertyConfig("Mohammadia", 31, "المحمدية", "Mohammadia", PropertyGroup.GREEN,
            30_000, 15_000, 2_600, 5_200, 13_000, 39_000, 90_000, 110_000, 127_500, 20_000),
        PropertyConfig("AinBenian", 32, "عين البنيان", "Aïn Benian", PropertyGroup.GREEN,
            30_000, 15_000, 2_600, 5_200, 13_000, 39_000, 90_000, 110_000, 127_500, 20_000),
        PropertyConfig("ElMouradia", 34, "المرادية", "El Mouradia", PropertyGroup.GREEN,
            32_000, 16_000, 2_800, 5_600, 15_000, 45_000, 100_000, 120_000, 140_000, 20_000),

        PropertyConfig("Hydra", 37, "حيدرة", "Hydra", PropertyGroup.DARK_BLUE,
            35_000, 17_500, 3_500, 7_000, 17_500, 50_000, 110_000, 130_000, 150_000, 20_000),
        PropertyConfig("SidiYahia", 39, "سيدي يحيى", "Sidi Yahia", PropertyGroup.DARK_BLUE,
            40_000, 20_000, 5_000, 10_000, 20_000, 60_000, 140_000, 170_000, 200_000, 20_000)
    )

    val stations: List<StationConfig> = listOf(
        StationConfig("AghaStation", 5, "محطة الجزائر – آغا", 20_000, 10_000, 2_500, 5_000, 10_000, 20_000),
        StationConfig("ElHarrachStation", 15, "محطة الحراش", 20_000, 10_000, 2_500, 5_000, 10_000, 20_000),
        StationConfig("PlaceDesMartyrsMetro", 25, "محطة مترو ساحة الشهداء", 20_000, 10_000, 2_500, 5_000, 10_000, 20_000),
        StationConfig("BabEzzouarTramway", 35, "محطة ترامواي باب الزوار", 20_000, 10_000, 2_500, 5_000, 10_000, 20_000)
    )

    val utilities: List<UtilityConfig> = listOf(
        UtilityConfig("Sonelgaz", 12, "سونلغاز", 15_000, 7_500),
        UtilityConfig("SEAAL", 28, "سيال", 15_000, 7_500)
    )

    val spaces: List<BoardSpace> = listOf(
        BoardSpace(0, SpaceType.GO, "انطلق – الجزائر العاصمة", "GO"),
        BoardSpace(1, SpaceType.PROPERTY, "درڨانة", "Dergana", "Dergana"),
        BoardSpace(2, SpaceType.COMMUNITY_CHEST, "صندوق العاصمة", "CapitalChest"),
        BoardSpace(3, SpaceType.PROPERTY, "وادي قريش", "OuedKoriche", "OuedKoriche"),
        BoardSpace(4, SpaceType.TAX, "الضريبة", "IncomeTax"),
        BoardSpace(5, SpaceType.STATION, "محطة الجزائر – آغا", "AghaStation", "AghaStation"),
        BoardSpace(6, SpaceType.PROPERTY, "باب الوادي", "BabElOued", "BabElOued"),
        BoardSpace(7, SpaceType.CHANCE, "الحظ", "Chance"),
        BoardSpace(8, SpaceType.PROPERTY, "بولوغين", "Bologhine", "Bologhine"),
        BoardSpace(9, SpaceType.PROPERTY, "القصبة", "Casbah", "Casbah"),
        BoardSpace(10, SpaceType.JAIL, "سجن الحراش / زيارة فقط", "ElHarrachJail"),
        BoardSpace(11, SpaceType.PROPERTY, "الحراش", "ElHarrach", "ElHarrach"),
        BoardSpace(12, SpaceType.UTILITY, "سونلغاز", "Sonelgaz", "Sonelgaz"),
        BoardSpace(13, SpaceType.PROPERTY, "بوروبة", "Bourouba", "Bourouba"),
        BoardSpace(14, SpaceType.PROPERTY, "بلوزداد", "Belouizdad", "Belouizdad"),
        BoardSpace(15, SpaceType.STATION, "محطة الحراش", "ElHarrachStation", "ElHarrachStation"),
        BoardSpace(16, SpaceType.PROPERTY, "حسين داي", "HusseinDey", "HusseinDey"),
        BoardSpace(17, SpaceType.COMMUNITY_CHEST, "صندوق العاصمة", "CapitalChest"),
        BoardSpace(18, SpaceType.PROPERTY, "القبة", "Kouba", "Kouba"),
        BoardSpace(19, SpaceType.PROPERTY, "المدنية", "ElMadania", "ElMadania"),
        BoardSpace(20, SpaceType.FREE_PARKING, "موقف مجاني", "FreeParking"),
        BoardSpace(21, SpaceType.PROPERTY, "بئر مراد رايس", "BirMouradRais", "BirMouradRais"),
        BoardSpace(22, SpaceType.CHANCE, "الحظ", "Chance"),
        BoardSpace(23, SpaceType.PROPERTY, "بن عكنون", "BenAknoun", "BenAknoun"),
        BoardSpace(24, SpaceType.PROPERTY, "الأبيار", "ElBiar", "ElBiar"),
        BoardSpace(25, SpaceType.STATION, "محطة مترو ساحة الشهداء", "PlaceDesMartyrsMetro", "PlaceDesMartyrsMetro"),
        BoardSpace(26, SpaceType.PROPERTY, "الشراقة", "Cheraga", "Cheraga"),
        BoardSpace(27, SpaceType.PROPERTY, "دالي إبراهيم", "DelyIbrahim", "DelyIbrahim"),
        BoardSpace(28, SpaceType.UTILITY, "سيال", "SEAAL", "SEAAL"),
        BoardSpace(29, SpaceType.PROPERTY, "سيدي عبد الله", "SidiAbdellah", "SidiAbdellah"),
        BoardSpace(30, SpaceType.GO_TO_JAIL, "اذهب إلى سجن الحراش", "GoToJail"),
        BoardSpace(31, SpaceType.PROPERTY, "المحمدية", "Mohammadia", "Mohammadia"),
        BoardSpace(32, SpaceType.PROPERTY, "عين البنيان", "AinBenian", "AinBenian"),
        BoardSpace(33, SpaceType.COMMUNITY_CHEST, "صندوق العاصمة", "CapitalChest"),
        BoardSpace(34, SpaceType.PROPERTY, "المرادية", "ElMouradia", "ElMouradia"),
        BoardSpace(35, SpaceType.STATION, "محطة ترامواي باب الزوار", "BabEzzouarTramway", "BabEzzouarTramway"),
        BoardSpace(36, SpaceType.CHANCE, "الحظ", "Chance"),
        BoardSpace(37, SpaceType.PROPERTY, "حيدرة", "Hydra", "Hydra"),
        BoardSpace(38, SpaceType.TAX, "ضريبة الرفاهية", "LuxuryTax"),
        BoardSpace(39, SpaceType.PROPERTY, "سيدي يحيى", "SidiYahia", "SidiYahia")
    )

    // --- Chance ("الحظ") deck — Cards.md §1, 16 cards ---
    val chanceDeck: List<CardDef> = listOf(
        CardDef("CH01", Deck.CHANCE, "تقدّم إلى انطلق – الجزائر العاصمة. اقبض المكافأة.",
            CardEffect.MoveToPosition(0)),
        CardDef("CH02", Deck.CHANCE, "تقدّم إلى محطة الجزائر – آغا. إذا مررت بـ GO اقبض المكافأة.",
            CardEffect.MoveToPosition(5)),
        CardDef("CH03", Deck.CHANCE, "تقدّم إلى القصبة. إذا مررت بـ GO اقبض المكافأة.",
            CardEffect.MoveToPosition(9)),
        CardDef("CH04", Deck.CHANCE, "تقدّم إلى أقرب محطة نقل. إذا كانت مملوكة، ادفع ضعف الإيجار المعتاد.",
            CardEffect.MoveToNearestStation(2)),
        CardDef("CH05", Deck.CHANCE, "تقدّم إلى أقرب مرفق (سونلغاز أو سيال). إذا كان مملوكًا، ادفع بحسب 10 أضعاف مجموع النرد.",
            CardEffect.MoveToNearestUtility(10)),
        CardDef("CH06", Deck.CHANCE, "ارجع 3 مربعات إلى الوراء.",
            CardEffect.MoveRelative(-3)),
        CardDef("CH07", Deck.CHANCE, "اذهب إلى سجن الحراش مباشرة. لا تمر بـ GO ولا تقبض المكافأة.",
            CardEffect.GoToJail),
        CardDef("CH08", Deck.CHANCE, "بطاقة الإفراج من السجن — احتفظ بها حتى تستخدمها أو تتاجر بها.",
            CardEffect.GetOutOfJailFree),
        CardDef("CH09", Deck.CHANCE, "البنك يدفع لك أرباح أسهم بقيمة 5,000 دج.",
            CardEffect.CollectFromBank(5_000)),
        CardDef("CH10", Deck.CHANCE, "غرامة مخالفة سير: ادفع 1,500 دج للبنك.",
            CardEffect.PayToBank(1_500)),
        CardDef("CH11", Deck.CHANCE, "مسابقة رياضية على مستوى العاصمة — اقبض 10,000 دج من البنك.",
            CardEffect.CollectFromBank(10_000)),
        CardDef("CH12", Deck.CHANCE, "فوترة صيانة الطرقات: ادفع 400 دج عن كل منزل و 1,150 دج عن كل فندق تملكه.",
            CardEffect.PropertyRepairs(perHouse = 400, perHotel = 1_150)),
        CardDef("CH13", Deck.CHANCE, "تقدّم إلى حيدرة. إذا مررت بـ GO اقبض المكافأة.",
            CardEffect.MoveToPosition(37)),
        CardDef("CH14", Deck.CHANCE, "تم انتخابك رئيسًا لجمعية الحي: ادفع لكل لاعب 2,000 دج.",
            CardEffect.PayEachPlayer(2_000)),
        CardDef("CH15", Deck.CHANCE, "تقدّم إلى محطة ترامواي باب الزوار. إذا مررت بـ GO اقبض المكافأة.",
            CardEffect.MoveToPosition(35)),
        CardDef("CH16", Deck.CHANCE, "حصلت على قرض بنكي صغير: اقبض 15,000 دج.",
            CardEffect.CollectFromBank(15_000))
    )

    // --- Capital Chest ("صندوق العاصمة") deck — Cards.md §2, 16 cards ---
    val chestDeck: List<CardDef> = listOf(
        CardDef("CC01", Deck.CAPITAL_CHEST, "خطأ من البنك لصالحك: اقبض 20,000 دج.",
            CardEffect.CollectFromBank(20_000)),
        CardDef("CC02", Deck.CAPITAL_CHEST, "فاتورة طبيب: ادفع 5,000 دج.",
            CardEffect.PayToBank(5_000)),
        CardDef("CC03", Deck.CAPITAL_CHEST, "تقدّم إلى انطلق – الجزائر العاصمة. اقبض المكافأة.",
            CardEffect.MoveToPosition(0)),
        CardDef("CC04", Deck.CAPITAL_CHEST, "بيع ممتلكات قديمة: اقبض 4,500 دج من البنك.",
            CardEffect.CollectFromBank(4_500)),
        CardDef("CC05", Deck.CAPITAL_CHEST, "ضريبة عقارية: ادفع 500 دج عن كل منزل و 1,000 دج عن كل فندق تملكه.",
            CardEffect.PropertyRepairs(perHouse = 500, perHotel = 1_000)),
        CardDef("CC06", Deck.CAPITAL_CHEST, "بطاقة الإفراج من السجن — احتفظ بها حتى تستخدمها أو تتاجر بها.",
            CardEffect.GetOutOfJailFree),
        CardDef("CC07", Deck.CAPITAL_CHEST, "اذهب إلى سجن الحراش مباشرة. لا تمر بـ GO ولا تقبض المكافأة.",
            CardEffect.GoToJail),
        CardDef("CC08", Deck.CAPITAL_CHEST, "عيد ميلادك: اقبض 1,000 دج من كل لاعب.",
            CardEffect.CollectFromEachPlayer(1_000)),
        CardDef("CC09", Deck.CAPITAL_CHEST, "إرث عائلي: اقبض 25,000 دج من البنك.",
            CardEffect.CollectFromBank(25_000)),
        CardDef("CC10", Deck.CAPITAL_CHEST, "مستحقات التأمين تُدفع: اقبض 3,000 دج من البنك.",
            CardEffect.CollectFromBank(3_000)),
        CardDef("CC11", Deck.CAPITAL_CHEST, "رسوم مدرسية: ادفع 3,500 دج للبنك.",
            CardEffect.PayToBank(3_500)),
        CardDef("CC12", Deck.CAPITAL_CHEST, "غرامة تلوث بيئي: ادفع 2,500 دج للبنك.",
            CardEffect.PayToBank(2_500)),
        CardDef("CC13", Deck.CAPITAL_CHEST, "فزت بجائزة تشجيعية في مسابقة محلية: اقبض 2,000 دج.",
            CardEffect.CollectFromBank(2_000)),
        CardDef("CC14", Deck.CAPITAL_CHEST, "تبرّع خيري: ادفع لكل لاعب 500 دج.",
            CardEffect.PayEachPlayer(500)),
        CardDef("CC15", Deck.CAPITAL_CHEST, "استرداد ضريبي: اقبض 5,000 دج من البنك.",
            CardEffect.CollectFromBank(5_000)),
        CardDef("CC16", Deck.CAPITAL_CHEST, "ارجع 3 مربعات إلى الوراء.",
            CardEffect.MoveRelative(-3))
    )

    /** The full, finalized BoardConfig for Version 1. */
    val boardConfig: BoardConfig = BoardConfig(
        constants = constants,
        spaces = spaces,
        properties = properties,
        stations = stations,
        utilities = utilities,
        chanceDeck = chanceDeck,
        chestDeck = chestDeck
    )
}

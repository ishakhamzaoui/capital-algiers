package com.menouer.protocol.snapshot

import com.menouer.economy_data.Deck
import com.menouer.protocol.validation.MatchStatus
import com.menouer.rules_engine.model.TurnPhase
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class GameStateSnapshotSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `round-trips a lobby snapshot`() {
        val snapshot = GameStateSnapshot(
            gameId = "game-1",
            matchStatus = MatchStatus.LOBBY,
            matchConfigId = "cfg-1",
            stateVersion = 2,
            matchCapacity = 6,
            players = listOf(
                SnapshotPlayer(
                    playerId = "p0", displayName = "Amine", connected = true, ready = true,
                    balance = null, position = null, inJail = null, jailTurnsUsed = null,
                    getOutOfJailCards = emptyList(), bankrupt = null
                )
            ),
            assets = emptyList(),
            activePlayerId = null,
            phase = null,
            bankHouses = null,
            bankHotels = null,
            chanceDeckRemaining = null,
            chestDeckRemaining = null,
            pendingAuction = null,
            pendingTrade = null
        )

        val decoded = json.decodeFromString<GameStateSnapshot>(json.encodeToString(snapshot))
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `round-trips a full in-progress snapshot including phase, deck, and GOOJF cards`() {
        val snapshot = GameStateSnapshot(
            gameId = "game-1",
            matchStatus = MatchStatus.IN_PROGRESS,
            matchConfigId = "cfg-1",
            stateVersion = 12,
            matchCapacity = 6,
            players = listOf(
                SnapshotPlayer(
                    playerId = "p0", displayName = "Amine", connected = true, ready = true,
                    balance = 144000, position = 3, inJail = false, jailTurnsUsed = 0,
                    getOutOfJailCards = listOf(Deck.CHANCE), bankrupt = false
                )
            ),
            assets = listOf(SnapshotAsset("OuedKoriche", "p0", mortgaged = false, houses = 0, hasHotel = false)),
            activePlayerId = "p0",
            phase = TurnPhase.AWAITING_OPTIONAL_ACTIONS,
            bankHouses = 32,
            bankHotels = 12,
            chanceDeckRemaining = 15,
            chestDeckRemaining = 16,
            pendingAuction = null,
            pendingTrade = null
        )

        val decoded = json.decodeFromString<GameStateSnapshot>(json.encodeToString(snapshot))
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `round-trips a snapshot with a pending auction and a pending trade`() {
        val snapshot = GameStateSnapshot(
            gameId = "game-1",
            matchStatus = MatchStatus.IN_PROGRESS,
            matchConfigId = "cfg-1",
            stateVersion = 20,
            matchCapacity = 6,
            players = emptyList(),
            assets = emptyList(),
            activePlayerId = "p0",
            phase = TurnPhase.IN_AUCTION,
            bankHouses = 32,
            bankHotels = 12,
            chanceDeckRemaining = 16,
            chestDeckRemaining = 16,
            pendingAuction = SnapshotAuction(
                assetId = "Dergana", highestBid = 1500, highestBidderId = "p1",
                eligibleBidders = setOf("p0", "p1"), passedBidders = setOf("p0")
            ),
            pendingTrade = SnapshotTrade(
                fromPlayerId = "p0", toPlayerId = "p1",
                offeredCash = 1000, offeredAssets = setOf("Dergana"),
                offeredGetOutOfJailCards = listOf(Deck.CAPITAL_CHEST),
                requestedCash = 0, requestedAssets = setOf("OuedKoriche"),
                requestedGetOutOfJailCards = emptyList()
            )
        )

        val decoded = json.decodeFromString<GameStateSnapshot>(json.encodeToString(snapshot))
        assertEquals(snapshot, decoded)
    }
}
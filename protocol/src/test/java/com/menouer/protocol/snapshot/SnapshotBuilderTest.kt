package com.menouer.protocol.snapshot

import com.menouer.economy_data.BoardConfig
import com.menouer.economy_data.EconomyConstants
import com.menouer.protocol.host.JoinedPlayer
import com.menouer.protocol.validation.MatchStatus
import com.menouer.rules_engine.model.AssetState
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerState
import com.menouer.rules_engine.model.TurnPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotBuilderTest {

    private fun emptyBoardConfig() = BoardConfig(
        constants = EconomyConstants(
            startingMoney = 150000, goReward = 20000, jailFine = 5000,
            mortgageInterestRate = 0.1, buildingResaleRate = 0.5,
            totalHouses = 32, totalHotels = 12,
            auctionMinimumBid = 1000, auctionMinimumIncrement = 500,
            singleUtilityMultiplier = 4, bothUtilitiesMultiplier = 10,
            incomeTax = 20000, luxuryTax = 10000
        ),
        spaces = emptyList(), properties = emptyList(), stations = emptyList(), utilities = emptyList(),
        chanceDeck = emptyList(), chestDeck = emptyList()
    )

    @Test
    fun `lobby snapshot has null gameplay fields and reflects the roster`() {
        val snapshot = SnapshotBuilder.build(
            SnapshotContext(
                gameId = "game-1",
                matchStatus = MatchStatus.LOBBY,
                matchConfigId = "cfg-1",
                matchCapacity = 6,
                stateVersion = 3,
                lobbyPlayers = listOf(
                    JoinedPlayer(id = "p0", displayName = "Amine", token = "t0", ready = true),
                    JoinedPlayer(id = "p1", displayName = "Yasmine", token = "t1", ready = false)
                ),
                connectedPlayerIds = setOf("p0"),
                gameState = null
            )
        )

        assertEquals(MatchStatus.LOBBY, snapshot.matchStatus)
        assertNull(snapshot.activePlayerId)
        assertNull(snapshot.phase)
        assertNull(snapshot.chanceDeckRemaining)
        assertTrue(snapshot.assets.isEmpty())

        assertEquals(2, snapshot.players.size)
        val amine = snapshot.players.single { it.playerId == "p0" }
        assertEquals("Amine", amine.displayName)
        assertEquals(true, amine.ready)
        assertEquals(true, amine.connected)
        assertNull(amine.balance)

        val yasmine = snapshot.players.single { it.playerId == "p1" }
        assertEquals(false, yasmine.connected)
    }

    @Test
    fun `in-progress snapshot carries full gameplay state and looks up display names`() {
        val state = GameState(
            stateVersion = 7,
            config = emptyBoardConfig(),
            players = listOf(
                PlayerState(id = "p0", balance = 144000, position = 3),
                PlayerState(id = "p1", balance = 150000, position = 0)
            ),
            assets = mapOf(
                "OuedKoriche" to AssetState(id = "OuedKoriche", ownerId = "p0"),
                "Dergana" to AssetState(id = "Dergana", ownerId = null)
            ),
            activePlayerId = "p1",
            phase = TurnPhase.AWAITING_ROLL,
            bankHouses = 32,
            bankHotels = 12,
            chanceDeck = listOf("CH01", "CH02"),
            chestDeck = listOf("CC01")
        )

        val snapshot = SnapshotBuilder.build(
            SnapshotContext(
                gameId = "game-1",
                matchStatus = MatchStatus.IN_PROGRESS,
                matchConfigId = "cfg-1",
                matchCapacity = 6,
                stateVersion = 12,
                lobbyPlayers = listOf(
                    JoinedPlayer(id = "p0", displayName = "Amine", token = "t0"),
                    JoinedPlayer(id = "p1", displayName = "Yasmine", token = "t1")
                ),
                connectedPlayerIds = setOf("p0", "p1"),
                gameState = state
            )
        )

        assertEquals(MatchStatus.IN_PROGRESS, snapshot.matchStatus)
        assertEquals("p1", snapshot.activePlayerId)
        assertEquals(TurnPhase.AWAITING_ROLL, snapshot.phase)
        assertEquals(2, snapshot.chanceDeckRemaining)
        assertEquals(1, snapshot.chestDeckRemaining)
        // Sorted by assetId for deterministic output: "Dergana" < "OuedKoriche".
        assertEquals(listOf("Dergana", "OuedKoriche"), snapshot.assets.map { it.assetId })

        val amine = snapshot.players.single { it.playerId == "p0" }
        assertEquals("Amine", amine.displayName)
        assertEquals(144000, amine.balance)
        assertEquals(3, amine.position)

        val ouedKoriche = snapshot.assets.single { it.assetId == "OuedKoriche" }
        assertEquals("p0", ouedKoriche.ownerId)
    }

    @Test
    fun `a player with no lobby record falls back to using their id as display name`() {
        // Defensive path only: every real player originates from a JoinedPlayer,
        // but the lookup is a firstOrNull, so this documents what happens if it ever misses.
        val state = GameState(
            stateVersion = 0,
            config = emptyBoardConfig(),
            players = listOf(PlayerState(id = "p0", balance = 150000, position = 0)),
            assets = emptyMap(),
            activePlayerId = "p0",
            phase = TurnPhase.AWAITING_ROLL,
            bankHouses = 32,
            bankHotels = 12,
            chanceDeck = emptyList(),
            chestDeck = emptyList()
        )

        val snapshot = SnapshotBuilder.build(
            SnapshotContext(
                gameId = "game-1",
                matchStatus = MatchStatus.IN_PROGRESS,
                matchConfigId = "cfg-1",
                matchCapacity = 6,
                stateVersion = 1,
                lobbyPlayers = emptyList(),
                connectedPlayerIds = setOf("p0"),
                gameState = state
            )
        )

        assertEquals("p0", snapshot.players.single().displayName)
    }
}
package com.menouer.protocol.envelope

import com.menouer.economy_data.Deck
import com.menouer.protocol.message.ClientMessage
import com.menouer.rules_engine.JailAction
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ClientEnvelopeSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `round-trips a no-payload-fields message`() {
        val envelope = ClientEnvelope(
            messageId = "msg-1",
            protocolVersion = 1,
            gameId = "game-1",
            senderId = "player-1",
            stateVersion = 5,
            payload = ClientMessage.RollDiceRequest
        )

        val decoded = json.decodeFromString<ClientEnvelope>(json.encodeToString(envelope))

        assertEquals(envelope, decoded)
    }

    @Test
    fun `round-trips a message carrying plain fields`() {
        val envelope = ClientEnvelope(
            messageId = "msg-2",
            protocolVersion = 1,
            gameId = "game-1",
            senderId = "player-1",
            stateVersion = 6,
            payload = ClientMessage.AuctionBidRequest(amount = 2500)
        )

        val decoded = json.decodeFromString<ClientEnvelope>(json.encodeToString(envelope))

        assertEquals(envelope, decoded)
    }

    @Test
    fun `round-trips a message carrying a custom-serialized rules-engine enum`() {
        val envelope = ClientEnvelope(
            messageId = "msg-3",
            protocolVersion = 1,
            gameId = "game-1",
            senderId = "player-1",
            stateVersion = 7,
            payload = ClientMessage.JailActionRequest(action = JailAction.PAY_FINE)
        )

        val decoded = json.decodeFromString<ClientEnvelope>(json.encodeToString(envelope))

        assertEquals(envelope, decoded)
    }

    @Test
    fun `round-trips a message carrying a custom-serialized economy-data enum list`() {
        val envelope = ClientEnvelope(
            messageId = "msg-4",
            protocolVersion = 1,
            gameId = "game-1",
            senderId = "player-1",
            stateVersion = 8,
            payload = ClientMessage.TradeProposalRequest(
                toPlayerId = "player-2",
                offeredCash = 1000,
                offeredAssets = setOf("Dergana"),
                offeredGetOutOfJailCards = listOf(Deck.CHANCE),
                requestedCash = 0,
                requestedAssets = emptySet(),
                requestedGetOutOfJailCards = listOf(Deck.CAPITAL_CHEST)
            )
        )

        val decoded = json.decodeFromString<ClientEnvelope>(json.encodeToString(envelope))

        assertEquals(envelope, decoded)
    }

    @Test
    fun `distinct payload types produce distinct encoded output`() {
        val rollEnvelope = ClientEnvelope("m1", 1, "g1", "p1", 1, ClientMessage.RollDiceRequest)
        val endTurnEnvelope = ClientEnvelope("m2", 1, "g1", "p1", 1, ClientMessage.EndTurnRequest)

        // Sanity check that the sealed-class discriminator actually
        // distinguishes payload types on the wire, satisfying §5's intent
        // for a messageType-equivalent without a hand-tracked extra field.
        assertNotEquals(json.encodeToString(rollEnvelope), json.encodeToString(endTurnEnvelope))
    }
}
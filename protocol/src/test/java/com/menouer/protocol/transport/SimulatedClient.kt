package com.menouer.protocol.transport

import com.menouer.protocol.envelope.ClientEnvelope
import com.menouer.protocol.envelope.HostEnvelope
import com.menouer.protocol.message.ClientMessage
import com.menouer.protocol.message.HostMessage
import com.menouer.rules_engine.model.PlayerId

/**
 * Test-support only: a client's local view of the world, driving a
 * [Transport] the way a real client implementation would — tracking its own
 * identity and the last `stateVersion` it has seen, and stamping every
 * outgoing envelope with them, rather than a test hand-constructing
 * [ClientEnvelope]s directly and having to duplicate/keep that bookkeeping
 * in sync itself.
 *
 * [connectionId] is used as `senderId` until [assignedPlayerId] is learned
 * from a `JoinAccepted` (see `ClientEnvelope`'s own doc on this) — from then
 * on, the sender id used for every subsequent [send] switches to it
 * automatically. Any `JoinAccepted` this client receives is unconditionally
 * its own: `InMemoryTransport.send`'s `Joined` branch only ever delivers it
 * to the one connection that just joined, never broadcasts it.
 */
class SimulatedClient(
    private val transport: Transport,
    private val gameId: String,
    private val protocolVersion: Int,
    val connectionId: String
) {
    val inbox: MutableList<HostEnvelope> = mutableListOf()

    var assignedPlayerId: PlayerId? = null
        private set

    /** The `stateVersion` of the last envelope this client has seen — what a well-behaved client stamps its next request with. */
    var lastKnownStateVersion: Long = 0
        private set

    private var messageCounter = 0
    private var lastSentEnvelope: ClientEnvelope? = null

    init {
        transport.connect(connectionId) { envelope -> onReceive(envelope) }
    }

    /**
     * Re-registers this client's channel with the transport under its
     * current identity — simulates a physical reconnect (a new/same socket
     * coming back up) as a distinct step from the protocol-level
     * `ReconnectRequest`/`ClientAcknowledgement` handshake that follows it.
     * Needed after [Transport.disconnect] removed this client's channel
     * entirely; without this there would be nowhere to deliver anything
     * back to.
     */
    fun reconnectChannel() {
        transport.connect(currentSenderId()) { envelope -> onReceive(envelope) }
    }

    private fun currentSenderId(): String = assignedPlayerId ?: connectionId

    private fun onReceive(envelope: HostEnvelope) {
        inbox += envelope
        lastKnownStateVersion = envelope.stateVersion
        val payload = envelope.payload
        if (payload is HostMessage.JoinAccepted) {
            assignedPlayerId = payload.assignedPlayerId
        }
    }

    private fun nextMessageId(): String = "$connectionId-${messageCounter++}"

    fun send(payload: ClientMessage) {
        val envelope = ClientEnvelope(
            messageId = nextMessageId(),
            protocolVersion = protocolVersion,
            gameId = gameId,
            senderId = currentSenderId(),
            stateVersion = lastKnownStateVersion,
            payload = payload
        )
        lastSentEnvelope = envelope
        transport.send(envelope)
    }

    /** Re-sends the exact last envelope this client sent (same messageId) — for exercising duplicate-request handling. */
    fun resendLast() {
        val last = checkNotNull(lastSentEnvelope) { "no previous send() to resend" }
        transport.send(last)
    }
}
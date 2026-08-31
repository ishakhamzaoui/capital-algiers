package com.menouer.protocol.transport

import com.menouer.protocol.envelope.ClientEnvelope
import com.menouer.protocol.envelope.HostEnvelope
import com.menouer.protocol.host.DispatchResult
import com.menouer.protocol.host.HostBroadcast
import com.menouer.protocol.host.HostSession
import com.menouer.protocol.message.HostMessage
import com.menouer.rules_engine.model.PlayerId

private const val HOST_SENDER_ID = "host"

/**
 * The in-memory `Transport` this milestone's exit criteria call for: "a
 * host + N simulated in-process clients can complete a full game via the
 * protocol layer... without real network sockets."
 *
 * Wraps exactly one [HostSession] and routes everything through it —
 * `HostSession` supplies the *what* (validate, dispatch to `RulesEngine`,
 * build snapshots); this class supplies the *who gets told*: connection
 * bookkeeping and addressed delivery, entirely in-memory via direct
 * [HostToClientChannel.deliver] calls rather than sockets.
 *
 * Addressing: a connection starts out identified by its own connection-local
 * [connect] id (what a not-yet-joined client uses as `ClientEnvelope.senderId`
 * — see `ClientEnvelope`'s doc). The moment [HostSession] accepts that
 * client's `JoinRequest` (`DispatchResult.Joined`), this class re-keys that
 * same physical channel under the newly assigned `PlayerId`, so every
 * subsequent lookup — routing a broadcast, addressing a later rejection —
 * uses one consistent identity per connection rather than juggling two.
 *
 * Delivery is uniformly push-based: every outcome (a rejection, a broadcast,
 * a targeted snapshot) goes out via [HostToClientChannel.deliver], including
 * back to whichever client's own request triggered it — mirroring how a
 * real client only ever learns the result of its own action by receiving a
 * message back over the same channel as everyone else, not via some special
 * synchronous return value.
 *
 * One `HostBroadcast` can bundle several `HostMessage`s from a single
 * committed change (e.g. `DiceRolled` + `PlayerMoved` from one `applyRoll`
 * call) — that bundling is a protocol-layer batching convenience (Session
 * 3), not the wire shape. §5 describes one message per envelope, so each
 * gets unwound into its own [HostEnvelope] here, all sharing that commit's
 * `stateVersion`.
 */
class InMemoryTransport(private val hostSession: HostSession) : Transport {

    private val channelsByAddress = mutableMapOf<String, HostToClientChannel>()
    private var outboundMessageCounter = 0

    override fun connect(connectionId: String, channel: HostToClientChannel) {
        channelsByAddress[connectionId] = channel
    }

    override fun disconnect(connectionId: String) {
        channelsByAddress.remove(connectionId)
        deliverBroadcast(hostSession.markDisconnected(connectionId))
    }

    /**
     * Routes `HostSession.startMatch()`'s own `DispatchResult` the same way
     * [send] routes a client-originated one — `startMatch` has no
     * `ClientMessage` of its own (MultiplayerProtocol.md §3: only the host
     * device's own UI triggers it), so it doesn't go through [send], but its
     * resulting broadcast still needs to reach every connected client.
     */
    fun startMatch(): DispatchResult {
        val result = hostSession.startMatch()
        if (result is DispatchResult.Applied) {
            result.broadcasts.forEach { deliverBroadcast(it) }
        }
        return result
    }

    override fun send(envelope: ClientEnvelope) {
        when (val result = hostSession.handle(envelope)) {
            is DispatchResult.Rejected ->
                deliverToOne(envelope.senderId, HostMessage.ErrorResponse(result.errorCode, envelope.messageId))

            is DispatchResult.Applied ->
                result.broadcasts.forEach { deliverBroadcast(it) }

            is DispatchResult.Joined -> {
                rekeyToAssignedPlayerId(envelope.senderId, result.assignedPlayerId)
                deliverToOne(result.assignedPlayerId, result.joinAccepted)
                result.broadcasts.forEach { deliverBroadcast(it) }
            }

            is DispatchResult.SnapshotSent ->
                deliverToOne(envelope.senderId, result.message)
        }
    }

    /**
     * A `JoinRequest`'s connection-local sender id and its newly assigned
     * `PlayerId` both refer to the same physical channel at this instant —
     * this just moves that one channel to live under its permanent key
     * going forward.
     */
    private fun rekeyToAssignedPlayerId(connectionId: String, assignedPlayerId: PlayerId) {
        val channel = channelsByAddress.remove(connectionId) ?: return
        channelsByAddress[assignedPlayerId] = channel
    }

    private fun deliverBroadcast(broadcast: HostBroadcast) {
        broadcast.messages.forEach { message ->
            val envelope = wrap(broadcast.stateVersion, message)
            channelsByAddress.values.forEach { it.deliver(envelope) }
        }
    }

    private fun deliverToOne(address: String, message: HostMessage) {
        channelsByAddress[address]?.deliver(wrap(hostSession.currentStateVersion(), message))
    }

    private fun wrap(stateVersion: Long, message: HostMessage): HostEnvelope = HostEnvelope(
        messageId = "host-${outboundMessageCounter++}",
        protocolVersion = hostSession.currentProtocolVersion(),
        gameId = hostSession.currentGameId(),
        senderId = HOST_SENDER_ID,
        stateVersion = stateVersion,
        payload = message
    )
}
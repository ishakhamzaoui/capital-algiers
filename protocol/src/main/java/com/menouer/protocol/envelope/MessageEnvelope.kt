package com.menouer.protocol.envelope

import com.menouer.protocol.message.ClientMessage
import com.menouer.protocol.message.HostMessage
import kotlinx.serialization.Serializable

/**
 * Wire envelope for a message from a client to the host, per
 * MultiplayerProtocol.md §5.
 *
 * §5's conceptual shape includes a `messageType` string field alongside
 * `payload`. This envelope doesn't carry `messageType` as a separate field:
 * [payload]'s concrete sealed subtype already *is* that information, and
 * kotlinx.serialization's polymorphic serialization for sealed classes emits
 * a discriminator (`"type"`) in the encoded JSON automatically — so the
 * actual wire format still satisfies §5, without this class hand-tracking a
 * second field that could drift out of sync with the payload's real type.
 *
 * [senderId] duplicates [ClientMessage.JoinRequest]'s concept of "who is
 * this" for the one message type sent before a player has an assigned
 * identity — for `JoinRequest`, the host treats [senderId] as a
 * connection-local/session identifier rather than an authoritative
 * `PlayerId`; it becomes authoritative only once `JoinAccepted` assigns one
 * (Session 4).
 */
@Serializable
data class ClientEnvelope(
    val messageId: String,
    val protocolVersion: Int,
    val gameId: String,
    val senderId: String,
    val stateVersion: Long,
    val payload: ClientMessage
)

/**
 * Wire envelope for a message from the host to a client, per
 * MultiplayerProtocol.md §5. Not `@Serializable` — see `HostMessage.kt`'s
 * doc for why `HostMessage` itself isn't annotated yet.
 *
 * [senderId] is always the host's own identifier for a real match (as
 * opposed to [ClientEnvelope.senderId], which identifies whichever client
 * sent the request).
 */
data class HostEnvelope(
    val messageId: String,
    val protocolVersion: Int,
    val gameId: String,
    val senderId: String,
    val stateVersion: Long,
    val payload: HostMessage
)
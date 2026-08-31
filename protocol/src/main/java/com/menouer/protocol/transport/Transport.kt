package com.menouer.protocol.transport

import com.menouer.protocol.envelope.ClientEnvelope
import com.menouer.protocol.envelope.HostEnvelope

/**
 * A connected client's mailbox, from the host's perspective: however the
 * host delivers a [HostEnvelope] to this one specific connection. A real
 * transport (M5) would implement this by writing to a socket; this
 * milestone's `InMemoryTransport` implements it by calling a plain Kotlin
 * callback directly.
 */
fun interface HostToClientChannel {
    fun deliver(envelope: HostEnvelope)
}

/**
 * The transport-agnostic connection registry + message router sitting
 * between N clients and one `HostSession` — this is the "transport-agnostic
 * layer" DevelopmentRoadmap.md's M4 goal describes, built here without real
 * sockets (an in-memory double, per M4's own scope: "This milestone can be
 * built and tested without real network sockets... Real sockets are M5's
 * job, not this one").
 *
 * `HostSession` itself has never had any transport knowledge (Session 3) —
 * every method on it is a plain function call. [Transport] is what turns
 * those plain calls into something that looks like a real client/server
 * exchange: connections, addressed delivery, and routing a `DispatchResult`
 * to the right recipient(s). A production transport (M5's real sockets)
 * implements this exact same interface; swapping `InMemoryTransport` for it
 * shouldn't require any change to `HostSession`.
 */
interface Transport {

    /**
     * Registers a new connection under [connectionId] — the connection-local
     * identifier a not-yet-joined client uses as its `ClientEnvelope.senderId`
     * (see `ClientEnvelope`'s own doc, Session 1). [channel] is how the host
     * delivers messages back to this connection specifically, both before
     * and after it successfully joins (the same physical connection just
     * gets re-addressed by assigned `PlayerId` once it has — see
     * `InMemoryTransport`'s doc).
     */
    fun connect(connectionId: String, channel: HostToClientChannel)

    /** Delivers [envelope] to the host and routes whatever comes back (rejection, broadcast, snapshot, ...) to the right recipient(s). */
    fun send(envelope: ClientEnvelope)

    /**
     * Simulates a dropped connection. Session 7 wires this to real
     * disconnect detection and the grace-period/auto-pass timeout
     * machinery; here it's just a direct call a test makes.
     */
    fun disconnect(connectionId: String)
}
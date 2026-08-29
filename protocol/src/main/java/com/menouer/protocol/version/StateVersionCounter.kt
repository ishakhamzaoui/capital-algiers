package com.menouer.protocol.version

/**
 * Owns the authoritative match's monotonically increasing `stateVersion`
 * (MultiplayerProtocol.md §10).
 *
 * rules-engine's `GameState` carries a `stateVersion: Long = 0` field, but
 * `RulesEngineImpl` never increments it anywhere — confirmed by inspection
 * of the current rules-engine source before writing this. That's not a gap
 * to patch in rules-engine; bumping it is a protocol-layer responsibility by
 * design, since "one committed change" (§12, atomicity) is a
 * protocol/transaction concept the engine itself has no reason to know
 * about. Session 3 (host session/engine wiring) calls [incrementAndGet]
 * exactly once per committed (`EngineResult.Applied`) result and stamps the
 * new value onto the resulting `GameState` before broadcasting it.
 *
 * Not thread-safe by design: MultiplayerProtocol.md's host-authoritative
 * model processes one request at a time (§8's pipeline runs sequentially),
 * so there's no concurrent-increment scenario for the in-memory transport
 * double this milestone builds, or for a straightforward single-threaded
 * host loop later.
 */
class StateVersionCounter(startingAt: Long = 0) {
    var current: Long = startingAt
        private set

    /** Call exactly once per committed engine result. Returns the new value. */
    fun incrementAndGet(): Long {
        current += 1
        return current
    }
}
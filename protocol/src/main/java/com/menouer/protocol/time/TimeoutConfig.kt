package com.menouer.protocol.time

/**
 * MultiplayerProtocol.md §13's finalized timeout/interval configuration.
 * The spec is explicit that these "are configuration values, not
 * hardcoded constants, and may be tuned later without a protocol version
 * bump" — hence one injectable data class carrying the finalized numbers
 * as defaults, rather than named constants scattered through `HostSession`.
 *
 * [joinTimeoutSeconds] and [discoveryRefreshIntervalSeconds] are carried
 * here for completeness with §13's full list but aren't yet enforced by
 * anything in `:protocol` — both are about the join/discovery flow over a
 * real transport (a pending-but-never-completed join timing out; how often
 * a real LAN discovery broadcast repeats), which only becomes meaningful
 * once M5 replaces `InMemoryTransport` with real sockets.
 */
data class TimeoutConfig(
    val joinTimeoutSeconds: Long = 15,
    val discoveryRefreshIntervalSeconds: Long = 3,
    val normalActionTimeoutSeconds: Long = 30,
    val auctionResponseTimeoutSeconds: Long = 10,
    val reconnectionGracePeriodSeconds: Long = 120,
    val hostLossHeartbeatIntervalSeconds: Long = 5,
    val hostLossDetectionMissedBeats: Int = 3
)
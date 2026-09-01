package com.menouer.protocol.timeout

import com.menouer.protocol.time.Clock
import java.time.Instant

/**
 * Client-side counterpart to `HostSession.maybeSendHeartbeat` (see its own
 * doc). Tracks how long it's been since ANY message arrived from the host —
 * a `HostMessage.Heartbeat` or any other broadcast/response counts equally
 * as proof of liveness — and reports host loss once that gap exceeds
 * `heartbeatIntervalSeconds * missedBeatsThreshold`, matching
 * MultiplayerProtocol.md §13's "after hostLossDetectionMissedBeats missed
 * heartbeats (~15s at defaults), clients mark the host lost."
 *
 * No client/app implementation exists yet to wire this into — `app`'s real
 * networking wiring is M5/M6's job per DevelopmentRoadmap.md — so this is
 * written now as standalone, fully-tested logic, ready for whichever
 * milestone first has a real client rather than needing this reasoning
 * worked out again later.
 */
class HostLossDetector(
    private val clock: Clock,
    private val heartbeatIntervalSeconds: Long,
    private val missedBeatsThreshold: Int
) {
    private var lastMessageAt: Instant = clock.now()

    /** Call whenever ANY message arrives from the host, not just heartbeats — resets the loss clock. */
    fun recordMessageReceived() {
        lastMessageAt = clock.now()
    }

    fun hasLostHost(): Boolean {
        val elapsedSeconds = clock.now().epochSecond - lastMessageAt.epochSecond
        return elapsedSeconds >= heartbeatIntervalSeconds * missedBeatsThreshold
    }
}
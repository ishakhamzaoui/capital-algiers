package com.menouer.protocol.time

import java.time.Instant

/**
 * An injectable source of "now", so timeout logic (Session 6) is testable
 * deterministically — by advancing a fake clock in tests — rather than
 * needing real wall-clock delays or a coroutines/scheduler dependency this
 * project doesn't otherwise have. Mirrors the same philosophy as
 * rules-engine's own seedable `DiceSource`.
 *
 * `java.time.Instant`/`Duration` are used directly (not a custom time type):
 * this project's minSdk is exactly 26, which is `java.time`'s natural
 * Android support baseline, so no core-library-desugaring config is needed
 * to use it safely down to the minimum supported OS version.
 */
fun interface Clock {
    fun now(): Instant
}

/** The real clock — what production code uses by default. */
object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
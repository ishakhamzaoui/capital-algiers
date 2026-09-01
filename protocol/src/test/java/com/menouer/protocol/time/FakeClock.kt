package com.menouer.protocol.time

import java.time.Instant

/** Test-support only: a [Clock] whose "now" only moves when a test tells it to. */
class FakeClock(startAt: Instant = Instant.EPOCH) : Clock {
    private var current: Instant = startAt

    override fun now(): Instant = current

    fun advanceBySeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }

    fun setTo(instant: Instant) {
        current = instant
    }
}
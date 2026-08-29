package com.menouer.protocol.version

import org.junit.Assert.assertEquals
import org.junit.Test

class StateVersionCounterTest {

    @Test
    fun `starts at zero by default`() {
        val counter = StateVersionCounter()
        assertEquals(0L, counter.current)
    }

    @Test
    fun `can start at an explicit value`() {
        val counter = StateVersionCounter(startingAt = 42L)
        assertEquals(42L, counter.current)
    }

    @Test
    fun `incrementAndGet returns and stores the new value`() {
        val counter = StateVersionCounter()
        assertEquals(1L, counter.incrementAndGet())
        assertEquals(1L, counter.current)
    }

    @Test
    fun `repeated increments are strictly monotonic`() {
        val counter = StateVersionCounter()
        val values = (1..5).map { counter.incrementAndGet() }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), values)
        assertEquals(5L, counter.current)
    }
}
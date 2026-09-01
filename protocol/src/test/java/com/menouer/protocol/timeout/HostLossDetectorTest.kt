package com.menouer.protocol.timeout

import com.menouer.protocol.time.FakeClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HostLossDetectorTest {

    @Test
    fun `does not report loss immediately after construction`() {
        val clock = FakeClock(Instant.EPOCH)
        val detector = HostLossDetector(clock, heartbeatIntervalSeconds = 5, missedBeatsThreshold = 3)
        assertFalse(detector.hasLostHost())
    }

    @Test
    fun `does not report loss before the threshold elapses`() {
        val clock = FakeClock(Instant.EPOCH)
        val detector = HostLossDetector(clock, heartbeatIntervalSeconds = 5, missedBeatsThreshold = 3)

        clock.advanceBySeconds(14) // just under 5 * 3 = 15
        assertFalse(detector.hasLostHost())
    }

    @Test
    fun `reports loss once heartbeatInterval times missedBeats has elapsed`() {
        val clock = FakeClock(Instant.EPOCH)
        val detector = HostLossDetector(clock, heartbeatIntervalSeconds = 5, missedBeatsThreshold = 3)

        clock.advanceBySeconds(15) // exactly 5 * 3
        assertTrue(detector.hasLostHost())
    }

    @Test
    fun `receiving a message resets the loss clock`() {
        val clock = FakeClock(Instant.EPOCH)
        val detector = HostLossDetector(clock, heartbeatIntervalSeconds = 5, missedBeatsThreshold = 3)

        clock.advanceBySeconds(14)
        detector.recordMessageReceived()
        clock.advanceBySeconds(14) // 14s since the reset, still under threshold
        assertFalse(detector.hasLostHost())

        clock.advanceBySeconds(1) // now 15s since the reset
        assertTrue(detector.hasLostHost())
    }
}
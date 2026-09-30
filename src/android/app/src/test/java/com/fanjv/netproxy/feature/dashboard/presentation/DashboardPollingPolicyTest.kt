package com.fanjv.netproxy.feature.dashboard.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardPollingPolicyTest {
    @Test
    fun `uses one two second confirmation before warm and idle cadence`() {
        assertEquals(2_000L, DashboardPollingPolicy.nextDelayMillis(0, confirmPending = true))
        assertEquals(5_000L, DashboardPollingPolicy.nextDelayMillis(0, confirmPending = false))
        assertEquals(5_000L, DashboardPollingPolicy.nextDelayMillis(24_999, confirmPending = false))
        assertEquals(15_000L, DashboardPollingPolicy.nextDelayMillis(25_000, confirmPending = false))
        assertEquals(15_000L, DashboardPollingPolicy.nextDelayMillis(30_000, confirmPending = false))
        assertEquals(5_000L, DashboardPollingPolicy.nextDelayMillis(-1, confirmPending = false))
    }

    @Test
    fun `idle dashboard stays within twenty five status calls in five minutes`() {
        var elapsed = 0L
        var calls = 1 // EVENT refresh at 0s
        var confirmPending = true
        val times = mutableListOf(0L)
        while (true) {
            val delay = DashboardPollingPolicy.nextDelayMillis(elapsed, confirmPending)
            elapsed += delay
            if (elapsed >= 300_000L) break
            calls++
            times += elapsed
            confirmPending = false
        }
        assertEquals(listOf(0L, 2_000L, 7_000L, 12_000L, 17_000L, 22_000L, 27_000L, 42_000L), times.take(8))
        assertEquals(25, calls)
    }
}

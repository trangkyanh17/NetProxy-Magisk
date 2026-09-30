package com.fanjv.netproxy.feature.dashboard.presentation

internal object DashboardPollingPolicy {
    private const val CONFIRM_DELAY_MS = 2_000L
    private const val WARM_DELAY_MS = 5_000L
    private const val IDLE_DELAY_MS = 15_000L
    private const val IDLE_THRESHOLD_MS = 30_000L

    fun nextDelayMillis(idleMillis: Long, confirmPending: Boolean): Long {
        if (confirmPending) return CONFIRM_DELAY_MS
        val idle = idleMillis.coerceAtLeast(0L)
        return if (idle + WARM_DELAY_MS >= IDLE_THRESHOLD_MS) {
            IDLE_DELAY_MS
        } else {
            WARM_DELAY_MS
        }
    }
}

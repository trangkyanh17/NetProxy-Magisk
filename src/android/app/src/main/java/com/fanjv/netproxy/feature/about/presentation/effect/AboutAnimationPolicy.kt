package com.fanjv.netproxy.feature.about.presentation.effect

internal object AboutAnimationPolicy {
    fun targetFps(active: Boolean, idleMillis: Long): Int {
        if (!active) return 0
        return if (idleMillis.coerceAtLeast(0L) < 10_000L) 60 else 30
    }
}

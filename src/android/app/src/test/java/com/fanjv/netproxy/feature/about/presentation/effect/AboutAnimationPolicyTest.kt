package com.fanjv.netproxy.feature.about.presentation.effect

import org.junit.Assert.assertEquals
import org.junit.Test

class AboutAnimationPolicyTest {
    @Test
    fun `inactive animation is stopped`() {
        assertEquals(0, AboutAnimationPolicy.targetFps(active = false, idleMillis = -1))
        assertEquals(0, AboutAnimationPolicy.targetFps(active = false, idleMillis = 60_000))
    }

    @Test
    fun `active animation drops from sixty to thirty fps after ten seconds`() {
        assertEquals(60, AboutAnimationPolicy.targetFps(active = true, idleMillis = -1))
        assertEquals(60, AboutAnimationPolicy.targetFps(active = true, idleMillis = 0))
        assertEquals(60, AboutAnimationPolicy.targetFps(active = true, idleMillis = 9_999))
        assertEquals(30, AboutAnimationPolicy.targetFps(active = true, idleMillis = 10_000))
        assertEquals(30, AboutAnimationPolicy.targetFps(active = true, idleMillis = 60_000))
    }
}

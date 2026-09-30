package com.fanjv.netproxy.feature.dashboard.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalIpv4CacheTest {
    @Test
    fun `caches successful address for thirty seconds and supports force refresh`() {
        var loads = 0
        val cache = LocalIpv4Cache {
            loads++
            "10.0.0.$loads"
        }

        assertEquals("10.0.0.1", cache.read(0))
        assertEquals("10.0.0.1", cache.read(29_999))
        assertEquals(1, loads)
        assertEquals("10.0.0.2", cache.read(30_000))
        assertEquals("10.0.0.3", cache.read(30_001, force = true))
        assertEquals(3, loads)
    }

    @Test
    fun `failed lookup returns placeholder and retries on next read`() {
        var loads = 0
        val cache = LocalIpv4Cache {
            loads++
            if (loads == 1) error("interface unavailable") else "192.168.1.2"
        }

        assertEquals("--", cache.read(0))
        assertEquals("192.168.1.2", cache.read(1))
        assertEquals(2, loads)
    }
}

package com.fanjv.netproxy.feature.dashboard.presentation

internal class LocalIpv4Cache(
    private val ttlMillis: Long = 30_000L,
    private val loader: () -> String?
) {
    private var cachedValue: String? = null
    private var cachedAtMillis: Long = Long.MIN_VALUE

    fun read(nowElapsedMillis: Long, force: Boolean = false): String {
        val cached = cachedValue
        if (!force && cached != null && nowElapsedMillis - cachedAtMillis < ttlMillis) {
            return cached
        }
        val loaded = runCatching(loader).getOrNull()?.takeIf(String::isNotBlank)
        if (loaded == null) return "--"
        cachedValue = loaded
        cachedAtMillis = nowElapsedMillis
        return loaded
    }
}

package com.fanjv.netproxy.core.locale

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocaleControllerTest {
    @Test
    fun `normalizes supported locale tags and falls back to system`() {
        assertEquals(AppLocaleController.VIETNAMESE, AppLocaleController.normalizeLanguageTag("vi-VN"))
        assertEquals(AppLocaleController.CHINESE_SIMPLIFIED, AppLocaleController.normalizeLanguageTag("zh-Hans-CN"))
        assertEquals(AppLocaleController.SYSTEM, AppLocaleController.normalizeLanguageTag("en-US"))
        assertEquals(AppLocaleController.SYSTEM, AppLocaleController.normalizeLanguageTag(""))
    }

    @Test
    fun `uses first application locale when Android returns a locale list`() {
        assertEquals(AppLocaleController.VIETNAMESE, AppLocaleController.normalizeLanguageTag("vi-VN,zh-CN"))
    }
}

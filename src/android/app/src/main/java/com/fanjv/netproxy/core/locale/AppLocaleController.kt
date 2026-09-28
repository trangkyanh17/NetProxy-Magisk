package com.fanjv.netproxy.core.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLocaleController {
    const val SYSTEM = ""
    const val CHINESE_SIMPLIFIED = "zh-CN"
    const val VIETNAMESE = "vi"

    private const val PREFS_NAME = "settings"
    private const val PREF_LANGUAGE = "app_language"

    internal fun normalizeLanguageTag(rawTag: String?): String {
        val tag = rawTag.orEmpty().substringBefore(',').trim()
        return when {
            tag.startsWith("vi", ignoreCase = true) -> VIETNAMESE
            tag.startsWith("zh", ignoreCase = true) -> CHINESE_SIMPLIFIED
            else -> SYSTEM
        }
    }

    fun currentLanguageTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            return normalizeLanguageTag(localeManager.applicationLocales.toLanguageTags())
        }

        return normalizeLanguageTag(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_LANGUAGE, SYSTEM)
        )
    }

    fun setLanguage(activity: Activity, languageTag: String) {
        val normalized = normalizeLanguageTag(languageTag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = if (normalized == SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(normalized)
            }
            activity.getSystemService(LocaleManager::class.java).applicationLocales = locales
            return
        }

        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (normalized == SYSTEM) {
            prefs.edit().remove(PREF_LANGUAGE).apply()
        } else {
            prefs.edit().putString(PREF_LANGUAGE, normalized).apply()
        }
        activity.recreate()
    }

    fun wrapContext(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context

        val languageTag = currentLanguageTag(context)
        if (languageTag == SYSTEM) return context

        val locale = Locale.forLanguageTag(languageTag)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }
}

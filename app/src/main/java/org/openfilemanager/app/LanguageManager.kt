package org.openfilemanager.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * اختيار لغة التطبيق من الإعدادات (النظام / العربية / English) دون الاعتماد على لغة الهاتف.
 * يُخزَّن الاختيار في SharedPreferences صغير ومتزامن لأن attachBaseContext يحتاجه قبل أي شيء.
 */
object LanguageManager {
    const val SYSTEM = ""
    const val ARABIC = "ar"
    const val ENGLISH = "en"

    private const val PREFS = "ofm_language"
    private const val KEY = "tag"

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun save(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    /** يُغلِّف السياق بلغة الاختيار؛ عند «النظام» يعيده كما هو. */
    fun wrap(base: Context): Context {
        val tag = current(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}

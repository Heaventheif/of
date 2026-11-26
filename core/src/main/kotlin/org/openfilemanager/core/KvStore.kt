package org.openfilemanager.core

/**
 * تخزين قيمة-مفتاح صغير تُحقن توفيراته (ملف أو SharedPreferences أو أي شيء)،
 * لتُختبر المتاجر الصرفة فوقه على JVM.
 */
interface KvStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}


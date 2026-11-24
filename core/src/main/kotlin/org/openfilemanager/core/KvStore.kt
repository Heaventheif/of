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

/** تنفيذ في الذاكرة للاختبارات. */
class MemoryKvStore : KvStore {
    private val map = LinkedHashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}

package org.openfilemanager.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** حقل الفرز المتاح لكل مجلد. */
enum class SortFieldCore { NAME, SIZE, MODIFIED, TYPE }
enum class SortDirectionCore { ASCENDING, DESCENDING }

data class FolderSort(
    val field: SortFieldCore = SortFieldCore.NAME,
    val direction: SortDirectionCore = SortDirectionCore.ASCENDING
) {
    fun encode(): String = "${field.name}:${direction.name}"

    companion object {
        fun decode(raw: String?): FolderSort? {
            if (raw == null) return null
            val parts = raw.split(':')
            if (parts.size != 2) return null
            val field = runCatching { SortFieldCore.valueOf(parts[0]) }.getOrNull() ?: return null
            val direction = runCatching { SortDirectionCore.valueOf(parts[1]) }.getOrNull() ?: return null
            return FolderSort(field, direction)
        }
    }
}

/**
 * فرز لكل مجلد: المفتاح **هاش** المسار (لا المسار نفسه فلا تُخزَّن مسارات
 * المستخدم كنص صريح)، بسقف [maxFolders] مجلد والأقدم استخداماً يُحذف (LRU).
 */
class PerFolderSortStore(
    private val kv: KvStore,
    private val maxFolders: Int = 200
) {
    /** بادئة المفاتيح + فاصل لا يظهر في الهيكس فلا تصادم بين هاش وموضع LRU. */
    private val prefix = "srt."
    private val orderKey = "srt.order"

    private fun hashOf(path: String): String =
        MessageDigest.getInstance("SHA-256").digest(path.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(12)

    /** ترتيب الاستخدام (الأقدم أولاً) لطرد الأقل استخداماً عند تجاوز السقف. */
    private fun order(): MutableList<String> =
        (kv.getString(orderKey) ?: "").split(',').filter { it.isNotEmpty() }.toMutableList()

    private fun touch(hash: String) {
        val list = order()
        list.remove(hash)
        list.add(hash)
        while (list.size > maxFolders) {
            val evicted = list.removeAt(0)
            kv.remove(prefix + evicted)
        }
        kv.putString(orderKey, list.joinToString(","))
    }

    fun sortOf(path: String): FolderSort? {
        val hash = hashOf(path)
        val value = FolderSort.decode(kv.getString(prefix + hash)) ?: return null
        touch(hash)
        return value
    }

    fun setSort(path: String, sort: FolderSort) {
        val hash = hashOf(path)
        kv.putString(prefix + hash, sort.encode())
        touch(hash)
    }

    /** إزالة فرز مجلد واحد (عند حذفه مثلاً). */
    fun clear(path: String) {
        val hash = hashOf(path)
        kv.remove(prefix + hash)
        val list = order()
        if (list.remove(hash)) kv.putString(orderKey, list.joinToString(","))
    }

    /** عدد المجلدات المسجلة الآن (للاختبار والعرض؛ لا يُحسب مفتاح الترتيب الداخلي). */
    fun size(): Int = kv.keys().count { it.startsWith(prefix) && it != orderKey }
}

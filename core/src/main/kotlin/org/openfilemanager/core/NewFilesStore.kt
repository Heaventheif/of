package org.openfilemanager.core

/**
 * علامات «الملفات الجديدة»: الملفات الأحدث من آخر زيارة تُعلَّم «جديد»،
 * وتزول العلامة عند فتح الملف. تُحفظ معرّفات (مسار/هاش) بسقف [maxIds] والقديم يُطرد.
 *
 * المنطق: نخزّن «آخر زيارة» طابعاً زمنياً، وقائمة معرّفات رآها المستخدم وفتحها؛
 * الملف «جديد» إن كان تاريخ إضافته أحدث من آخر زيارة **ولم يُفتح من قبل**.
 * الطوابع الزمنية تُمرَّر من الخارج حتى يبقى الكود صافاً وقابلاً للاختبار.
 */
class NewFilesStore(
    private val kv: KvStore,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val maxIds: Int = 500
) {
    private val seenKey = "new.seen"
    private val visitKey = "new.lastVisit"

    private fun seen(): MutableSet<String> =
        (kv.getString(seenKey) ?: "").split(',').filter { it.isNotEmpty() }.toMutableSet()

    private fun saveSeen(ids: MutableSet<String>) {
        val list = ids.toList()
        val kept = if (list.size > maxIds) list.subList(list.size - maxIds, list.size) else list
        kv.putString(seenKey, kept.joinToString(","))
    }

    /** تُستدعى عند فتح المستعرض/المكتبة: تُحدّث طابع آخر زيارة وتعيد الطابع السابق. */
    fun visit(): Long {
        val previous = kv.getString(visitKey)?.toLongOrNull() ?: 0L
        kv.putString(visitKey, nowProvider().toString())
        return previous
    }

    fun lastVisit(): Long = kv.getString(visitKey)?.toLongOrNull() ?: 0L

    /**
     * هل الملف «جديد»؟ [addedAt] طابع إضافته (MediaStore DATE_ADDED أو وقت آخر تعديل)،
     * و[id] معرّف ثابت له (مسار أو هاش مسار).
     */
    fun isNew(id: String, addedAt: Long): Boolean {
        if (addedAt <= 0) return false
        if (id in seen()) return false
        val last = lastVisit()
        return addedAt > last || last == 0L
    }

    /** تزول العلامة عند الفتح. */
    fun markOpened(id: String) {
        val ids = seen()
        if (ids.add(id)) saveSeen(ids)
    }

    /** كل المعرّفات المسجلة كمفتوحة (للاختبار وللعرض). */
    fun seenCount(): Int = seen().size
}

package org.openfilemanager.core

/**
 * كاش LRU بسقف وزن (بايت عادة): أي كاش خاص بحجم أقصى بالبايت، يُنظَّف عند
 * onTrimMemory.
 *
 * - الوصول ([get]) يجدّد حداثة الإدخال (accessOrder) فيُطرد الأقدم استخداماً أولاً.
 * - [trim] يطرد حتى هدف وزن أدنى: هو خط التنظيف الذي تستدعيه الواجهة من
 *   `onTrimMemory` بمستوى الضغط المناسب.
 * - الوزن صفر مسموح (إدخال لا يُطرد غيره)، والسقف يجب أن يكون موجباً.
 * - منطق صرف بلا أندرويد فيُختبر على JVM.
 */
class BoundedLruCache<K : Any, V : Any>(
    private val maxWeight: Long,
    private val weigher: (key: K, value: V) -> Long
) {
    init {
        require(maxWeight > 0) { "maxWeight must be positive: $maxWeight" }
    }

    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var totalWeight = 0L

    /** الوزن الحالي للمحفوظات. */
    val weight: Long get() = synchronized(this) { totalWeight }

    /** عدد الإدخالات. */
    val size: Int get() = synchronized(this) { map.size }

    fun put(key: K, value: V): Unit = synchronized(this) {
        map.remove(key)?.let { old -> totalWeight -= weigher(key, old) }
        totalWeight += weigher(key, value)
        map[key] = value
        evictOverLimit()
    }

    /** الوصول يجدّد الحداثة (LRU) ويُعاد الإدخال أو null. */
    operator fun get(key: K): V? = synchronized(this) { map[key] }

    fun remove(key: K): Unit = synchronized(this) {
        map.remove(key)?.let { old -> totalWeight -= weigher(key, old) }
    }

    fun clear(): Unit = synchronized(this) {
        map.clear()
        totalWeight = 0L
    }

    /** يطرد الأقدم استخداماً حتى ينزل الوزن إلى [targetWeight] (onTrimMemory). */
    fun trim(targetWeight: Long): Unit = synchronized(this) {
        if (targetWeight < 0) return
        val iterator = map.entries.iterator()
        while (totalWeight > targetWeight && iterator.hasNext()) {
            val entry = iterator.next()
            totalWeight -= weigher(entry.key, entry.value)
            iterator.remove()
        }
    }

    /** الطرد الداخلي فوق السقف بعد كل إضافة. */
    private fun evictOverLimit() {
        val iterator = map.entries.iterator()
        while (totalWeight > maxWeight && iterator.hasNext()) {
            val entry = iterator.next()
            totalWeight -= weigher(entry.key, entry.value)
            iterator.remove()
        }
    }
}

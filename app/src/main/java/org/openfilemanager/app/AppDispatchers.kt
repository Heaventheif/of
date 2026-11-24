package org.openfilemanager.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * مجدولات التطبيق الموحدة: «كل العمل الثقيل على Dispatchers.IO محدودة
 * بـ limitedParallelism(4) والفرز على Default، والخيط الرئيسي للواجهة فقط».
 *
 * نقطة واحدة يعبرها كل كود التطبيق، فلا يتفلت استخدام خام إلى Dispatchers.IO
 * غير المحدودة، ويبقى تعديل السقف لاحقاً في سطر واحد.
 */
object AppDispatchers {
    /** إدخال/إخراج الملفات والاستعلامات: 4 خيوط على الأكثر. */
    val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)

    /** الفرز والتحويلات الحسابية على القوائم الكبيرة. */
    val compute: CoroutineDispatcher = Dispatchers.Default
}

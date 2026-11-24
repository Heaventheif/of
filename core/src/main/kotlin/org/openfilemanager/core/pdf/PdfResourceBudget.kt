package org.openfilemanager.core.pdf

import java.util.concurrent.atomic.AtomicLong

/**
 * (ميزانية مركزية) سقوف موارد فك ترميز مستند PDF واحد، تُطبَّق عبر كل
 * مسارات الفك في [PdfFile] و[PdfFont] والمعترض: لا ينتظر الفشل حتى
 * استهلاك الذاكرة بل يُرفض عند التجاوز برسالة واضحة.
 *
 * - [maxStreamBytes]: سقف ناتج stream واحد (لكل مرحلة مرشح، تراكمي عبر السلسلة).
 * - [maxTotalStreamBytes]: سقف مجموع البايتات المفكوكة للمستند كله — يمنع
 *   قنبلة موزعة على مداخل كثيرة (Do متكرر، محتويات صفحة متعددة) بدل قنبلة واحدة.
 * - [maxFilterChain]: طول سلسلة المرشحات (قنابل Flate المتداخلة).
 * - [maxXrefEntries]: عدد مداخل جداول/تدفقات xref (استهلاك ذاكرة الخريطة).
 * - [maxObjectsPerStream]: مدخل /N لتيار الكائنات قبل أي تخصيص حرصي.
 * - [maxCachedObjects]: سقف مخزن الكائنات المحلولة (LRU).
 * - [maxCmapEntries]: سقف مداخل خريطة ToUnicode (تفكيك endbfrange).
 * - [maxPageContentBytes]: مجموع محتوى صفحة واحدة (N مدخل /Contents).
 *
 * الخيط الآمن: العداد ذري؛ فك الترميز يجري تحت قفل المستند عملياً لكن
 * الذرية تجعل العقد صحيحاً حتى لو تعدّت المسارات.
 */
internal class PdfResourceBudget(
    val maxStreamBytes: Int = DEFAULT_MAX_STREAM_BYTES,
    val maxTotalStreamBytes: Long = DEFAULT_MAX_TOTAL_STREAM_BYTES,
    val maxFilterChain: Int = DEFAULT_MAX_FILTER_CHAIN,
    val maxXrefEntries: Int = DEFAULT_MAX_XREF_ENTRIES,
    val maxObjectsPerStream: Int = DEFAULT_MAX_OBJECTS_PER_STREAM,
    val maxCachedObjects: Int = DEFAULT_MAX_CACHED_OBJECTS,
    val maxCmapEntries: Int = DEFAULT_MAX_CMAP_ENTRIES,
    val maxPageContentBytes: Int = DEFAULT_MAX_PAGE_CONTENT_BYTES
) {
    private val chargedStreams = HashSet<Long>()
    private val totalStreamBytes = AtomicLong(0L)

    /**
     * يسجل بايتات فك stream **متميز** (موضعه وطوله) مرة واحدة في ميزانية
     * المستند، ويُفشل عند تجاوز [maxTotalStreamBytes]. التمييز ضروري:
     * إعادة طلب نص صفحة (بحث/تمرير) تعيد فك التيارات نفسها، فتأمين كل فك
     * كان يُنهي قراءة مستندات كبيرة سليمة في منتصفها. الخلفية سقف أخير
     * (2 ج.ب) فوق أي مستند نصي حقيقي وضمن أي هجوم موزّع.
     */
    fun chargeDecodedOnce(stream: PdfStream, decodedBytes: Int) {
        val key = stream.start.toLong() shl 32 or (stream.length.toLong() and 0xffffffffL)
        if (!chargedStreams.add(key)) return
        if (chargedStreams.size > MAX_TRACKED_STREAMS) {
            // سقف ذاكرة الجرد نفسه: أفرِغه — أسوأ الحالات إعادة احتساب مزدوجة
            // (يُبقّي السقف صحيحاً) لا فشل مستند سليم.
            chargedStreams.clear()
        }
        val now = totalStreamBytes.addAndGet(maxOf(decodedBytes, stream.length).toLong())
        if (now > maxTotalStreamBytes) {
            throw PdfBudgetException("document decoded-stream budget exceeded ($now > $maxTotalStreamBytes bytes)")
        }
    }

    companion object {
        /** سقف ناتج stream واحد (64 MiB): كافٍ لأي مستند نصي حقيقي. */
        const val DEFAULT_MAX_STREAM_BYTES = 64 shl 20

        /**
         * ميزانية الفك الكلية للمستند (256 MiB): تقيّد العمل لا الذاكرة —
         * الذروة تبقى محدودة بسقف المرحلة؛ هذا يمنع مليون عملية فك صغيرة
         * تستهلك CPU إلى ما لا نهاية.
         */
        const val DEFAULT_MAX_TOTAL_STREAM_BYTES = 256L shl 20

        /** أقصى طول سلسلة مرشحات: السلاسل الحقيقية 1-3، و8 حصن ضد التداخل. */
        const val DEFAULT_MAX_FILTER_CHAIN = 8

        /** أقصى مداخل xref: مستندات عملية < 100 ألف؛ سقف الذاكرة الخام للأقصى. */
        const val DEFAULT_MAX_XREF_ENTRIES = 500_000

        /** أقصى مدخل /N لتيار كائنات (قبل التخصيص الحرصي). */
        const val DEFAULT_MAX_OBJECTS_PER_STREAM = 16_384

        /** سقف مخزن الكائنات (LRU): يمنع نموه مع عمر المستند بلا حد. */
        const val DEFAULT_MAX_CACHED_OBJECTS = 100_000

        /** سقف مداخل CMap: المجال الكامل للرموز ذات البايتين = 65536. */
        const val DEFAULT_MAX_CMAP_ENTRIES = 65_536

        /** مجموع محتوى صفحة واحدة (كل مداخل /Contents معاً) — وهو أيضاً سقف
         *  فك XObjects النمطية (Do) في معترض الصفحة. */
        const val DEFAULT_MAX_PAGE_CONTENT_BYTES = 32 shl 20

        /** سقف جرد التيارات المميزة (ذاكرة الجرد نفسها محدودة). */
        private const val MAX_TRACKED_STREAMS = 1_000_000
    }
}

/** تجاوز ميزانية الموارد: يوقف استخراج المستند/الصفحة كاملاً (لا يُبتلع). */
class PdfBudgetException(message: String) : PdfFormatException(message)

package org.openfilemanager.core.pdf

import java.util.concurrent.atomic.AtomicLong

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

    /** يسجل بايتات فك stream **متميز** (موضعه وطوله) مرة واحدة في ميزانية المستند، ويُفشل عند تجاوز [maxTotalStreamBytes]. */
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

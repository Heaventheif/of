package org.openfilemanager.core

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** نتيجة عملية ضغط/استخراج موحّدة (نفس دلالات [FileOpStats] مع مسار الناتج). */
data class ArchiveOpStats(
    val ok: Int,
    val failed: Int,
    val skipped: Int,
    val finalPath: Path?
) {
    val total: Int get() = ok + failed + skipped
}

/**
 * [0.7.9] محرك عمليات الضغط/الاستخراج فوق نفس عقد [OpJournal] الموحد الذي
 * يستخدمه [FileOpEngine]. قبل: كل عملية كانت تُنشئ OpRecord يدوياً بعنصر
 * واحد فقط (sources.firstOrNull)، فلا سجل كامل ولا استرداد. الآن:
 * - الضغط: عنصر OpItemRecord لكل مصدر (ملف أو جذر شجرة) — سجل كامل قابل
 *   للفحص، وINTERERRUPTED عند الانقطاع يعرف مصادره كاملة.
 * - الاستخراج (غير المقسم): عنصر لكل مدخل من معاينة [ArchiveInspector.list]
 *   قبل البدء؛ تُسجَّل حالة كل مدخل للشفافية والتقدم. الاستخراج يُنشر ذرياً
 *   كمجلد كامل، لذلك الاستئناف يعيد العملية كاملة ولا يعيد مدخلاً منفرداً.
 * - الاستخراج المقسم (SplitZip): يبقى عنصراً واحداً — دمج الأجزاء عملية
 *   واحدة غير قابلة للتقسيم، فالاسترداد يعيد الدمج كاملاً (وثيقة القرار).
 *
 * لا يستورد android.* إطلاقاً (قاعدة :core الصارمة).
 */
class ArchiveOpEngine(
    private val journal: OpJournal,
    private val zipCreator: ZipCreator = ZipCreator(),
    private val limitsFor: (Path) -> ExtractLimits = { ExtractLimits() },
    private val listEntries: (Path, Charset) -> List<String> = { archive, charset ->
        ArchiveInspector.list(archive, charset, maxEntries = 100_000).entries
            .filterNot { it.isDirectory }
            .map { it.name }
    }
) {

    /**
     * ضغط المصادر إلى ZIP في [destination]. يسجّل عملية COMPRESS بعنصر لكل
     * مصدر، وينفّذ عبر [ZipCreator]، ويعيد الإحصاء النهائي.
     * [onProgress] بالبايتات، و[isCancelled] يُفحص عند كل مدخل وكل دفعة كتابة.
     */
    fun compress(
        sources: List<Path>,
        destination: Path,
        opId: String,
        onProgress: (ZipCreateProgress) -> Unit,
        isCancelled: () -> Boolean
    ): ArchiveOpStats {
        val items = sources.mapIndexed { group, source ->
            val exists = Files.exists(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            val isDir = exists && Files.isDirectory(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            OpItemRecord(
                opId = opId, index = group, group = group,
                itemKind = if (isDir) OpItemKind.DIR_CREATE else OpItemKind.FILE,
                state = if (exists) ItemState.PENDING else ItemState.SKIPPED,
                src = source.toString(), dst = destination.toString(),
                error = if (exists) "" else "المصدر غير موجود"
            )
        }
        val record = OpRecord(
            id = opId, kind = OpKind.COMPRESS, state = OpState.RUNNING,
            sources = sources.map { it.toString() },
            targetDir = destination.parent?.toString() ?: "",
            createdAt = System.currentTimeMillis()
        )
        journal.startOp(record, items)
        val validItems = items.filter { it.state == ItemState.PENDING }
        return try {
            val created = zipCreator.create(
                validItems.map { Paths.get(it.src) }, destination,
                onProgress = onProgress, isCancelled = isCancelled
            )
            // [0.7.9] DONE للمصادر الصالحة فقط — الغائب يبقى SKIPPED ولا يُحسب نجاحاً.
            validItems.forEach { journal.updateItem(opId, it.index, ItemState.DONE) }
            journal.finishOp(opId, OpState.FINISHED, validItems.size, 0, items.size - validItems.size, "أُنشئ الأرشيف")
            ArchiveOpStats(ok = validItems.size, failed = 0, skipped = items.size - validItems.size, finalPath = created)
        } catch (e: OperationCancelledException) {
            // الانقطاع يترك غير المنفذ PENDING — الاسترداد يعيد المحاولة
            // للمداخل المعلّقة فقط (انظر extract).
            journal.finishOp(opId, OpState.CANCELLED, 0, 0, items.size, "أُلغيت")
            throw e
        } catch (e: Exception) {
            journal.finishOp(opId, OpState.FAILED, 0, items.size, 0, e.message ?: "فشل الضغط")
            ArchiveOpStats(ok = 0, failed = items.size, skipped = 0, finalPath = null)
        }
    }

    /**
     * استخراج [archive] إلى داخل [targetDir]. يسجّل عملية EXTRACT:
     * - غير المقسم: عنصر لكل مدخل ملف (من المعاينة) باسم المدخل في [extra].
     * - المقسم (يقرره المستدعي عبر [split]): عنصر واحد للعملية كلها.
     * عند الإلغاء: المداخل غير المنفذة تبقى PENDING، ويعيدها
     * [pendingEntries] للاستئناف الجزئي عبر EntryFilter.only.
     */
    fun extract(
        archive: Path,
        targetDir: Path,
        charset: Charset,
        opId: String,
        split: Boolean,
        onProgress: (entries: Int, bytes: Long) -> Unit,
        checkCancelled: () -> Unit,
        filter: EntryFilter = EntryFilter.ALL,
        finalNameOverride: String? = null,
        /** ≠ null: العناصر المحددة توضع مباشرة في [targetDir] (نقل/نسخ من أرشيف) لا في مجلد باسم الأرشيف. */
        placeSelectedDirectly: Collection<String>? = null
    ): ArchiveOpStats {
        val itemNames: List<String> = if (split) {
            emptyList() // عنصر واحد أدناه يمثل الدمج والاستخراج معاً
        } else {
            runCatching { listEntries(archive, charset) }
                .getOrDefault(emptyList())
                .filter { filter.matches(it) }
        }
        val items = if (split) {
            listOf(
                OpItemRecord(
                    opId = opId, index = 0, group = 0,
                    itemKind = OpItemKind.FILE, state = ItemState.PENDING,
                    src = archive.toString(), dst = targetDir.toString()
                )
            )
        } else {
            itemNames.mapIndexed { i, name ->
                OpItemRecord(
                    opId = opId, index = i, group = 0,
                    itemKind = OpItemKind.FILE, state = ItemState.PENDING,
                    src = archive.toString(), dst = targetDir.toString(), extra = name
                )
            }
        }
        val record = OpRecord(
            id = opId, kind = OpKind.EXTRACT, state = OpState.RUNNING,
            sources = listOf(archive.toString()), targetDir = targetDir.toString(),
            createdAt = System.currentTimeMillis()
        )
        journal.startOp(record, items)
        val effectiveFilter = if (filter === EntryFilter.ALL) EntryFilter.ALL else filter
        // [0.7.9] كل مدخل يكتمل كتابته يُعلَّم DONE فوراً — عند الإلغاء يبقى
        // غير المنفذ PENDING فيسأل الاسترداد عن المعلّق لا عن الكل.
        val indexByName = items.associate { it.extra to it.index }
        return try {
            val report = if (split) {
                val limits = limitsFor(targetDir)
                SplitZip.extract(
                    archive, targetDir, limits, charset,
                    onProgress = onProgress, checkCancelled = checkCancelled,
                    filter = effectiveFilter, finalNameOverride = finalNameOverride
                )
            } else {
                ArchiveExtractor(limitsFor(targetDir), charset).extract(
                    archive, targetDir,
                    onProgress = onProgress, checkCancelled = checkCancelled, filter = effectiveFilter,
                    finalNameOverride = finalNameOverride,
                    placeSelectedDirectly = placeSelectedDirectly,
                    onEntryWritten = { name -> indexByName[name]?.let { journal.updateItem(opId, it, ItemState.DONE) } }
                )
            }
            items.forEach { if (it.state == ItemState.PENDING) journal.updateItem(opId, it.index, ItemState.DONE) }
            journal.finishOp(
                opId, OpState.FINISHED, report.written, 0, report.skipped.size,
                "استُخرج ${report.written} عنصراً"
            )
            ArchiveOpStats(
                ok = report.written, failed = 0, skipped = report.skipped.size,
                finalPath = report.finalPath
            )
        } catch (e: OperationCancelledException) {
            journal.finishOp(opId, OpState.CANCELLED, 0, 0, items.size, "أُلغيت")
            throw e
        } catch (e: Exception) {
            // A listing failure yields an empty item list; never report zero failures.
            val failedCount = maxOf(items.size, 1)
            journal.finishOp(opId, OpState.FAILED, 0, failedCount, 0, e.message ?: "فشل الاستخراج")
            ArchiveOpStats(ok = 0, failed = failedCount, skipped = 0, finalPath = null)
        }
    }

    /**
     * المداخل التي لم تصل DONE/SKIPPED في عملية استخراج سابقة.
     * تُعرض للتشخيص والتقدم فقط؛ لا تُمرّر كمرشح استئناف لأن نشر الاستخراج
     * ذري على مستوى المجلد، والاستئناف الآمن يعيد الاستخراج الكامل.
     */
    fun pendingEntries(journal: OpJournal, opId: String): Set<String> =
        journal.items(opId)
            .filter { it.state == ItemState.PENDING || it.state == ItemState.RUNNING }
            .map { it.extra }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * استئناف استخراج منقطع.
     *
     * قرار موثق: الاستئناف يعيد الاستخراج **كاملاً** على التحديد الأصلي —
     * الاستخراج ذري (مجلد مؤقت ثم نقل واحد)، فالمقطوع يترك بلا مجلد نهائي
     * إطلاقاً؛ إعادة المداخل المعلّقة فقط كانت تنتج مجلداً ناقصاً. عناصر
     * السجل لكل مدخل تبقى للشفافية (أيها كان قد نُفذ قبل القطع) والتقدم،
     * و[pendingEntries] واجهة فحص لا أساس تصفية.
     */
    fun resumeExtract(
        archive: Path,
        targetDir: Path,
        charset: Charset,
        opId: String,
        split: Boolean,
        onProgress: (entries: Int, bytes: Long) -> Unit,
        checkCancelled: () -> Unit,
        filter: EntryFilter = EntryFilter.ALL
    ): ArchiveOpStats {
        val previous = journal.op(opId) ?: throw java.io.IOException("لا توجد عملية استخراج بهذا المعرف: $opId")
        if (previous.kind != OpKind.EXTRACT) throw java.io.IOException("المعرف لا يخص عملية استخراج")
        if (previous.state !in setOf(OpState.CANCELLED, OpState.INTERRUPTED, OpState.FAILED)) {
            throw java.io.IOException("لا يمكن استئناف استخراج بحالة ${previous.state}")
        }
        // This is an explicit full restart of an atomic extraction, not an
        // implicit overwrite by startOp. The prior journal is removed only
        // after the caller has selected Resume and the old run is terminal.
        journal.remove(opId)
        return extract(archive, targetDir, charset, opId, split, onProgress, checkCancelled, filter)
    }
}

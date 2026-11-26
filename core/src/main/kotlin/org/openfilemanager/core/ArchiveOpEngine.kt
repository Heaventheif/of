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

/** محرك عمليات الضغط/الاستخراج فوق نفس عقد [OpJournal] الموحد الذي يستخدمه [FileOpEngine]. */
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
                error = if (exists) "" else CoreOpCode.SOURCE_MISSING
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
            // DONE للمصادر الصالحة فقط — الغائب يبقى SKIPPED ولا يُحسب نجاحاً.
            validItems.forEach { journal.updateItem(opId, it.index, ItemState.DONE) }
            journal.finishOp(opId, OpState.FINISHED, validItems.size, 0, items.size - validItems.size, CoreOpCode.ARCHIVE_CREATED)
            ArchiveOpStats(ok = validItems.size, failed = 0, skipped = items.size - validItems.size, finalPath = created)
        } catch (e: OperationCancelledException) {
            // الانقطاع يترك غير المنفذ PENDING — الاسترداد يعيد المحاولة
            // للمداخل المعلّقة فقط (انظر extract).
            journal.finishOp(opId, OpState.CANCELLED, 0, 0, items.size, CoreOpCode.CANCELLED)
            throw e
        } catch (e: Exception) {
            journal.finishOp(opId, OpState.FAILED, 0, items.size, 0, e.message ?: CoreOpCode.COMPRESS_FAILED)
            ArchiveOpStats(ok = 0, failed = items.size, skipped = 0, finalPath = null)
        }
    }

    /** استخراج [archive] إلى داخل [targetDir]. */
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
        // كل مدخل يكتمل كتابته يُعلَّم DONE فوراً — عند الإلغاء يبقى
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
                CoreOpCode.EXTRACTED
            )
            ArchiveOpStats(
                ok = report.written, failed = 0, skipped = report.skipped.size,
                finalPath = report.finalPath
            )
        } catch (e: OperationCancelledException) {
            journal.finishOp(opId, OpState.CANCELLED, 0, 0, items.size, CoreOpCode.CANCELLED)
            throw e
        } catch (e: Exception) {
            // A listing failure yields an empty item list; never report zero failures.
            val failedCount = maxOf(items.size, 1)
            journal.finishOp(opId, OpState.FAILED, 0, failedCount, 0, e.message ?: CoreOpCode.EXTRACT_FAILED)
            ArchiveOpStats(ok = 0, failed = failedCount, skipped = 0, finalPath = null)
        }
    }
}

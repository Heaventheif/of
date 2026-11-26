package org.openfilemanager.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** منسّق الاسترداد/السجل (مستخرج من [FileOpEngine] بلا تغيير سلوك): استئناف عملية انقطعت بإعادة لعب العناصر بحالتها المسجلة — المنجز (DONE) والمتخطى. */
internal class OpRecoveryCoordinator(
    private val journal: OpJournal,
    private val transferExecutor: OpTransferExecutor,
    private val deleteExecutor: OpDeleteExecutor,
    private val onProgress: (FileOpProgress) -> Unit,
    private val parsePath: (String) -> Path,
    private val cancelCheck: () -> Unit
) {
    /** استئناف عملية انقطعت (موت العملية أو مهلة الخدمة): يعيد العناصر غير المنجزة فقط. */
    suspend fun resume(opId: String, policy: ConflictPolicy): FileOpStats {
        val record = journal.op(opId) ?: throw IOException(CoreOpCode.message(CoreOpCode.NO_OPERATION, opId))
        if (record.state != OpState.RUNNING && record.state != OpState.INTERRUPTED) {
            throw IOException(CoreOpCode.OP_ALREADY_FINISHED)
        }
        val items = journal.items(opId)
        if (items.isEmpty()) throw IOException(CoreOpCode.message(CoreOpCode.OP_EMPTY, opId))
        if (record.kind == OpKind.RENAME) throw IOException(CoreOpCode.RENAME_RECOVERED_BY_ENGINE)
        return try {
            run(record, items, policy)
        } catch (e: OperationCancelledException) {
            journal.finishOp(opId, OpState.CANCELLED, 0, 0, 0, CoreOpCode.CANCELLED)
            throw e
        } catch (e: Exception) {
            journal.finishOp(opId, OpState.FAILED, 0, 0, 0, e.message ?: CoreOpCode.RESUME_FAILED)
            throw e
        }
    }

    /** تشغيل الدفعة كاملة: إعادة لعب الحالات، التنفيذ بالمجموعات، ثم الإنهاء على السجل (ملخص + تقليم). */
    internal suspend fun run(record: OpRecord, items: List<OpItemRecord>, defaultPolicy: ConflictPolicy): FileOpStats {
        val ctx = RunContext(record, items.size, items.sumOf { it.size }, onProgress)

        items.forEach { item ->
            when (item.state) {
                ItemState.DONE -> { ctx.c.ok++; ctx.c.bytesDone += item.size }
                ItemState.SKIPPED -> ctx.c.skipped++
                else -> {}
            }
        }

        // تجميع بالقيمة لا بالتجاور: عناصر الدمج المسجلة في السجل (فهارس أعلى
        // من نطاق التعداد) تأتي في نهاية قائمة العناصر بعد مجموعات لاحقة،
        // ومعنى group ثابت: كل مصدر مستوى أعلى ومحتواه مجموعة واحدة.
        val groups = LinkedHashMap<Int, MutableList<OpItemRecord>>()
        items.forEach { groups.getOrPut(it.group) { ArrayList() }.add(it) }
        for (groupItems in groups.values) {
            cancelCheck()
            groupItems.sortBy { it.index }
            val root = groupItems.first()

            val groupNeedsWork = groupItems.any { it.state != ItemState.DONE && it.state != ItemState.SKIPPED }
            if (groupNeedsWork) {
                when (root.itemKind) {
                    OpItemKind.TREE_DELETE -> deleteExecutor.runTreeDelete(ctx, root)
                    OpItemKind.TREE_MOVE -> transferExecutor.runTreeMove(ctx, root, defaultPolicy)
                    else -> transferExecutor.runCopyGroup(ctx, groupItems, defaultPolicy)
                }
            }
        }

        if (record.kind == OpKind.MOVE) {
            ctx.emptiedSourceDirs.sortedByDescending { it.length }.forEach { dir ->
                tryOrNull { Files.deleteIfExists(parsePath(dir)) }
            }
        }

        val stats = FileOpStats(ctx.c.ok, ctx.c.failed, ctx.c.skipped)
        journal.finishOp(
            record.id, OpState.FINISHED,
            stats.ok, stats.failed, stats.skipped,
            summaryMessage(record.kind, stats)
        )
        journal.prune()
        return stats
    }

    private fun summaryMessage(kind: OpKind, stats: FileOpStats): String {
        // الملخص يخزّن رمز العملية والإحصائيات كرسالة رمزية:
        // "OP_SUMMARY\u001FCOPY\u001F1\u001F0\u001F0" — تُترجم في طبقة التطبيق.
        val label = when (kind) {
            OpKind.COPY -> CoreOpCode.KIND_COPY
            OpKind.MOVE -> CoreOpCode.KIND_MOVE
            OpKind.DELETE -> CoreOpCode.KIND_DELETE
            else -> CoreOpCode.KIND_OPERATION
        }
        return CoreOpCode.message(CoreOpCode.OP_SUMMARY, label, stats.ok, stats.failed, stats.skipped)
    }
}

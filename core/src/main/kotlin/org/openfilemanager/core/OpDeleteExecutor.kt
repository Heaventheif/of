package org.openfilemanager.core

import java.nio.file.Path

/** منفّذ حذف الأشجار (مستخرج من [FileOpEngine] بلا تغيير سلوك): حذف شجرة المصدر كاملة عبر [AtomicIO.deleteTree] مع نقطة تفتيش RUNNING قبل الحذف وDONE. */
internal class OpDeleteExecutor(
    private val journal: OpJournal,
    private val parsePath: (String) -> Path,
    private val cancelCheck: () -> Unit
) {
    /** حذف شجرة مصدر واحدة: تقدّم باسم الجذر، RUNNING، حذف عبر [AtomicIO.deleteTree]، ثم DONE. */
    internal fun runTreeDelete(ctx: RunContext, item: OpItemRecord) {
        ctx.progress(item.index, parsePath(item.src).fileName.toString())
        journal.markRunning(ctx, item)
        try {
            AtomicIO.deleteTree(parsePath(item.src), checkCancelled = cancelCheck)
            journal.updateItem(ctx.record.id, item.index, ItemState.DONE)
            ctx.c.ok++
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: Exception) {
            journal.failItem(ctx, item, e.message ?: CoreOpCode.DELETE_FAILED)
        }
    }
}

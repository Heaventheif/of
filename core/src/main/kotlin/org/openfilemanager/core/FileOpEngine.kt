package org.openfilemanager.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/** أُلغيت العملية بطلب المستخدم. النسخ/النقل لا ينشران ملفاً جزئياً؛ أما الحذف فقد يكون جزئياً ويُستأنف من السجل. */
class OperationCancelledException : RuntimeException("operation cancelled")

/** نقل/نسخ عنصر إلى داخل نفسه (قارن toRealPath ثم st_dev/st_ino). */
class TargetInsideSourceException(message: String) : IOException(message)

/** سياسة التعارض الافتراضية للعملية (الإعداد 7.5). ASK تعني حواراً للمستخدم. */
enum class ConflictPolicy { ASK, REPLACE, KEEP_BOTH, SKIP }

/** قرار المستخدم لحالة تعارض واحدة. */
data class ConflictDecision(val policy: ConflictPolicy, val applyToAll: Boolean)

/** تعارض يُعرض على المستخدم عند السياسة ASK. */
data class ConflictRequest(
    val source: Path,
    val target: Path,
    val isDirectory: Boolean
)

data class FileOpProgress(
    val itemIndex: Int,
    val itemCount: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val current: String
)

data class FileOpStats(val ok: Int, val failed: Int, val skipped: Int) {
    val total: Int get() = ok + failed + skipped
}

/** محرك الدفعات: نسخ ونقل وحذف فوق [AtomicIO] مع سجل قابل للاسترداد. */
class FileOpEngine(
    private val journal: OpJournal,
    private val sameVolume: (Path, Path) -> Boolean = { a, b -> a.fileSystem == b.fileSystem },
    private val resolveConflict: suspend (ConflictRequest) -> ConflictDecision =
        { ConflictDecision(ConflictPolicy.SKIP, true) },
    private val onProgress: (FileOpProgress) -> Unit = {},
    private val isCancelled: () -> Boolean = { false },
    private val dropPageCache: (Path) -> Unit = {},
    /**
     * سقف عناصر التعداد القابل للضبط: تجاوزه يعني شجرة كبيرة جداً على
     * الذاكرة — تُفشل العملية برسالة واضحة قبل استهلاك الذاكرة لا بعده.
     */
    private val maxEnumeratedItems: Int = DEFAULT_MAX_ENUMERATED_ITEMS,
    /**
     * يفك مسارات السجل عند التنفيذ والاستئناف. الإنتاج يبقي الافتراضي (نظام ملفات الجهاز)،
     * والاختبارات تحقن Jimfs؛ فالمسار النصي وحده لا يكفي لتحديد نظام الملفات.
     */
    private val parsePath: (String) -> Path = { Paths.get(it) }
) {
    private fun checkCancelled() {
        if (isCancelled()) throw OperationCancelledException()
    }

    private val treeEnumerator =
        OpTreeEnumerator(maxEnumeratedItems, sameVolume, cancelCheck = { checkCancelled() })

    private val transferExecutor = OpTransferExecutor(
        journal, treeEnumerator, maxEnumeratedItems, dropPageCache, resolveConflict, parsePath,
        cancelCheck = { checkCancelled() }
    )

    private val deleteExecutor = OpDeleteExecutor(
        journal, parsePath, cancelCheck = { checkCancelled() }
    )

    private val recoveryCoordinator = OpRecoveryCoordinator(
        journal, transferExecutor, deleteExecutor, onProgress, parsePath,
        cancelCheck = { checkCancelled() }
    )

    suspend fun copy(sources: List<Path>, targetDir: Path, policy: ConflictPolicy, opId: String = newOpId()): FileOpStats =
        batch(OpKind.COPY, sources, targetDir, policy, opId)

    suspend fun move(sources: List<Path>, targetDir: Path, policy: ConflictPolicy, opId: String = newOpId()): FileOpStats =
        batch(OpKind.MOVE, sources, targetDir, policy, opId)

    suspend fun delete(sources: List<Path>, opId: String = newOpId()): FileOpStats =
        batch(OpKind.DELETE, sources, sources.firstOrNull()?.parent ?: parsePath("."), ConflictPolicy.SKIP, opId)

    /** استئناف عملية انقطعت (موت العملية أو مهلة الخدمة): يعيد العناصر غير المنجزة فقط. */
    suspend fun resume(opId: String, policy: ConflictPolicy): FileOpStats =
        recoveryCoordinator.resume(opId, policy)

    private suspend fun batch(
        kind: OpKind,
        sources: List<Path>,
        targetDir: Path,
        policy: ConflictPolicy,
        opId: String
    ): FileOpStats {
        if (sources.isEmpty()) return FileOpStats(0, 0, 0)
        try {
            if (kind != OpKind.DELETE) {
                // افحص المسار قبل إنشائه: createDirectories أولاً كان يترك مجلداً فارغاً داخل المصدر حتى عندما نرفض النسخ/النقل إلى داخل الشجرة نفسها.
                OpPathValidation.ensureTargetOutsideSources(targetDir, sources)
                Files.createDirectories(targetDir)
                // تحقق ثانية بعد الإنشاء للتقاط تبدّل المسار أو مكوّن رابط
                // رمزي خلال نافذة التهيئة.
                OpPathValidation.ensureTargetOutsideSources(targetDir, sources)
            }
            val items = treeEnumerator.enumerate(opId, kind, sources, targetDir, lazySizes = kind == OpKind.MOVE)
            val record = OpRecord(
                id = opId, kind = kind, state = OpState.RUNNING,
                sources = sources.map { it.toString() },
                targetDir = if (kind == OpKind.DELETE) "" else targetDir.toString(),
                createdAt = System.currentTimeMillis(),
                totalBytes = items.sumOf { it.size }
            )
            journal.startOp(record, items)
            return run(record, items, policy)
        } catch (e: OperationCancelledException) {
            journal.finishOp(opId, OpState.CANCELLED, 0, 0, 0, CoreOpCode.CANCELLED)
            throw e
        } catch (e: Exception) {
            journal.finishOp(opId, OpState.FAILED, 0, 0, 0, e.message ?: CoreOpCode.FAILED)
            throw e
        }
    }

    // ---------- التنفيذ ----------

    private suspend fun run(record: OpRecord, items: List<OpItemRecord>, defaultPolicy: ConflictPolicy): FileOpStats =
        recoveryCoordinator.run(record, items, defaultPolicy)

    internal fun moveReplacing(
        src: Path,
        dst: Path,
        replaceExisting: Boolean,
        checkCancelled: () -> Unit = {},
        move: (src: Path, dst: Path, replace: Boolean) -> Unit =
            { s, d, replace -> AtomicIO.moveAtomicOrFallback(s, d, replace) }
    ) = transferExecutor.moveReplacing(src, dst, replaceExisting, checkCancelled, move)

    companion object {
        /**
         * سقف التعداد الافتراضي: عناصر الدفعات (ملفات/مجلدات) فوقه تُرفض
         * العملية برسالة واضحة بدل استهلاك ذاكرة غير محدود. قابل للضبط عبر
         * [FileOpEngine.maxEnumeratedItems] لمن يحتاج عمليات أكبر.
         */
        const val DEFAULT_MAX_ENUMERATED_ITEMS = 500_000

        fun newOpId(): String = UUID.randomUUID().toString().take(8)
    }
}

internal inline fun <T> tryOrNull(block: () -> T): T? = try {
    block()
} catch (e: Exception) {
    null
}

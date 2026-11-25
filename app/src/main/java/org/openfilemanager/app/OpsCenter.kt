package org.openfilemanager.app

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openfilemanager.core.ConflictDecision
import org.openfilemanager.core.ConflictRequest
import org.openfilemanager.core.FileOpJournal
import org.openfilemanager.core.OpKind
import org.openfilemanager.core.OpRecovery
import org.openfilemanager.core.OpRecord
import org.openfilemanager.core.OpState
import org.openfilemanager.core.ReplaceBackupRecovery
import org.openfilemanager.core.ReplaceBackupStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Progress of the active operation as the app renders it (header line + notification). */
data class OpProgressInfo(
    val opId: String,
    val label: String,
    val itemIndex: Int,
    val itemCount: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val currentFile: String
)

/**
 * A conflict awaiting the user's decision: the service waits on a
 * CompletableDeferred and the UI completes it from the dialog.
 */
class PendingConflict(val request: ConflictRequest, val deferred: CompletableDeferred<ConflictDecision>)

/**
 * Outcome of an operation that actually finished: a structured signal instead
 * of parsing translated strings. `kind` is null on resume when the original
 * record kind is unknown. The UI relies on it to clear the cut clipboard on
 * a successful move (not merely on enqueueing), and [opId] ties it to its own
 * operation so one result never lands on a newer clipboard.
 */
data class OpOutcome(
    val opId: String?,
    val kind: OpKind?,
    val ok: Int,
    val failed: Int,
    val skipped: Int
)

/**
 * Operations bridge between the service and the UI at the whole-operation
 * level, with no Activity or UI-context reference so nothing leaks. The UI
 * collects the flows only while STARTED (repeatOnLifecycle) so no conflict
 * dialog appears after leaving; the conflict times out to a safe skip.
 *
 * No longer the former global object: a single instance is created in
 * [AppContainer] and injected into the service, UI and ViewModel via manual
 * DI. The flows are read-only from outside (immutable StateFlow/SharedFlow) —
 * the service publishes through [publishProgress], [publishConflict],
 * [publishResult] and [publishOutcome], so no caller can write operation
 * state outside the documented path.
 */
class OpsCenter(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Single operation journal: used by the service and boot-time recovery. */
    val journal: FileOpJournal by lazy {
        FileOpJournal(File(appContext.filesDir, "ops").toPath())
    }

    /**
     * سجل دائم لأوامر استبدال مجلد الوجهة عند استخراج الأرشيف (REPLACE):
     * كل نسخة احتياطية تحتوي بيانات المستخدم تُسجّل هنا قبل نقل المجلد
     * الأصلي إليها، فيستطيع استرداد الإقلاع ([ReplaceBackupRecovery]) حسم
     * مصيرها — لا منظّف المؤقتات الذي كان يمحوها عند موت العملية (0.8.1).
     */
    val replaceBackups: ReplaceBackupStore by lazy {
        ReplaceBackupStore(File(appContext.filesDir, "ops").resolve("replace-backups").toPath())
    }

    /** The active operation right now, or null while idle. */
    private val _progress = MutableStateFlow<OpProgressInfo?>(null)
    val progress: StateFlow<OpProgressInfo?> = _progress.asStateFlow()

    /** Final result messages (snackbar + folder re-read). */
    private val _results = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val results: SharedFlow<String> = _results.asSharedFlow()

    /** Structured outcomes for operations that actually completed. */
    private val _outcomes = MutableSharedFlow<OpOutcome>(extraBufferCapacity = 16)
    val outcomes: SharedFlow<OpOutcome> = _outcomes.asSharedFlow()

    /**
     * (F-14) نتائج غير مقروءة: كانت خانة واحدة تُداس بنتيجة أحدث فتضيع قبل
     * عرضها. الآن طابور محدود بحدّ [MAX_PENDING_RESULTS]: كل نتيجة تُعرض
     * بالتسلسل ولا يُطوَى شيء؛ [OpJournal] يبقى المصدر الدائم للعمليات،
     * ويعاد استرجاع ما فات أثناء موت العملية من [refreshRecoverable].
     * [seq] مُعرّف فريد للنتيجة، و[acknowledgeResult] لا تُنادى إلا بعد
     * عرض Snackbar فعلاً (من حلقة العرض في OfmApp) فلا تفقد نتيجة
     * بإعادة إنشاء الواجهة.
     */
    data class PendingResult(val seq: Long, val opId: String?, val message: String, val outcome: OpOutcome?, val isError: Boolean = false)

    private val _pendingResults = MutableStateFlow<List<PendingResult>>(emptyList())
    val pendingResults: StateFlow<List<PendingResult>> = _pendingResults.asStateFlow()

    private val resultSeq = AtomicLong(1)

    /** معرّفات نتائج نُشرت في هذه العملية: يمنع استرجاع المُتحقق منها مرة ثانية. */
    private val publishedOpIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** أقدم النتائج غير المقرّ بها، أو null عند الفراغ. */
    fun nextUnacknowledged(): PendingResult? = _pendingResults.value.firstOrNull()

    /** الإقرار بعد عرض النتيجة (ظهر Snackbar فعلاً): تُحذف من الطابور. */
    fun acknowledgeResult(seq: Long) {
        synchronized(this) { _pendingResults.value = _pendingResults.value.filterNot { it.seq == seq } } // same lock as publishResult
    }

    /** Current pending conflict; one active operation, so one conflict slot suffices. */
    private val _conflict = MutableStateFlow<PendingConflict?>(null)
    val conflict: StateFlow<PendingConflict?> = _conflict.asStateFlow()

    // ---------- Publishing (service only): state written through one path ----------

    /** Update the active operation progress (null = idle). */
    fun publishProgress(info: OpProgressInfo?) {
        _progress.value = info
    }

    /** Show/hide the pending conflict. */
    fun publishConflict(pending: PendingConflict?) {
        _conflict.value = pending
    }

    /** Final result message (snackbar + re-read): بث فوري + طابور النتائج المعلّقة (F-14). */
    fun publishResult(message: String, opId: String? = null, isError: Boolean = false) {
        _results.tryEmit(message)
        synchronized(this) {
            val cur = _pendingResults.value
            _pendingResults.value = cur + PendingResult(resultSeq.getAndIncrement(), opId, message, null, isError)
        }
        if (opId != null) publishedOpIds += opId
        // حدّ الطابور: أقدم نتيجة تُرمى (مع سجل) بدل نمو بلا حد — العرض متأخر
        // جداً وسيقرأه المستخدم من سجل العمليات عند الحاجة.
        if (_pendingResults.value.size > MAX_PENDING_RESULTS) {
            _pendingResults.value = _pendingResults.value.takeLast(MAX_PENDING_RESULTS)
        }
    }

    /** Structured outcome for a finished operation: بث فوري + ربطه بنتيجته المعلّقة (F-14). */
    fun publishOutcome(outcome: OpOutcome) {
        _outcomes.tryEmit(outcome)
        outcome.opId?.let { publishedOpIds += it }
        synchronized(this) {
            val cur = _pendingResults.value
            // اربطه بمدخلة معرّفها نفسه بلا ناتج، وإلا فأقدم مدخلة بلا ناتج:
            // النتائج لا تُداس فوق بعضها.
            val idx = cur.indexOfLast { it.outcome == null && it.opId == outcome.opId }
                .takeIf { it >= 0 } ?: cur.indexOfFirst { it.outcome == null }
            if (idx >= 0) {
                val updated = cur[idx].copy(outcome = outcome)
                _pendingResults.value = cur.toMutableList().also { it[idx] = updated }
            }
        }
    }

    /**
     * Cancel the whole queue (cancel button): covers the running operation and
     * every request queued behind it. Cleared when the UI successfully sends a
     * new request, or when a new service cycle starts.
     */
    @Volatile
    var queueCancelRequested: Boolean = false
        private set

    /**
     * Operation ids the platform asked to stop at the dataSync timeout:
     * cancellation is keyed by operation id, not a global flag, so a new UI
     * request cannot wipe a stop aimed at a running operation, and the stop
     * never crosses into the next queued operation. The id is removed when a
     * new run/resume starts with it.
     */
    private val stopRequestedOps: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** User cancellation: the running operation and everything queued behind it. */
    fun requestCancelQueue() {
        queueCancelRequested = true
    }

    /** Stop a specific operation (platform timeout or the cancel button). */
    fun requestStopOp(opId: String?) {
        if (opId != null) stopRequestedOps += opId
    }

    /** Was a stop requested for the operation with this id? */
    fun isStopRequested(opId: String): Boolean = opId in stopRequestedOps

    /** A run/resume starting with a previously stopped id: it does not inherit the earlier stop. */
    fun clearStopRequest(opId: String) {
        stopRequestedOps -= opId
    }

    /**
     * A new UI request whose intent was sent successfully: clears the previous
     * queue cancellation without disturbing it. Does not touch the timeout stop
     * aimed at the running operation (that one has its own id).
     */
    fun clearQueueCancel() {
        queueCancelRequested = false
    }

    /** New service cycle: a clean start that inherits no flags from the previous one. */
    fun resetCancel() {
        queueCancelRequested = false
        stopRequestedOps.clear()
    }

    /**
     * Ids of operations currently executing in the live app process: recorded
     * when the service starts each one and cleared when it finishes. A RUNNING
     * journal record present here is not "interrupted" — the service is writing
     * it right now; announcing it interrupted during activity recreation
     * (rotation/language/task switch) showed the recovery dialog falsely and
     * its cancel button deleted temps of a live operation.
     */
    private val activeOpIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * معرّفات العمليات المُرسَل لها طلب استئناف في هذه العملية الحية: نافذة
     * السباق (enqueue ← markOpActive) كانت تعرض الحوار ثانية فيعيد المستخدم
     * الاستئناف مرتين. تُمسح عند خاتمة العملية أو فشل الجدولة.
     */
    private val resumeDispatchedOps: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** تسجيل أن طلب استئنافاً رُسل للعملية (يمنع إرسال ثانٍ حتى تنتهي). */
    fun markResumeDispatched(opId: String) {
        resumeDispatchedOps += opId
    }

    /** استئناف فشل جدوله أو انتهت عملته: يسمح بالمحاولة مجدداً. */
    fun clearResumeDispatched(opId: String) {
        resumeDispatchedOps -= opId
    }

    /** Is a resume already dispatched for this op in the live process? */
    fun isResumeDispatched(opId: String): Boolean = opId in resumeDispatchedOps

    fun markOpActive(opId: String) {
        activeOpIds += opId
    }

    fun markOpFinished(opId: String) {
        activeOpIds -= opId
        resumeDispatchedOps -= opId
    }

    /** Operations genuinely running in this live process (read-only copy). */
    fun activeOps(): Set<String> = activeOpIds.toSet()

    /**
     * Is any service work active right now? Used to hold off the whole
     * interruption check: while the service runs an operation (progress != null)
     * every RUNNING record belongs to it or to requests in its queue — none of
     * them is "interrupted".
     */
    fun hasActiveServiceWork(): Boolean = activeOpIds.isNotEmpty() || progress.value != null

    // ---------- استرداد العمليات المنقطعة (F-01) ----------

    private val recoveryScope = CoroutineScope(SupervisorJob() + AppDispatchers.io)

    /**
     * العمليات المنقطعة القابلة للاسترداد (RUNNING/INTERRUPTED بلا تنفيذ حي):
     * مصدر واحد للواجهة (حوار الاسترداد) — مستودع العرض الوحيد لكل ما يمكن
     * استئنافه أو إلغاؤه من OpJournal.
     */
    private val _recoverable = MutableStateFlow<List<OpRecord>>(emptyList())
    val recoverable: StateFlow<List<OpRecord>> = _recoverable.asStateFlow()

    /**
     * فحص الانقطاع: يُستدعى عند بدء التطبيق وعند عودة الواجهة (ON_RESUME)
     * وعند خاتمة كل عملية. أثناء عمل الخدمة يُفرَّغ الفحص (سجل RUNNING لعملية
     * تُنفَّذ الآن ليس «منقطعاً»)، وغير القابل للاستئناف (ضغط/استخراج) يُسقط
     * صامتاً مع تنظيف مؤقتاته عبر [OpRecovery.abandonUnresumable].
     * استئناف سبق إرساله في هذه العملية (نافذة السباق) لا يُعرض ثانية.
     */
    suspend fun refreshRecoverable() {
        replayMissedResults()
        if (hasActiveServiceWork()) {
            _recoverable.value = emptyList()
            return
        }
        val ops = withContext(AppDispatchers.io) {
            runCatching { journal.ops() }.getOrDefault(emptyList())
        }
        // سباق وصول طلب جديد أثناء الفحص: أعد الفحص قبل العرض (لا حوار زور).
        if (hasActiveServiceWork()) {
            _recoverable.value = emptyList()
            return
        }
        val active = activeOps()
        // تسوية أوامر الاستبدال المعلّقة أولاً: استعادة الأصل من النسخة
        // الاحتياطية عند فشل/انقطاع الاستخراج، وحذفها فقط بعد ثبوت نجاح
        // البديل المنشور. تُنفَّذ قبل abandonUnresumable حتى لو مُيّزت
        // العملية لاحقاً CANCELLED — الحالتان غير المنجَزتين معاً.
        runCatching {
            val outcome = withContext(AppDispatchers.io) {
                ReplaceBackupRecovery.reconcile(replaceBackups, journal, active)
            }
            if (outcome.restored.isNotEmpty()) {
                publishResult(appContext.getString(R.string.replace_backup_restored), opId = null)
            }
            if (outcome.kept.isNotEmpty()) {
                publishResult(appContext.getString(R.string.replace_backup_kept), opId = null)
            }
            outcome.errors.forEach { AppLog.e("recovery", "replace-backup: $it", null) }
        }
        runCatching { OpRecovery.abandonUnresumable(journal, ops, active, serviceBusy = false) }
        val fresh = withContext(AppDispatchers.io) {
            runCatching { journal.ops() }.getOrDefault(emptyList())
        }
        _recoverable.value = OpRecovery.recoverableOps(fresh, activeOps(), serviceBusy = false)
            .filterNot { isResumeDispatched(it.id) }
    }

    /**
     * نشر نتائج العمليات المنتهية أثناء غياب عملية حية عنها (موت العملية بعد
     * كتابة سطر الإنهاء، أو اكتمال العملية بعد إغلاق الواجهة) — مرة واحدة
     * لكل عملية، بحدّ [MAX_MISSED_RESULTS] رسالة. العلامة ملف طابع زمني
     * بجانب سجل العمليات (لا حالة حساسة جديدة تخزَّن)، وما نُشر في هذه
     * العملية (publishedOpIds) لا يُعاد فلا تتكرر نتيجة بعد الاسترداد.
     */
    private suspend fun replayMissedResults() {
        if (missedResultsReplayed) return
        missedResultsReplayed = true
        val marker = File(File(appContext.filesDir, "ops"), "results-seen")
        val now = System.currentTimeMillis()
        val published = withContext(AppDispatchers.io) {
            runCatching {
                val lastSeen = marker.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
                if (lastSeen == null) {
                    // أول تشغيل بلا علامة: أنشئها بلا إزعاج المستخدم بنتائج قديمة.
                    marker.parentFile?.mkdirs()
                    marker.writeText(now.toString())
                    return@runCatching emptyList<OpRecord>()
                }
                val ops = journal.ops()
                    .filter {
                        it.finishedAt > lastSeen && it.finishedAt <= now && it.id !in publishedOpIds &&
                            (it.state == OpState.FINISHED || it.state == OpState.FAILED || it.state == OpState.CANCELLED)
                    }
                    .sortedBy { it.finishedAt }
                    .take(MAX_MISSED_RESULTS)
                marker.writeText(now.toString())
                ops
            }.getOrDefault(emptyList())
        }
        published.forEach { op ->
            val message = op.message.takeIf { it.isNotBlank() }
                ?: "${op.kind.name.lowercase()} finished: ok=${op.ok} failed=${op.failed} skipped=${op.skipped}"
            publishResult(message, opId = op.id, isError = op.state == OpState.FAILED || op.failed > 0)
        }
    }

    @Volatile
    private var missedResultsReplayed = false

    /** فحص غير معيق (لا I/O على الخيط الرئيسي): نقطة الاستدعاء من الواجهة والخدمة. */
    fun requestRecoveryCheck() {
        recoveryScope.launch { runCatching { refreshRecoverable() } }
    }

    /** أزيل العملية من قائمة الحوار بعد تنفيذ قرار المستخدم عليها. */
    fun dismissRecoverable(opId: String) {
        _recoverable.value = _recoverable.value.filterNot { it.id == opId }
    }

    private companion object {
        /** طابور النتائج غير المقرّ بها: حدّ أقصى قبل رمي الأقدم. */
        const val MAX_PENDING_RESULTS = 8

        /** أقصى نتائج فائتة تُعرض بعد موت العملية (البقية في سجل العمليات). */
        const val MAX_MISSED_RESULTS = 5
    }
}

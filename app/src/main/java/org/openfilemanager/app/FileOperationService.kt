package org.openfilemanager.app

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openfilemanager.core.ConflictDecision
import org.openfilemanager.core.ConflictPolicy
import org.openfilemanager.core.ConflictRequest
import org.openfilemanager.core.FileOpEngine
import org.openfilemanager.core.FileOpProgress
import org.openfilemanager.core.FileOpStats
import org.openfilemanager.core.OpJournal
import org.openfilemanager.core.OpKind
import org.openfilemanager.core.OpState
import org.openfilemanager.core.OperationCancelledException
import org.openfilemanager.core.TargetInsideSourceException
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** طلب عملية تُرسل إلى الخدمة. المسارات نصوص عبر Intent (Path ليس Parcelable).
 *  كل طلب يحمل معرّفه (opId) — يولّده المرسل فيعرفه، وتربط الواجهة
 *  النتيجة المهيكلة به، فلا تشوش نتيجة عملية على حافظة أحدث. */
sealed interface OpRequest {
    data class Copy(
        val sources: List<Path>,
        val targetDir: Path,
        val opId: String = FileOpEngine.newOpId()
    ) : OpRequest

    data class Move(
        val sources: List<Path>,
        val targetDir: Path,
        val opId: String = FileOpEngine.newOpId()
    ) : OpRequest

    data class Delete(val sources: List<Path>, val opId: String = FileOpEngine.newOpId()) : OpRequest
    data class Resume(val opId: String) : OpRequest

    /**
     * ضغط التحديد إلى ZIP داخل [targetDir] (5): كان يجري في viewModelScope بلا
     * إلغاء ولا تقدم ولا خدمة أمامية فيُقتل في الخلفية ويترك مؤقتات.
     * [entryNames] أسماء العناصر كما ستظهر في الأرشيف (الاسم الظاهر).
     */
    data class Compress(
        val sources: List<Path>,
        val targetDir: Path,
        val targetName: String,
        val opId: String = FileOpEngine.newOpId()
    ) : OpRequest

    /**
     * استخراج أرشيف إلى داخل [targetDir] (5): نفس مبرر Compress.
     * [charsetName] اسم ترميز أسماء مداخل الأرشيف (UTF-8 أو windows-1256)،
     * و[selected] أسماء العناصر المحددة فقط (null = الكل).
     */
    data class Extract(
        val archive: Path,
        val targetDir: Path,
        val charsetName: String,
        val selected: List<String>?,
        val opId: String = FileOpEngine.newOpId(),
        /** نقل/نسخ عناصر من أرشيف إلى مجلد: توضع مباشرة في الوجهة بلا مجلد باسم الأرشيف. */
        val placeDirectly: Boolean = false
    ) : OpRequest
}

/** خدمة العمليات الطويلة: - أمامية من نوع dataSync، تبدأ من واجهة ظاهرة فقط (قيود بدء الخدمات في 15+). */
class FileOperationService : Service() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase.applyLanguage())
    }

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.io)

    /** Injected operations bridge (operation code itself unchanged): the container's instance. */
    private val opsCenter by lazy { appContainer.opsCenter }

    /**
     * مدخل الطابور: نية من onStartCommand أو طلب مُسترد مباشرة.
     * الطلب المسترد لا يمر عبر Intent (OpRequest ليس Parcelable) ولا عبر
     * startForegroundService (الخدمة أمامية أصلاً) — يدخل القناة كما هو.
     */
    private sealed interface QueueEntry {
        data class FromIntent(val intent: Intent) : QueueEntry
        data class Recovered(val request: OpRequest) : QueueEntry
    }

    private val requests = Channel<QueueEntry>(Channel.UNLIMITED)
    private val pendingCount = AtomicInteger(0)
    private lateinit var notifications: NotificationManager

    /** معرّف العملية الجارية؛ يقرأه onTimeout من الخيط الرئيسي فيُعلّم @Volatile. */
    @Volatile
    private var activeOpId: String? = null
    private var currentLabel = ""

    /** يُكتب من onTimeout (الخيط الرئيسي) ويُقرأ من المحرك (خيط IO) — @Volatile لضمان الرؤية. */
    @Volatile
    private var timedOut = false

    /**
 * خطأ JVM قاتل (OutOfMemoryError/StackOverflowError) ليس استثناءً تشغيلياً
 * قابلاً للتعافي — بعد وقوعه تُفرَّغ بقية الطابور كطلبات «لم تبدأ» وتتوقف
 * الخدمة، فلا معالجة إضافية في حالة غير متسقة.
 */
    @Volatile
    private var fatalStop = false

    /** قفل تسلسل إدخال الطلبات/الإيقاف: يمنع سباق stopSelf مع طلب وارد (4.1). */
    private val pendingLock = Any()

    /** آخر startId استلمته الخدمة: stopSelf(lastStartId) لا يهدم طلباً أحدث. */
    @Volatile
    private var lastStartId = 0

    private val archiveCoordinator by lazy {
        ArchiveCoordinator(
            opsCenter = opsCenter,
            relaxedLimits = { appContainer.settings.archiveRelaxedLimits },
            unreadableSpaceMessage = { getString(R.string.archive_space_unreadable) },
            timedOut = { timedOut },
            setLabel = { currentLabel = it },
            onProgress = ::onProgress,
            resolveConflict = ::askConflict,
            text = { res -> getString(res) },
            textArg = { res, arg -> getString(res, arg) },
            pluralArg = { res, count, arg1, arg2 -> resources.getQuantityString(res, count, arg1, arg2) }
        )
    }

    override fun onCreate() {
        super.onCreate()
        opsCenter.resetCancel()
        notifications = getSystemService(NotificationManager::class.java)
        createChannel()
        scope.launch {
            PendingRequestStore.purgeOlderThan(filesDir, STALE_PENDING_MS)
            // استرداد الطلبات المعلّقة من تشغيل سابق قبل بدء العامل: الخدمة تُدمّر عند قتل النظام/onTimeout والنظام لا يعيد تسليم النوايا (START_NOT_STICKY) فتضيع…
            val recovered = runCatching {
                PendingRequestStore.recoverActive(filesDir, opsCenter.journal, STALE_PENDING_MS)
            }.getOrDefault(emptyList())
            for ((opId, stored) in recovered) {
                val request = stored.toOpRequest(opId) ?: continue
                synchronized(pendingLock) { pendingCount.incrementAndGet() }
                requests.trySend(QueueEntry.Recovered(request))
                AppLog.e("ops", "استُردّ طلب معلّق من تشغيل سابق ($opId)")
            }
            // Worker starts only after recovery completes, so recovery can never
            // observe an active marker written by this instance's own worker.
            worker()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // إشعار الخدمة الأمامية إجباري نظامياً فقط: أدنى أهمية، بلا نص تقدم ولا أزرار.
            // التقدم والنتيجة والإلغاء تظهر داخل التطبيق (شريط التقدم + Snackbar).
            val notification = foregroundNotification()
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (t: Throwable) {
            AppLog.e("ops", "رفض النظام startForeground — إيقاف الخدمة بهدوء", t)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL) {
            opsCenter.requestCancelQueue()
            // الإلغاء يُربط أيضاً بمعرّف العملية الجارية: لو مُسح علم الطابور لاحقاً
            // بطلب جديد (enqueue) تبقى العملية الملغاة ملغاة.
            opsCenter.requestStopOp(activeOpId)
            opsCenter.conflict.value?.deferred?.complete(ConflictDecision(ConflictPolicy.SKIP, false))
            // الإلغاء لا يوقف الخدمة وفي طابورها عمل جارٍ: stopSelf(startId) هنا كانت تدمّرها فوراً (نية الإلغاء هي الأحدث) فيُقتل العامل في منتصف العملية وسجلها…
            synchronized(pendingLock) {
                lastStartId = startId
                if (pendingCount.get() == 0) stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (intent == null) {
            synchronized(pendingLock) {
                lastStartId = startId
                if (pendingCount.get() == 0) stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        synchronized(pendingLock) {
            lastStartId = startId
            pendingCount.incrementAndGet()
            requests.trySend(QueueEntry.FromIntent(intent))
        }
        return START_NOT_STICKY
    }

    /** مهلة dataSync (أندرويد 15+): النظام يوجب توقف الخدمة بعدها قريباً — استمرار العامل بالطلب التالي في الطابور كان يخالف ذلك (4.2). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        opsCenter.requestStopOp(activeOpId)
        opsCenter.requestCancelQueue()
        opsCenter.conflict.value?.deferred?.complete(ConflictDecision(ConflictPolicy.SKIP, false))
        stopSelf(startId)
    }

    override fun onDestroy() {
        opsCenter.requestCancelQueue()
        opsCenter.conflict.value?.deferred?.complete(ConflictDecision(ConflictPolicy.SKIP, false))
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun worker() {
        for (entry in requests) {
            // الطلب المسترد يعبر decode نظرياً (نيته الأصلية استُهلكت
            // مع ملفها) — يُعاد بناؤه عند الاسترداد فيصبح جاهزاً هنا مباشرة.
            val request = when (entry) {
                is QueueEntry.Recovered -> entry.request
                is QueueEntry.FromIntent -> runCatching { decode(entry.intent, filesDir) }.getOrNull()
            }
            // مهلة الخدمة (4.2): ما بقي في الطابور يُصرَّف كطلبات لم تبدأ —
            // النظام يوجب التوقف بعدها فلا يبدأ عمل جديد.
            if (timedOut) {
                if (request != null) {
                    val notStarted = runCatching { getString(R.string.op_not_started_after_fatal) }
                        .getOrDefault("operation not started")
                    opsCenter.publishResult(notStarted)
                    runCatching {
                        opsCenter.publishOutcome(
                            OpOutcome(opIdOf(request), kindOf(request, opsCenter.journal), 0, 1, 0)
                        )
                    }
                }
                // الطلب المسترد هنا فقدنيته الأصلية؛ يُمحى أثره المعلّق.
                if (request != null) {
                    runCatching { PendingRequestStore.clearActive(filesDir, opIdOf(request)) }
                    runCatching { PendingRequestStore.removePending(filesDir, opIdOf(request)) }
                }
                completeOne()
                continue
            }
            if (request == null) {
                // decode الفاشل كان يُسقط الطلب بصمت: لا نتيجة، لا سجل،
                // والواجهة تنتظر إلى الأبد. الآن: نتيجة نصية فاشلة + سجل معمّى.
                runCatching {
                    val msg = getString(R.string.op_decode_failed)
                    AppLog.e("ops", "تعذّر فك ترميز طلب معلّق — يُسقط من الطابور")
                    opsCenter.publishResult(msg, isError = true)
                    // لا opId معروف — لا OpOutcome مهيكل ممكن، لكن publishResult يكفي للواجهة
                }
                completeOne()
                continue
            }
            if (fatalStop) {
                val notStarted = runCatching { getString(R.string.op_not_started_after_fatal) }
                    .getOrDefault("operation not started")
                opsCenter.publishResult(notStarted)
                runCatching {
                    opsCenter.publishOutcome(
                        OpOutcome(opIdOf(request), kindOf(request, opsCenter.journal), 0, 1, 0)
                    )
                }
            } else if (opsCenter.queueCancelRequested) {
                val cancelled = runCatching { getString(R.string.op_cancelled_resumable) }
                    .getOrDefault("operation cancelled")
                opsCenter.publishResult(cancelled)
                runCatching {
                    opsCenter.publishOutcome(
                        OpOutcome(opIdOf(request), kindOf(request, opsCenter.journal), 0, 1, 0)
                    )
                }
            } else {
                runOp(request)
            }
            // إيقاف آمن مدمج تحت القفل: قراءة pendingCount و stopSelf(lastStartId) تحت القفل نفسه تمنع سباق «طلب جديد بعد decrement وقبل stopSelf» — نافذة كان يمكن…
            completeOne()
        }
    }

    /** Single exit path for every dequeued entry: stop the service when the queue drains. */
    private fun completeOne() {
        synchronized(pendingLock) {
            if (pendingCount.decrementAndGet() == 0) {
                stopSelf(lastStartId)
            }
        }
    }

    /** معرّف أي طلب كانت عمليته قد بدأت أو صُرفت. */
    private fun opIdOf(request: OpRequest): String = when (request) {
        is OpRequest.Copy -> request.opId
        is OpRequest.Move -> request.opId
        is OpRequest.Delete -> request.opId
        is OpRequest.Resume -> request.opId
        is OpRequest.Compress -> request.opId
        is OpRequest.Extract -> request.opId
    }

    private suspend fun runOp(request: OpRequest) {
        val opId = opIdOf(request)
        val journal: OpJournal = opsCenter.journal
        var wakeLock: android.os.PowerManager.WakeLock? = null
        val engine by lazy {
            FileOpEngine(
                journal = journal,
                sameVolume = { a, b -> StorageRepository.sameVolume(a, b) },
                resolveConflict = ::askConflict,
                onProgress = ::onProgress,
                isCancelled = { opsCenter.isStopRequested(opId) || opsCenter.queueCancelRequested || timedOut },
                dropPageCache = { file -> StorageRepository.dropPageCache(file) }
            )
        }
        // All setup lives inside try/finally so cleanup always runs, even if init throws.
        try {
            // النسخة الاحتياطية موجودة أصلاً منذ holdAndRead عند decode
            // (نقل ذري باسم opId)، وما عاد يُعاد كتابتها هنا — كانت بين الحذف
            // والكتابة نافذة فقد الطلب عند موت العملية. علامة «قيد التنفيذ» تُكتب الآن.
            PendingRequestStore.markActive(filesDir, opId)
            opsCenter.clearStopRequest(opId)
            opsCenter.markOpActive(opId)
            activeOpId = opId
            wakeLock = runCatching {
                (getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                    ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ofm:op:${opId}")
                    ?.also { it.setReferenceCounted(false) }
            }.getOrNull()
            runCatching { wakeLock?.acquire(WAKELOCK_TIMEOUT_MS) }
            val appSettings = AppSettings.get(applicationContext)
            appSettings.ensureLoaded()
            val policy = appSettings.conflictPolicy()
            when (request) {
                is OpRequest.Compress -> {
                    // المحرك يعيد رسالة النتيجة مباشرة — بلا قناة
                    // lastArchiveMessage الجانبية بين الدالتين.
                    val message = archiveCoordinator.runCompress(request)
                    return
                }
                is OpRequest.Extract -> {
                    val message = archiveCoordinator.runExtract(request, policy)
                    return
                }
                else -> {}
            }
            val stats: FileOpStats = when (request) {
                is OpRequest.Copy -> {
                    currentLabel = getString(R.string.op_kind_copy)
                    engine.copy(request.sources, request.targetDir, policy, opId)
                }
                is OpRequest.Move -> {
                    currentLabel = getString(R.string.op_kind_move)
                    engine.move(request.sources, request.targetDir, policy, opId)
                }
                is OpRequest.Delete -> {
                    currentLabel = getString(R.string.op_kind_delete)
                    engine.delete(request.sources, opId)
                }
                is OpRequest.Resume -> {
                    currentLabel = getString(R.string.op_kind_resume)
                    engine.resume(request.opId, policy)
                }
                else -> throw IllegalStateException("unreachable")
            }
            val message = summary(currentLabel, stats)
            opsCenter.publishResult(message, opId)
            opsCenter.publishOutcome(
                OpOutcome(opId, kindOf(request, journal), stats.ok, stats.failed, stats.skipped)
            )
        } catch (e: OperationCancelledException) {
            if (timedOut) {
                runCatching { journal.finishOp(activeOpId ?: "", OpState.INTERRUPTED, 0, 0, 0, getString(R.string.op_timeout_journal)) }
                val message = getString(R.string.op_timeout)
                opsCenter.publishResult(message, opId)
                publishAbortOutcome(request, opId, failed = 0, skipped = 1)
            } else {
                val message = getString(R.string.op_cancelled_resumable)
                opsCenter.publishResult(message, opId)
                publishAbortOutcome(request, opId, failed = 0, skipped = 1)
            }
        } catch (e: TargetInsideSourceException) {
            runCatching { journal.finishOp(activeOpId ?: "", OpState.FAILED, 0, 0, 0, e.message ?: "") }
            val message = getString(R.string.op_target_inside_source, e.message ?: getString(R.string.op_target_inside_source_default))
            opsCenter.publishResult(message, opId)
            publishAbortOutcome(request, opId, failed = 1, skipped = 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = getString(R.string.op_stopped, e.message ?: e.javaClass.simpleName)
            AppLog.e("ops", message, e)
            opsCenter.publishResult(message, opId)
            publishAbortOutcome(request, opId, failed = 1, skipped = 0)
        } catch (t: Throwable) {
            fatalStop = true
            val label = t.javaClass.simpleName
            runCatching {
                journal.finishOp(
                    activeOpId ?: "", OpState.INTERRUPTED, 0, 0, 0,
                    getString(R.string.op_fatal_journal, label)
                )
            }
            val message = runCatching { getString(R.string.op_fatal_stopped, label) }
                .getOrDefault(label)
            runCatching { AppLog.e("ops", message, t) }
            opsCenter.publishResult(message, opId, isError = true)
            publishAbortOutcome(request, opId, failed = 1, skipped = 0)
        } finally {
            // محو علامة التنفيذ والنسخة الاحتياطية: أي خاتمة (نجاح/فشل/
            // إلغاء/خطأ قاتل) تنهي دورة حياة الطلب المعلّق فلا يُسترد مجدداً.
            runCatching { PendingRequestStore.clearActive(filesDir, opId) }
            runCatching { PendingRequestStore.removePending(filesDir, opId) }
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            opsCenter.publishProgress(null)
            opsCenter.publishConflict(null)
            opsCenter.markOpFinished(opId)
            activeOpId = null
            currentLabel = ""
            // خاتمة العملية نقطة فحص طبيعية للانقطاع: عملية RUNNING في
            // السجل بعد موت سابق لم يُقرر بعد تظهر الآن، والانقطاع الحقيقي
            // لهذه العملية (خطأ قاتل/إيقاف) يُعلَّم ويُعرض.
            opsCenter.requestRecoveryCheck()
        }
    }

    /**
     * إشارة مهيكلة للإلغاء/الفشل (كمساري الضغط والاستخراج): بلا حدث لا تُصفّى
     * awaitingMoveOpId في الواجهة. الأعداد دائماً failed>0 أو skipped>0 فلا تُفرَّغ
     * حافظة القص (تُفرَّغ عند failed==0 && skipped==0 فقط).
     */
    private fun publishAbortOutcome(request: OpRequest, opId: String, failed: Int, skipped: Int) {
        runCatching {
            opsCenter.publishOutcome(OpOutcome(opId, kindOf(request, opsCenter.journal), 0, failed, skipped))
        }
    }

    /**
     * يعرض التعارض على الواجهة وينتظر قرارها. التدفقات في OpsCenter بلا مراجع
     * نشاط؛ إن لم تكن الواجهة ظاهرة فيكفيها الإشعار («افتح التطبيق للقرار»)،
     * وبعد 10 دقائق بلا قرار نتخطى (الوجهة تبقى والمصدر سليم).
     */
    private suspend fun askConflict(request: ConflictRequest): ConflictDecision {
        val deferred = CompletableDeferred<ConflictDecision>()
        opsCenter.publishConflict(PendingConflict(request, deferred))
        val decision = withTimeoutOrNull(CONFLICT_TIMEOUT_MS) { deferred.await() }
            ?: ConflictDecision(ConflictPolicy.SKIP, false)
        opsCenter.publishConflict(null)
        return decision
    }

    private fun onProgress(p: FileOpProgress) {
        opsCenter.publishProgress(OpProgressInfo(
            opId = activeOpId ?: "",
            label = currentLabel,
            itemIndex = p.itemIndex,
            itemCount = p.itemCount,
            bytesDone = p.bytesDone,
            bytesTotal = p.bytesTotal,
            currentFile = p.current
        ))
    }

    private fun summary(label: String, stats: FileOpStats): String {
        val verdict = when {
            stats.failed == 0 && stats.skipped == 0 -> getString(R.string.op_result_completed)
            stats.failed == 0 -> getString(R.string.op_result_partial)
            else -> getString(R.string.op_result_incomplete)
        }
        return getString(R.string.op_result_summary, label, verdict, stats.ok, stats.failed, stats.skipped)
    }

    /** نوع العملية للإشارة المهيكلة؛ الاستئناف يُقرأ من سجل العملية الأصلية. */
    private fun kindOf(request: OpRequest, journal: OpJournal): OpKind? = when (request) {
        is OpRequest.Copy -> OpKind.COPY
        is OpRequest.Move -> OpKind.MOVE
        is OpRequest.Delete -> OpKind.DELETE
        is OpRequest.Compress -> OpKind.COMPRESS
        is OpRequest.Extract -> OpKind.EXTRACT
        is OpRequest.Resume ->
            runCatching { journal.ops().firstOrNull { it.id == request.opId }?.kind }.getOrNull()
    }

    // ---------- الإشعار الإجباري للخدمة الأمامية ----------

    private fun createChannel() {
        // استخدام NotificationChannels.ensure الموحّد بدل تكرار البناء.
        NotificationChannels.ensure(
            this, CHANNEL_ID, getString(R.string.file_ops_channel), getString(R.string.file_ops_channel_desc),
            NotificationManager.IMPORTANCE_MIN
        )
    }

    private fun foregroundNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.file_ops_channel))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

    // ---------- ترميز الطلبات عبر Intent ----------

    companion object {
        const val CHANNEL_ID = "file_ops"
        const val NOTIFICATION_ID = 42
        const val ACTION_CANCEL = "org.openfilemanager.app.ops.CANCEL"
        private const val EXTRA_KIND = "kind"

        /** مفاتيح معرّف العملية ورمز الطلب في النية — عقد الواجهة←الخدمة (داخلية لاختبار الانحدار). */
        internal const val EXTRA_OP_ID = "op_id"
        internal const val EXTRA_TOKEN = "token"
        private const val KIND_RESUME = "RESUME"
        private const val CONFLICT_TIMEOUT_MS = 10L * 60 * 1000
        /** عمر طلب معلّق يُعدّ هرماً: النية لا تُعاد التسليم أصلاً (START_NOT_STICKY). */
        private const val STALE_PENDING_MS = 60L * 60 * 1000

        private const val WAKELOCK_TIMEOUT_MS = 5L * 60 * 60 * 1000

        /** يفك النية إلى طلب عملية، أو null عند الغياب أو التلف. */
        internal fun decode(intent: Intent, filesDir: File): OpRequest? {
            intent.getStringExtra(EXTRA_TOKEN)?.let { token ->
                // تسليم ذري: الملف يُنقل باسم opId إلى النسخ الاحتياطية قبل
                // القراءة، فلا نافذة «حُذف الأصل ولم تُكتب النسخة» بعد الآن.
                val opId = intent.getStringExtra(EXTRA_OP_ID) ?: FileOpEngine.newOpId()
                val stored = PendingRequestStore.holdAndRead(filesDir, token, opId) ?: return null
                return stored.toOpRequest(opId)
            }
            val kind = intent.getStringExtra(EXTRA_KIND) ?: return null
            return when (kind) {
                KIND_RESUME -> OpRequest.Resume(intent.getStringExtra(EXTRA_OP_ID) ?: return null)
                else -> null
            }
        }

        /**
         * (E3) نفس [enqueue] لكن كتابة ملف الطلب وstartForegroundService تجريان على
         * نطاق IO لا خيط المستدعي: حذف عدد كبير من العناصر يكتب ملفاً كبيراً فلا
         * يجمد الخيط الرئيسي. النتيجة تصل بعد اكتمال الكتابة — لا تفترضها فورية.
         */
        suspend fun enqueueOnIo(context: Context, request: OpRequest): Boolean =
            withContext(AppDispatchers.io) { enqueue(context, request) }

        fun enqueue(context: Context, request: OpRequest): Boolean {
            var token: String? = null
            return try {
                val intent = Intent(context, FileOperationService::class.java)
                when (request) {
                    is OpRequest.Resume -> {
                        intent.putExtra(EXTRA_KIND, KIND_RESUME)
                        intent.putExtra(EXTRA_OP_ID, request.opId)
                    }
                    is OpRequest.Copy -> {
                        token = writeStoredRequest(context.filesDir, request)
                        intent.putExtra(EXTRA_TOKEN, token)
                        intent.putExtra(EXTRA_OP_ID, request.opId)
                    }
                    is OpRequest.Move -> {
                        token = writeStoredRequest(context.filesDir, request)
                        intent.putExtra(EXTRA_TOKEN, token)
                        intent.putExtra(EXTRA_OP_ID, request.opId)
                    }
                    is OpRequest.Delete -> {
                        token = writeStoredRequest(context.filesDir, request)
                        intent.putExtra(EXTRA_TOKEN, token)
                        intent.putExtra(EXTRA_OP_ID, request.opId)
                    }
                    is OpRequest.Compress -> {
                        token = writeStoredRequest(context.filesDir, request)
                        intent.putExtra(EXTRA_TOKEN, token)
                        intent.putExtra(EXTRA_OP_ID, request.opId)
                    }
                    is OpRequest.Extract -> {
                        token = writeStoredRequest(context.filesDir, request)
                        intent.putExtra(EXTRA_TOKEN, token)
                        intent.putExtra(EXTRA_OP_ID, request.opId)
                    }
                }
                // المسح قبل الإرسال: بعده قد يبدأ الطلب الجديد فيُلغى بعلم قديم
                // (نافذة السباق). الإلغاء المطلوب لعملية جارية محفوظ بمعرّفها.
                context.appContainer.opsCenter.clearQueueCancel()
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                token?.let { PendingRequestStore.remove(context.filesDir, it) }
                false
            }
        }

        private fun writeStoredRequest(filesDir: File, request: OpRequest): String {
            val stored = requireNotNull(request.toStoredOpRequest()) {
                "unreachable: only Resume encodes to null and it is handled above"
            }
            return PendingRequestStore.write(
                filesDir, stored.kind, stored.paths, stored.target,
                extra = stored.extra, selected = stored.selected
            )
        }
    }
}

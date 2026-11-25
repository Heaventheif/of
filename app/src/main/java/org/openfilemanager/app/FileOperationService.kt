package org.openfilemanager.app

import android.app.Notification
import android.app.NotificationChannel
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
import java.nio.charset.Charset
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.openfilemanager.core.EntryFilter

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

/**
 * خدمة العمليات الطويلة:
 * - أمامية من نوع dataSync، تبدأ من واجهة ظاهرة فقط (قيود بدء الخدمات في 15+).
 * - معالجة تسلسلية (عملية كتابة واحدة في وقتها؛ يحقق «عملية واحدة لكل وحدة» بدراجة أمان).
 * - إشعار تقدم يُختزل إلى مرة كل 500 مللي ثانية، وزر إلغاء فوري (فحص كل دفعة).
 * - التعارض يعلّق المحرك على CompletableDeferred وتُكمله الواجهة عبر [OpsCenter].
 * - onTimeout (مهلة dataSync ~6 ساعات في 15+): إيقاف آمن وتعليم السجل INTERRUPTED للاستئناف.
 * - كل عملية تُسجَّل في [OpJournal] فتُستأنف بعد موت العملية.
 */
class FileOperationService : Service() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LanguageManager.wrap(newBase))
    }

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.io)

    /** Injected operations bridge (operation code itself unchanged): the container's instance. */
    private val opsCenter by lazy { appContainer.opsCenter }

    /**
     * [0.7.9] مدخل الطابور: نية من onStartCommand أو طلب مُسترد مباشرة.
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

    /**
     * [0.7.9] محرك الضغط/الاستخراج فوق عقد OpJournal (كان المساران يدويين
     * بسجل ناقص). مفرد في الحاوية — حدود الاستخراج تُحسب من مساحة الوجهة
     * كما في ArchiveService.spaceBoundLimits.
     */
    private val archiveEngine by lazy {
        org.openfilemanager.core.ArchiveOpEngine(
            journal = opsCenter.journal,
            limitsFor = { targetDir -> spaceBoundLimits(java.io.File(targetDir.toString())) }
        )
    }

    /** [0.7.9] حدود الاستخراج المرتبطة بالمساحة المتاحة (نفس منطق ArchiveService). */
    private fun spaceBoundLimits(destinationDir: java.io.File): org.openfilemanager.core.ExtractLimits {
        var probe: java.io.File? = destinationDir
        while (probe != null && !probe.exists()) probe = probe.parentFile
        val usable = probe?.let { runCatching { it.usableSpace }.getOrNull() }
        if (usable == null || usable <= 0L) {
            throw org.openfilemanager.core.ArchiveLimitException(getString(R.string.archive_space_unreadable))
        }
        // (مراجعة 0.8.6-#3) سقف مخفَّف اختياري من الإعدادات، كما في ArchiveService.
        return if (appContainer.settings.archiveRelaxedLimits) org.openfilemanager.core.ExtractLimits.relaxed(usable)
        else org.openfilemanager.core.ExtractLimits(maxTotalBytes = usable)
    }

    override fun onCreate() {
        super.onCreate()
        opsCenter.resetCancel()
        notifications = getSystemService(NotificationManager::class.java)
        createChannel()
        scope.launch {
            PendingRequestStore.purgeOlderThan(filesDir, STALE_PENDING_MS)
            // [0.7.9] استرداد الطلبات المعلّقة من تشغيل سابق قبل بدء العامل:
            // الخدمة تُدمّر عند قتل النظام/onTimeout والنظام لا يعيد تسليم
            // النوايا (START_NOT_STICKY) فتضيع الطلبات غير المنفذة كلياً.
            // المستردّ يُعاد إدخاله كأنه وصل الآن — إلا ما كان له سجل RUNNING/
            // INTERRUPTED (يقرره حوار الاسترداد لا هذه المسارات الصامتة).
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
            // الإلغاء لا يوقف الخدمة وفي طابورها عمل جارٍ: stopSelf(startId) هنا كانت
            // تدمّرها فوراً (نية الإلغاء هي الأحدث) فيُقتل العامل في منتصف العملية
            // وسجلها يبقى RUNNING. يُسجَّل startId ليبقى أحدث رقم معروفاً للعامل
            // فيوقف الخدمة هو عند الفراغ؛ وإن لم يكن عمل فنوقفها هنا.
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

    /**
     * مهلة dataSync (أندرويد 15+): النظام يوجب توقف الخدمة بعدها قريباً —
     * استمرار العامل بالطلب التالي في الطابور كان يخالف ذلك (4.2). المسار:
     * 1) طلب إلغاء العملية الجارية (المحرك يفحصه عند الدفعة التالية ويعلّم
     * سجلها INTERRUPTED)، 2) حلّ أي تعارض معلّق، 3) تصريف بقية الطابور كطلبات
     * «لم تبدأ» (نتائج مهيكلة كفرع fatalStop وتبقى قابلة للاستئناف من سجلها)،
     * 4) stopSelf فوراً.
     */
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
            // [0.7.9] الطلب المسترد يعبر decode نظرياً (نيته الأصلية استُهلكت
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
                // [0.7.9] الطلب المسترد هنا فقدنيته الأصلية؛ يُمحى أثره المعلّق.
                if (request != null) {
                    runCatching { PendingRequestStore.clearActive(filesDir, opIdOf(request)) }
                    runCatching { PendingRequestStore.removePending(filesDir, opIdOf(request)) }
                }
                completeOne()
                continue
            }
            if (request == null) {
                // [0.7.9] decode الفاشل كان يُسقط الطلب بصمت: لا نتيجة، لا سجل،
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
            // إيقاف آمن مدمج تحت القفل (4.1 / [0.7.9]): قراءة pendingCount و
            // stopSelf(lastStartId) تحت القفل نفسه تمنع سباق «طلب جديد بعد
            // decrement وقبل stopSelf» — نافذة كان يمكن أن تمر فيها نية محتجزة
            // مع خدمة مدمرة. stopSelf سريع (<1ms) فلا يُحبس القفل طويلاً.
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
        // يُنشأ عند أول استخدام: الضغط والاستخراج لا يحتاجانه (F18).
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
            // (F-07) النسخة الاحتياطية موجودة أصلاً منذ holdAndRead عند decode
            // (نقل ذري باسم opId)، وما عاد يُعاد كتابتها هنا — كانت بين الحذف
            // والكتابة نافذة فقد الطلب عند موت العملية. علامة «قيد التنفيذ» تُكتب الآن.
            PendingRequestStore.markActive(filesDir, opId)
            opsCenter.clearStopRequest(opId)
            opsCenter.markOpActive(opId)
            activeOpId = opId
            // (F21) partial wake lock around the operation; released in finally.
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
                    // [0.7.9] المحرك يعيد رسالة النتيجة مباشرة — بلا قناة
                    // lastArchiveMessage الجانبية بين الدالتين.
                    val message = runCompress(request)
                    return
                }
                is OpRequest.Extract -> {
                    val message = runExtract(request, policy)
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
            // [0.7.9] محو علامة التنفيذ والنسخة الاحتياطية: أي خاتمة (نجاح/فشل/
            // إلغاء/خطأ قاتل) تنهي دورة حياة الطلب المعلّق فلا يُسترد مجدداً.
            runCatching { PendingRequestStore.clearActive(filesDir, opId) }
            runCatching { PendingRequestStore.removePending(filesDir, opId) }
            // (F21) تحرير قفل الاستيقاظ دائماً، حتى بعد خطأ قاتل.
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            opsCenter.publishProgress(null)
            opsCenter.publishConflict(null)
            opsCenter.markOpFinished(opId)
            activeOpId = null
            currentLabel = ""
            // (F-01) خاتمة العملية نقطة فحص طبيعية للانقطاع: عملية RUNNING في
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
     * [0.7.9] الضغط عبر [ArchiveOpEngine] في :core: سجل كامل (عنصر لكل مصدر)
     * بدل OpItemRecord واحد ناقص، والنتيجة تُنشر عبر publishResult/publishOutcome
     * حصراً — lastArchiveMessage (قناة @Volatile بين دالتين في نفس الكوروتين)
     * حُذفت. المؤقتات .fm-tmp-zip-* يغطيها منظف 3.3 كما قبل.
     */
    private suspend fun runCompress(request: OpRequest.Compress): String {
        val opId = request.opId
        val journal = opsCenter.journal
        val destination = request.targetDir.resolve(request.targetName)
        currentLabel = getString(R.string.op_kind_compress)
        val stats = archiveEngine.compress(
            sources = request.sources,
            destination = destination,
            opId = opId,
            onProgress = { p ->
                onProgress(
                    FileOpProgress(
                        itemIndex = p.entriesDone, itemCount = p.entriesTotal,
                        bytesDone = p.bytesDone, bytesTotal = p.bytesTotal,
                        current = destination.fileName.toString()
                    )
                )
            },
            isCancelled = { opsCenter.isStopRequested(opId) || opsCenter.queueCancelRequested || timedOut }
        )
        val message = if (stats.finalPath != null) {
            getString(R.string.archive_created, BidiText.wrap(destination.fileName.toString()))
        } else {
            getString(R.string.archive_create_failed)
        }
        opsCenter.publishResult(message, opId)
        opsCenter.publishOutcome(
            OpOutcome(opId, OpKind.COMPRESS, stats.ok, stats.failed, stats.skipped)
        )
        return message
    }

    /**
     * استخراج الأرشيف مع تطبيق سياسة التعارض نفسها التي تستخدمها عمليات النسخ والنقل.
     * الاستبدال يحفظ الوجهة القديمة باسم احتياطي مؤقت ثم يستعيدها عند الفشل أو الإلغاء.
     */
    private suspend fun runExtract(request: OpRequest.Extract, configuredPolicy: ConflictPolicy): String {
        val opId = request.opId
        val archiveFile = request.archive.toFile()
        currentLabel = getString(R.string.op_kind_extract)
        if (request.selected != null && request.selected.isEmpty()) {
            val message = getString(R.string.archive_no_items_selected)
            opsCenter.publishResult(message, opId)
            opsCenter.publishOutcome(OpOutcome(opId, OpKind.EXTRACT, 0, 0, 1))
            return message
        }

        val splitCheck = org.openfilemanager.core.SplitZip.check(request.archive)
        if (splitCheck is org.openfilemanager.core.SplitZipCheck.Incomplete) {
            val message = getString(
                R.string.archive_missing_parts,
                splitCheck.missing.joinToString(getString(R.string.list_separator))
            )
            opsCenter.publishResult(message, opId)
            opsCenter.publishOutcome(OpOutcome(opId, OpKind.EXTRACT, 0, 1, 0))
            return message
        }
        val split = splitCheck is org.openfilemanager.core.SplitZipCheck.Split

        val kind = org.openfilemanager.core.ArchiveFormat.detect(request.archive)
        val singleFileOutput = kind == org.openfilemanager.core.ArchiveFormat.Kind.GZ ||
            kind == org.openfilemanager.core.ArchiveFormat.Kind.XZ
        val naturalName = if (singleFileOutput) {
            org.openfilemanager.core.ArchiveFormat.singleFileName(archiveFile.name)
        } else {
            org.openfilemanager.core.ArchiveFormat.folderName(archiveFile.name)
        }
        val targetDir = request.targetDir
        val naturalTarget = targetDir.resolve(naturalName)
        var finalNameOverride: String? = null
        var backupPath: Path? = null
        var extractionSucceeded = false

        if (!request.placeDirectly && Files.exists(naturalTarget, LinkOption.NOFOLLOW_LINKS)) {
            val decision = when (configuredPolicy) {
                ConflictPolicy.ASK -> askConflict(
                    ConflictRequest(
                        source = request.archive,
                        target = naturalTarget,
                        isDirectory = Files.isDirectory(naturalTarget, LinkOption.NOFOLLOW_LINKS)
                    )
                )
                else -> ConflictDecision(configuredPolicy, false)
            }
            when (decision.policy) {
                ConflictPolicy.SKIP, ConflictPolicy.ASK -> {
                    val message = getString(R.string.archive_destination_skipped, BidiText.wrap(naturalName))
                    opsCenter.publishResult(message, opId)
                    opsCenter.publishOutcome(OpOutcome(opId, OpKind.EXTRACT, 0, 0, 1))
                    return message
                }
                ConflictPolicy.KEEP_BOTH -> {
                    finalNameOverride = uniqueArchiveOutputName(targetDir, naturalName, singleFileOutput)
                }
                ConflictPolicy.REPLACE -> if (request.selected != null &&
                    Files.isDirectory(naturalTarget, LinkOption.NOFOLLOW_LINKS)
                ) {
                    // A partial extraction must never wipe an existing folder: replacing it
                    // would delete every file that is not in the selected entries.
                    finalNameOverride = uniqueArchiveOutputName(targetDir, naturalName, singleFileOutput)
                } else {
                    val backup = uniqueReplaceBackup(naturalTarget)
                    // (المرحلة 2) تسجيل دائم قبل النقل: إن ماتت العملية بعد
                    // إزاحة المجلد الأصلي يعرف استرداد الإقلاع أن هذه النسخة
                    // بيانات مستخدم معلّقة — لا مؤقتاً يُجتاح مع `.fm-*`.
                    opsCenter.replaceBackups.record(opId, naturalTarget, backup)
                    backupPath = backup
                    moveWithoutReplacement(naturalTarget, backup)
                }
            }
        }

        try {
            val charset = runCatching { Charset.forName(request.charsetName) }.getOrDefault(Charsets.UTF_8)
            val stats = archiveEngine.extract(
                archive = request.archive,
                targetDir = targetDir,
                charset = charset,
                opId = opId,
                split = split,
                onProgress = { entries, bytes ->
                    onProgress(
                        FileOpProgress(
                            itemIndex = entries, itemCount = 0, bytesDone = bytes, bytesTotal = 0L,
                            current = archiveFile.name
                        )
                    )
                },
                checkCancelled = {
                    if (opsCenter.isStopRequested(opId) || opsCenter.queueCancelRequested || timedOut) {
                        throw OperationCancelledException()
                    }
                },
                // (دورة 6) only() لا exactOnly(): مرسلو Extract المحدد (extractHere،
                // لصق «نقل» من أرشيف) يحددون مجلدات — التوسع لكل ما تحتها هو السلوك
                // القديم (EntryFilter.only في ArchiveService) ويجب أن يبقى عبر الخدمة.
                filter = if (request.selected == null) EntryFilter.ALL else EntryFilter.only(request.selected.toSet()),
                finalNameOverride = finalNameOverride,
                placeSelectedDirectly = if (request.placeDirectly) request.selected else null
            )
            extractionSucceeded = stats.finalPath != null
            val finalName = stats.finalPath?.fileName?.toString() ?: naturalName
            val message = if (extractionSucceeded) {
                resources.getQuantityString(
                    R.plurals.plurals_archive_extracted, stats.ok, stats.ok, BidiText.wrap(finalName)
                ) + if (stats.skipped > 0) getString(R.string.archive_extracted_links, stats.skipped) else ""
            } else {
                getString(R.string.archive_extract_failed)
            }
            opsCenter.publishResult(message, opId)
            opsCenter.publishOutcome(
                OpOutcome(opId, OpKind.EXTRACT, stats.ok, stats.failed, stats.skipped)
            )
            return message
        } finally {
            val backup = backupPath
            if (backup != null) {
                if (extractionSucceeded) {
                    // البديل نُشر بنجاح: النسخة القديمة حُسم أمرها فتُحذف
                    // بأمان، ثم يُختم أمر الاستبدال في سجله الدائم.
                    runCatching { org.openfilemanager.core.AtomicIO.deleteTree(backup) }
                        .onFailure { AppLog.e("ops", "تعذر حذف النسخة الاحتياطية بعد الاستبدال: $backup", it) }
                    opsCenter.replaceBackups.remove(opId)
                } else {
                    runCatching {
                        if (!Files.exists(naturalTarget, LinkOption.NOFOLLOW_LINKS)) {
                            moveWithoutReplacement(backup, naturalTarget)
                            opsCenter.replaceBackups.remove(opId)
                        } else {
                            throw java.io.IOException("target was recreated; backup preserved at $backup")
                        }
                    }.onFailure {
                        // تعذر الحسم بأمان: يبقى الأمر مسجّلاً والنسخة محفوظة
                        // فيحاول استرداد الإقلاع التالي، ويُخبر المستخدم.
                        AppLog.e("ops", "تعذر استعادة الوجهة السابقة من $backup", it)
                        opsCenter.publishResult(getString(R.string.replace_backup_kept), opId)
                    }
                }
            }
        }
    }

    /** اسم بديل يحافظ على امتداد الملف المنفرد، ويتجنب أي ملف/مجلد موجود أو رابط رمزي. */
    private fun uniqueArchiveOutputName(parent: Path, baseName: String, preserveExtension: Boolean): String {
        val dot = if (preserveExtension) baseName.lastIndexOf('.').takeIf { it > 0 } else null
        val originalStem = if (dot != null) baseName.substring(0, dot) else baseName
        val extension = if (dot != null) baseName.substring(dot) else ""
        var index = 1
        while (index <= 100_000) {
            val suffix = " ($index)"
            val stemBudget = org.openfilemanager.core.NameRules.MAX_NAME_BYTES -
                suffix.toByteArray(Charsets.UTF_8).size - extension.toByteArray(Charsets.UTF_8).size
            if (stemBudget <= 0) throw java.io.IOException("archive name is too long to make a conflict-safe alternative")
            var stem = originalStem
            while (stem.isNotEmpty() && stem.toByteArray(Charsets.UTF_8).size > stemBudget) stem = stem.dropLast(1)
            val candidate = "$stem$suffix$extension"
            val path = parent.resolve(candidate)
            if (org.openfilemanager.core.NameRules.problem(candidate, strictFat = false) == null &&
                !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            ) return candidate
            index++
        }
        throw java.io.IOException("could not find an available extraction name")
    }

    /** نقل الوجهة إلى اسم احتياطي فريد دون استبدال أي عنصر آخر. */
    private fun uniqueReplaceBackup(target: Path): Path {
        repeat(8) {
            val candidate = target.resolveSibling(".fm-replace-backup-${UUID.randomUUID()}")
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate
        }
        throw java.io.IOException("could not allocate a safe replacement backup name")
    }

    private fun moveWithoutReplacement(source: Path, target: Path) {
        org.openfilemanager.core.AtomicIO.moveNoReplace(source, target)
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
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.file_ops_channel), NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.file_ops_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        notifications.createNotificationChannel(channel)
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
        /** عمر أدنى لحذف المؤقتات عند الإلغاء: مؤقت أحدث قد يخص كتابة حية أخرى في المجلد نفسه. */
        private const val CLEANUP_MIN_AGE_MS = 60_000L
        private const val CONFLICT_TIMEOUT_MS = 10L * 60 * 1000
        /** عمر طلب معلّق يُعدّ هرماً: النية لا تُعاد التسليم أصلاً (START_NOT_STICKY). */
        private const val STALE_PENDING_MS = 60L * 60 * 1000

        /** (F21) مهلة قفل الاستيقاظ: قريبة من مهلة dataSync (~6 ساعات) كصمام أمان للتحرير المفقود. */
        private const val WAKELOCK_TIMEOUT_MS = 5L * 60 * 60 * 1000


        /**
         * يفك النية إلى طلب عملية، أو null عند الغياب أو التلف. مسار القرص أولاً:
         * المسارات الكثيرة تتجاوز حد Binder فتحمل النية رمز الملف فقط، ويبقى
         * الاستئناف رمزاً قصيراً في extras. معرّف العملية يسافر مع النية فيعرفه
         * المرسل وتربط الواجهة النتيجة المهيكلة به؛ إن غاب (نية من نسخة أقدم)
         * يولّد معرّفاً جديداً. داخلية لاختبار الانحدار (عقد opId).
         */
        internal fun decode(intent: Intent, filesDir: File): OpRequest? {
            intent.getStringExtra(EXTRA_TOKEN)?.let { token ->
                // (F-07) تسليم ذري: الملف يُنقل باسم opId إلى النسخ الاحتياطية قبل
                // القراءة، فلا نافذة «حُذف الأصل ولم تُكتب النسخة» بعد الآن.
                val opId = intent.getStringExtra(EXTRA_OP_ID) ?: FileOpEngine.newOpId()
                val stored = PendingRequestStore.holdAndRead(filesDir, token, opId) ?: return null
                return when (stored.kind) {
                    PendingRequestStore.KIND_COPY -> OpRequest.Copy(
                        stored.paths.map { Paths.get(it) },
                        Paths.get(stored.target ?: return null),
                        opId
                    )
                    PendingRequestStore.KIND_MOVE -> OpRequest.Move(
                        stored.paths.map { Paths.get(it) },
                        Paths.get(stored.target ?: return null),
                        opId
                    )
                    PendingRequestStore.KIND_DELETE -> OpRequest.Delete(stored.paths.map { Paths.get(it) }, opId)
                    PendingRequestStore.KIND_COMPRESS -> OpRequest.Compress(
                        stored.paths.map { Paths.get(it) },
                        Paths.get(stored.target ?: return null),
                        requireNotNull(stored.extra),
                        opId
                    )
                    PendingRequestStore.KIND_EXTRACT -> OpRequest.Extract(
                        Paths.get(stored.paths.first()),
                        Paths.get(stored.target ?: return null),
                        decodeExtractCharset(stored.extra),
                        stored.selected,
                        opId,
                        decodeExtractDirect(stored.extra)
                    )
                    else -> null
                }
            }
            val kind = intent.getStringExtra(EXTRA_KIND) ?: return null
            return when (kind) {
                KIND_RESUME -> OpRequest.Resume(intent.getStringExtra(EXTRA_OP_ID) ?: return null)
                else -> null
            }
        }

        /**
         * يضيف الطلب إلى طابور الخدمة. تُستدعى من واجهة ظاهرة فقط (قيود 15+).
         * مسارات النسخ/النقل/الحذف تُكتب على القرص: حد Binder (~1MB) كان يرمي
         * TransactionTooLargeException عند تحديد كل ملفات مجلد كبير فتضيع
         * العملية بصمت؛ الاستئناف رمز قصير يبقى في extras.
         */
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
                        token = PendingRequestStore.write(
                            context.filesDir, PendingRequestStore.KIND_COPY,
                            request.sources.map { it.toString() }, request.targetDir.toString()
                        )
                        intent.putExtra(EXTRA_TOKEN, token)
                        intent.putExtra(EXTRA_OP_ID, request.opId)
                    }
                    is OpRequest.Move -> {
                        token = PendingRequestStore.write(
                            context.filesDir, PendingRequestStore.KIND_MOVE,
                            request.sources.map { it.toString() }, request.targetDir.toString()
                        )
                        intent.putExtra(EXTRA_TOKEN, token)
                        intent.putExtra(EXTRA_OP_ID, request.opId)
                    }
                    is OpRequest.Delete -> {
                        token = PendingRequestStore.write(
                            context.filesDir, PendingRequestStore.KIND_DELETE,
                            request.sources.map { it.toString() }, null
                        )
                        intent.putExtra(EXTRA_TOKEN, token)
                        intent.putExtra(EXTRA_OP_ID, request.opId)
                    }
                    is OpRequest.Compress -> {
                        token = PendingRequestStore.write(
                            context.filesDir, PendingRequestStore.KIND_COMPRESS,
                            request.sources.map { it.toString() }, request.targetDir.toString(),
                            extra = request.targetName
                        )
                        intent.putExtra(EXTRA_TOKEN, token)
                        intent.putExtra(EXTRA_OP_ID, request.opId)
                    }
                    is OpRequest.Extract -> {
                        token = PendingRequestStore.write(
                            context.filesDir, PendingRequestStore.KIND_EXTRACT,
                            listOf(request.archive.toString()), request.targetDir.toString(),
                            extra = encodeExtractExtra(request.charsetName, request.placeDirectly),
                            selected = request.selected
                        )
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
    }
}

    /**
     * [0.7.9] يحوّل الطلب المخزّن إلى OpRequest بنفس مفاتيح decode تماماً —
     * مسار الاسترداد يعيد بناء الطلب كما لو وصلت نيته الآن. امتداد علوية كي
     * تستدعيها اختبارات الوحدة مباشرة.
     */
    internal fun StoredOpRequest.toOpRequest(opId: String): OpRequest? {
        // جسم كتلي: return المبكر داخل جسم تعبيرية ممنوع في Kotlin.
        if (kind == PendingRequestStore.KIND_DELETE) {
            return OpRequest.Delete(paths.map { Paths.get(it) }, opId)
        }
        if (kind == PendingRequestStore.KIND_COMPRESS) {
            val dir = target ?: return null
            return OpRequest.Compress(paths.map { Paths.get(it) }, Paths.get(dir), extra ?: "", opId)
        }
        if (kind == PendingRequestStore.KIND_EXTRACT) {
            val dir = target ?: return null
            return OpRequest.Extract(
                Paths.get(paths.first()), Paths.get(dir),
                decodeExtractCharset(extra), selected, opId, decodeExtractDirect(extra)
            )
        }
        val dir = target ?: return null
        return when (kind) {
            PendingRequestStore.KIND_COPY -> OpRequest.Copy(paths.map { Paths.get(it) }, Paths.get(dir), opId)
            PendingRequestStore.KIND_MOVE -> OpRequest.Move(paths.map { Paths.get(it) }, Paths.get(dir), opId)
            else -> null
        }
    }


/** ترميز علم «الوضع المباشر» داخل حقل extra المخزّن (يحفظ صيغة التخزين كما هي). */
internal fun encodeExtractExtra(charsetName: String, direct: Boolean): String =
    if (direct) "$charsetName#direct" else charsetName

internal fun decodeExtractCharset(extra: String?): String =
    extra?.substringBefore('#')?.ifBlank { null } ?: Charsets.UTF_8.name()

internal fun decodeExtractDirect(extra: String?): Boolean = extra?.endsWith("#direct") == true

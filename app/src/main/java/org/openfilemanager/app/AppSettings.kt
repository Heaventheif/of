package org.openfilemanager.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openfilemanager.core.ConflictPolicy
import org.openfilemanager.core.FolderSort
import org.openfilemanager.core.SortDirectionCore
import org.openfilemanager.core.SortFieldCore

/** DataStore للإعدادات: بديل SharedPreferences الآمن من الخيوط. */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "open_file_manager",
    corruptionHandler = ReplaceFileCorruptionHandler(
        produceNewData = { emptyPreferences() }
    )
)

class AppSettings private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val data: DataStore<Preferences> get() = appContext.settingsDataStore

    /** نطاق كتابة القرص فقط؛ يُلغى مع العملية، على المجدول الموحد AppDispatchers.io. */
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.io)
    private val loadLock = Mutex()

    private val diskEdits = Channel<DiskEdit>(Channel.UNLIMITED)

    private val queue = SettingsDiskEditQueue(
        apply = ::applyDiskEdit,
        retryMs = DISK_WRITE_RETRY_MS,
        maxAttempts = DISK_WRITE_MAX_ATTEMPTS,
        onGiveUp = { edit, attempts, t ->
            AppLog.e(
                "settings",
                "تعذر حفظ تعديل الإعدادات على القرص بعد $attempts محاولات (${edit.javaClass.simpleName})",
                t
            )
        }
    )

    init {
        startDiskConsumer()
    }

    @Suppress("UNCHECKED_CAST")
    private fun writeToPrefs(prefs: androidx.datastore.preferences.core.MutablePreferences, edit: DiskEdit) {
        if (edit !is DiskEdit.SetValue) return
        val key = edit.key
        when (val value = edit.value) {
            is DiskEdit.SupportedValue.Bool -> prefs[key as Preferences.Key<Boolean>] = value.v
            is DiskEdit.SupportedValue.Str -> prefs[key as Preferences.Key<String>] = value.v
            is DiskEdit.SupportedValue.Num -> prefs[key as Preferences.Key<Int>] = value.v
            is DiskEdit.SupportedValue.LongNum -> prefs[key as Preferences.Key<Long>] = value.v
            is DiskEdit.SupportedValue.FloatNum -> prefs[key as Preferences.Key<Float>] = value.v
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun applyDiskEdit(edit: DiskEdit) {
        when (edit) {
            is DiskEdit.SetValue -> data.edit { prefs -> writeToPrefs(prefs, edit) }
            is DiskEdit.Remove -> data.edit { prefs ->
                prefs.remove(edit.key as Preferences.Key<Any>)
            }
            DiskEdit.ClearAll -> data.edit { prefs -> prefs.clear() }
            is DiskEdit.Barrier -> edit.done.complete(Unit)
        }
    }

    private fun startDiskConsumer() {
        scope.launch {
            for (edit in diskEdits) {
                queue.submit(edit)
            }
        }
    }

    /**
     * لقطة محمّلة للقراءات المتزامنة (خيط الرئيسي). العقد: تُستدعى [ensureLoaded]
     * (تُعلَّق حتى الجاهزية) من مسارات القراءة غير المتزامنة قبل أول قراءة متزامنة،
     * و[preload] من App.onCreate يعطيها سبقاً قبل أول نشاط.
     */
    @Volatile
    private var snapshot: Preferences? = null

    /** أول تحميل (مع ترحيل SharedPreferences القديم مرة واحدة). آمن للتكرار. */
    /**
 * كتابة قبل أول تحميل: العلم [loadedFromDisk] منفصل عن وجود اللقطة، فلا تمنع
 * الكتابةُ المبكرة قراءةَ بقية المفاتيح من القرص؛ والكتابات ما قبل التحميل
 * تُدمج فوق حالة القرص (الأحدث يسود).
 */
    @Volatile
    private var loadedFromDisk = false

    /** جيل التحميل: reset أثناء تحميل جارٍ يرفعه فتُهمل قراءة القرص الهرمة. */
    private var loadGeneration = 0

    suspend fun ensureLoaded() {
        if (loadedFromDisk) return
        loadLock.withLock {
            if (loadedFromDisk) return
            val generation = synchronized(this) { loadGeneration }
            migrateLegacyIfNeeded()
            val disk = data.data.first()
            synchronized(this) {
                if (generation != loadGeneration) return
                val preLoadWrites = snapshot
                snapshot = if (preLoadWrites == null) disk else mergePreferences(disk, preLoadWrites)
                loadedFromDisk = true
            }
        }
    }

    /** دمج كتاباتٍ سبقت اكتمال التحميل فوق حالة القرص (الأحدث يسود). */
    @Suppress("UNCHECKED_CAST")
    private fun mergePreferences(disk: Preferences, pending: Preferences): Preferences {
        val merged = disk.toMutablePreferences()
        pending.asMap().forEach { (key, value) ->
            merged[key as Preferences.Key<Any>] = value
        }
        return merged
    }

    /** ينتظر (حتى [timeoutMs]) تفريغ كل التعديلات المعلقة إلى القرص. */
    fun flushBlocking(timeoutMs: Long = 400L): Boolean {
        val completed = runBlocking {
            withTimeoutOrNull(timeoutMs) {
                val done = CompletableDeferred<Unit>()
                diskEdits.send(DiskEdit.Barrier(done))
                done.await()
            }
        }
        return completed != null
    }

    /** تحميل غير معطِّل للإقلاع: يُنادى مرة من App.onCreate. فشل التحميل يُعلن
     * (حالة + سجل) لا يُسقط صمتاً ويُنهي العملية: التطبيق يعمل على القيم
     * الافتراضية في اللقطة ويحاول الكتابة لاحقاً. */
    fun preload() {
        scope.launch {
            runCatching { ensureLoaded() }
                .onFailure { t ->
                    AppLog.e("settings", "تعذر تحميل الإعدادات من القرص؛ تعمل الجلسة على الافتراضيات", t)
                    queue.markBroken()
                }
        }
    }

    /**
     * ترحيل واحد من إعدادات 0.5.0 (SharedPreferences): إن وجدت قيم قديمة وDataStore
     * فارغ تُنقل كلها ثم يُحذف الملف القديم فلا يُرحَّل مرتين ولا تعود بعد «استعادة
     * الافتراضيات».
     */
    private suspend fun migrateLegacyIfNeeded() {
        val legacy = appContext.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
        val old = legacy.all
        if (old.isEmpty()) return
        val existing = data.data.first().asMap().keys.map { it.name }.toSet()
        val pending = synchronized(this) {
            snapshot?.asMap()?.keys?.map { it.name }?.toSet() ?: emptySet()
        }
        val dropped = mutableListOf<String>()
        data.edit { prefs ->
            old.forEach { (key, value) ->
                if (key !in existing && key !in pending) {
                    when (value) {
                        is Boolean -> prefs[booleanPreferencesKey(key)] = value
                        is String -> prefs[stringPreferencesKey(key)] = value
                        is Int -> prefs[intPreferencesKey(key)] = value
                        is Long -> prefs[longPreferencesKey(key)] = value
                        is Float -> prefs[floatPreferencesKey(key)] = value
                        is Set<*> -> @Suppress("UNCHECKED_CAST") (value as? Set<String>)?.let {
                            prefs[stringSetPreferencesKey(key)] = it
                        } ?: dropped.add(key)
                        else -> dropped.add(key)
                    }
                }
            }
        }
        if (dropped.isEmpty()) {
            legacy.edit().clear().apply()
        } else {
            AppLog.e("settings", "مفاتيح تراثية لم تُرحّل: ${dropped.joinToString("، ")}")
        }
    }

    private fun loaded(): Preferences = snapshot ?: mutablePreferencesOf()

    // ---------- القراءة والكتابة (لقطة فورية + استمرار غير متزامن) ----------

    private fun putBooleanValue(key: Preferences.Key<Boolean>, value: Boolean) =
        mutateSnapshotAndPersist { prefs ->
            prefs[key] = value
            DiskEdit.SetValue(key, DiskEdit.SupportedValue.Bool(value))
        }

    private fun putStringValue(key: Preferences.Key<String>, value: String) =
        mutateSnapshotAndPersist { prefs ->
            prefs[key] = value
            DiskEdit.SetValue(key, DiskEdit.SupportedValue.Str(value))
        }

    /** تعديل اللقطة وإدخال تعديل القرص داخل القفل نفسه وبالترتيب نفسه. */
    private fun mutateSnapshotAndPersist(
        block: (androidx.datastore.preferences.core.MutablePreferences) -> DiskEdit
    ) {
        synchronized(this) {
            val updated = loaded().toMutablePreferences()
            val edit = block(updated)
            snapshot = updated
            if (diskEdits.trySend(edit).isFailure) AppLog.e("settings", "تعذر جدولة كتابة الإعدادات على القرص")
        }
    }

    // ---------- الإعدادات القياسية ----------

    var showHidden: Boolean
        get() = loaded()[SHOW_HIDDEN] ?: false
        set(value) = putBooleanValue(SHOW_HIDDEN, value)
    /** طريقة عرض الملفات: شبكة (true) أو قائمة (false). تُغيَّر من الإعدادات. */
    var gridView: Boolean
        get() = loaded()[GRID_VIEW] ?: false
        set(value) = putBooleanValue(GRID_VIEW, value)

    var foldersFirst: Boolean
        get() = loaded()[FOLDERS_FIRST] ?: true
        set(value) = putBooleanValue(FOLDERS_FIRST, value)

    /**
     * (مراجعة 0.8.6-#3) حدود أرشفة مخفَّفة: ملفات مشروعة شديدة الانضغاط (صور
     * أقراص مفرّغة، سجلات ضخمة) كانت تُرفض عند سقف 200:1. الإيقاف الافتراضي يحفظ
     * حماية القنابل المضغوطة؛ التمكين يرفع السقف إلى 2000:1 مع هامش أكبر.
     */
    var archiveRelaxedLimits: Boolean
        get() = loaded()[ARCHIVE_RELAXED] ?: false
        set(value) = putBooleanValue(ARCHIVE_RELAXED, value)

    /** سياسة التعارض الافتراضية للنسخ/النقل. ASK = حوار. */
    fun conflictPolicy(): ConflictPolicy {
        val raw = loaded()[CONFLICT_POLICY]
        return runCatching { ConflictPolicy.valueOf(raw ?: "") }.getOrDefault(ConflictPolicy.ASK)
    }

    // ---------- التاريخ والإشعارات والعودة للمصدر والتكرار ----------

    /** تكرار الموسيقى الافتراضي عند بدء التشغيل. */
    var defaultRepeat: RepeatMode
        get() = runCatching { RepeatMode.valueOf(loaded()[DEFAULT_REPEAT] ?: "") }
            .getOrDefault(RepeatMode.OFF)
        set(value) = putStringValue(DEFAULT_REPEAT, value.name)

    /** الفرز الافتراضي للمجلدات التي لم يُخصص لها فرز (قيمة مجهولة ← الافتراض). */
    var defaultSort: FolderSort
        get() = FolderSort.decode(loaded()[DEFAULT_SORT])
            ?: FolderSort(SortFieldCore.NAME, SortDirectionCore.ASCENDING)
        set(value) = putStringValue(DEFAULT_SORT, value.encode())

    fun reset() {
        synchronized(this) {
            loadGeneration++
            snapshot = mutablePreferencesOf()
            loadedFromDisk = true
            diskEdits.trySend(DiskEdit.ClearAll)
        }
    }

    val saveFailed: StateFlow<Boolean> get() = queue.saveFailed

    /**
     * (E2) إعادة المحاولة: إن فشل التحميل أعيد أولاً (فالطابور فارغ لا يُصلحه
     * التفريغ)، ثم يُفرّج المعلق. علم التحميل لا يُمسح إلا بنجاح إعادة التحميل.
     */
    suspend fun retryPendingWrites() {
        if (queue.loadFailed.value) {
            runCatching { ensureLoaded() }
                .onSuccess { queue.clearLoadFailed() }
                .onFailure { t ->
                    AppLog.e("settings", "تعذر إعادة تحميل الإعدادات من القرص", t)
                }
        }
        queue.retryPending()
    }

    companion object {
        private val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        private val FOLDERS_FIRST = booleanPreferencesKey("folders_first")
        private val GRID_VIEW = booleanPreferencesKey("grid_view")
        private val CONFLICT_POLICY = stringPreferencesKey("conflict_policy")
        private val DEFAULT_REPEAT = stringPreferencesKey("default_repeat")
        private val DEFAULT_SORT = stringPreferencesKey("default_sort")
        private val ARCHIVE_RELAXED = booleanPreferencesKey("archive_relaxed_limits")

        /** اسم ملف SharedPreferences في الإصدارات قبل 0.6.0 (يُرحَّل مرة واحدة). */
        private const val LEGACY_NAME = "open_file_manager"

        /** مهلة إعادة محاولة كتابة القرص الفاشلة. */
        private const val DISK_WRITE_RETRY_MS = 250L

        /** عدد محاولات كتابة القرص قبل إعلان الفشل بحالة قابلة للملاحظة. */
        private const val DISK_WRITE_MAX_ATTEMPTS = 3

        @Volatile
        private var instance: AppSettings? = null

        /** مثال مفرد لكل عملية: لقطة واحدة يشترك فيها كل المستدعين. */
        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext)
                    .also { instance = it }
            }
    }
}

internal sealed interface DiskEdit {
    sealed interface SupportedValue {
        data class Bool(val v: Boolean) : SupportedValue
        data class Str(val v: String) : SupportedValue
        data class Num(val v: Int) : SupportedValue
        data class LongNum(val v: Long) : SupportedValue
        data class FloatNum(val v: Float) : SupportedValue
    }

    data class SetValue(val key: Preferences.Key<*>, val value: SupportedValue) : DiskEdit
    data class Remove(val key: Preferences.Key<*>) : DiskEdit
    data object ClearAll : DiskEdit
    /** حاجز: يكتمل بعد كتابة كل ما سبقه في القناة (انظر flushBlocking). */
    data class Barrier(val done: CompletableDeferred<Unit>) : DiskEdit
}

internal class SettingsDiskEditQueue(
    private val apply: suspend (DiskEdit) -> Unit,
    private val retryMs: Long,
    private val maxAttempts: Int,
    private val onGiveUp: (DiskEdit, Int, Throwable) -> Unit
) {
    private val pending = ArrayList<DiskEdit>()

    /** (E2) فشل الكتابة: يرفعه فشل drain النهائي (onGiveUp) حصراً. انتهاء مهلة
     *  flushBlocking ليس فشل كتابة فلا يرفعه. */
    private val writeFailed = MutableStateFlow(false)

    /** (E2) فشل التحميل (preload فشل): مستقل عن الكتابة؛ لا يمسحه إلا
     *  إعادة تحميل ناجحة (clearLoadFailed من retryPendingWrites). */
    private val _loadFailed = MutableStateFlow(false)

    private val _saveFailed = MutableStateFlow(false)

    private val drainLock = Mutex()

    /** فشل حفظ ظاهر للواجهة: فشل كتابة أو فشل تحميل (أيهما). */
    val saveFailed: StateFlow<Boolean> get() = _saveFailed.asStateFlow()

    /** فشل تحميل القرص: يحدد هل retryPendingWrites يعيد التحميل قبل التفريغ. */
    val loadFailed: StateFlow<Boolean> get() = _loadFailed.asStateFlow()

    /** لقطة الطابور للتشخيص والاختبار (نسخة، لا تعرض الداخل). */
    fun pendingSnapshot(): List<DiskEdit> = synchronized(this) { pending.toList() }

    private fun refreshIndicator() {
        _saveFailed.value = writeFailed.value || _loadFailed.value
    }

    /** فشل خارجي (تحميل قرص فاشل مثلاً) يرفع علم التحميل بلا تعديلات طابور. */
    fun markBroken() {
        _loadFailed.value = true
        refreshIndicator()
    }

    /** (E2) إعادة تحميل ناجحة: مسح علم التحميل وحده؛ علم الكتابة لا يُمس هنا. */
    fun clearLoadFailed() {
        _loadFailed.value = false
        refreshIndicator()
    }

    /** يستقبل تعديلاً (من كوروتين المستهلك وحده) ثم يصرف الطابور بالترتيب. */
    suspend fun submit(edit: DiskEdit) {
        synchronized(this) { coalesce(edit) }
        drainLock.withLock { drain() }
        synchronized(this) {
            if (pending.isEmpty()) writeFailed.value = false
            refreshIndicator()
        }
    }

    suspend fun retryPending() {
        var hadPending = false
        drainLock.withLock {
            synchronized(this) { hadPending = pending.isNotEmpty() }
            if (hadPending) drain()
        }
        if (hadPending) {
            synchronized(this) {
                if (pending.isEmpty()) writeFailed.value = false
                refreshIndicator()
            }
        }
    }

    /** قواعد الدمج: ClearAll يفوق الكل؛ المفتاح المكرر يستبدل في موضعه ضمن مقطعه. */
    private fun coalesce(edit: DiskEdit) {
        when (edit) {
            DiskEdit.ClearAll -> {
                pending.clear()
                pending.add(edit)
            }
            is DiskEdit.Barrier -> pending.add(edit)
            is DiskEdit.SetValue, is DiskEdit.Remove -> {
                // نطاق الدمج: منذ آخر حاجز (ClearAll/Barrier) فقط.
                var i = pending.size - 1
                while (i >= 0) {
                    val p = pending[i]
                    if (p is DiskEdit.Barrier || p == DiskEdit.ClearAll) break
                    if (sameKey(p, edit)) {
                        pending[i] = edit
                        return
                    }
                    i--
                }
                pending.add(edit)
            }
        }
    }

    private fun sameKey(a: DiskEdit, b: DiskEdit): Boolean {
        val ka = when (a) {
            is DiskEdit.SetValue -> a.key
            is DiskEdit.Remove -> a.key
            else -> return false
        }
        val kb = when (b) {
            is DiskEdit.SetValue -> b.key
            is DiskEdit.Remove -> b.key
            else -> return false
        }
        return ka == kb
    }

    /** يعالج الطابور من الرأس؛ فشل نهائي عند الرأس يوقف المعالجة بلا إسقاط. */
    private suspend fun drain() {
        while (true) {
            val head = synchronized(this) { pending.firstOrNull() } ?: return
            var attempt = 0
            var succeeded = false
            var lastError: Throwable? = null
            while (true) {
                val result = runCatching { apply(head) }
                if (result.isSuccess) { succeeded = true; break }
                lastError = result.exceptionOrNull()
                attempt++
                if (attempt >= maxAttempts) break
                delay(retryMs)
            }
            if (succeeded) {
                synchronized(this) {
                    val idx = pending.indexOf(head)
                    if (idx >= 0) pending.removeAt(idx)
                }
            } else {
                writeFailed.value = true
                refreshIndicator()
                onGiveUp(head, attempt, lastError ?: IllegalStateException("disk write failed"))
                break
            }
        }
    }
}

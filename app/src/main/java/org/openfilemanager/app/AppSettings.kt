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
import org.openfilemanager.core.KvStore
import org.openfilemanager.core.NewFilesStore
import org.openfilemanager.core.PerFolderSortStore
import org.openfilemanager.core.SortDirectionCore
import org.openfilemanager.core.SortFieldCore

/**
 * DataStore للإعدادات: بديل SharedPreferences الآمن من الخيوط.
 * مثال واحد لكل عملية؛ الكتابة تُحدّث لقطة في الذاكرة فوراً وتُنظِّم في القرص
 * (DataStore) من نطاق داخلي يُلغى مع العملية — بلا حظر متزامن للإقلاع.
 * الظهور داخلي لا خاص: يتيح اختبار الترحيل مباشرة من اختبارات الوحدة.
 *
 * (تخريب) مستند DataStore تالف يُستبدل بمعالج صريح (قيم افتراضية) لا انهيار
 * عند كل إقلاع: كل القيم تفضيلات قابلة لإعادة الاشتقاق، لا ملفات مستخدم —
 * البديل الموثق: الافتراضيات + سجل خطأ.
 */
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

    /**
 * كل تعديل قرص يمر عبر هذه القناة: يُصف داخل كتلة synchronized نفسها التي
 * تعدّل اللقطة، ويستهلكه كوروتين واحد — فيطابق ترتيبُ القرص ترتيبَ اللقطة
 * تماماً، ولا يمكن لكتابة سابقة أن تعود وتهبط بعد إعادة الضبط.
 */
    /**
     * كل تعديل قرص يمر عبر هذه القناة. (F23) قيمة SetValue ليست Any سائبة بل
     * مغلفة بنوعها المادي — الأنواع الخمسة التي تدعمها Preferences فعلاً — فلا
     * يمر عبر القناة كائن عابر غير متوقع.
     */
    private sealed interface DiskEdit {
        /** قيمة مغلَفة بنوعها المادي (F23). */
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
        /** حاجز: يكتمل بعد كتابة كل ما سبقه في القناة (انظر [flushBlocking]). */
        data class Barrier(val done: CompletableDeferred<Unit>) : DiskEdit
    }

    private val diskEdits = Channel<DiskEdit>(Channel.UNLIMITED)

    /** تعديلات لم تكتب للقرص بعد (بالفشل أو بالانتظار) بترتيب وصولها. */
    private val retryQueue = ArrayDeque<DiskEdit>()

    init {
        startDiskConsumer()
    }

    /** كتابة القيمة المادية في Preferences حسب نوعها (F23)؛ الأنواع القياسية الخمسة حصراً. */
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
                // (F-17/تحديث) التعديل الفاشل نهائياً (بعد كل المحاولات) كان
                // يُسقط ويُستهلك ما بعده، فتكتب قيمة أحدث ناجحة مفتاحها هي فقط
                // ويضيع المفتاح الفاشل عند الإقلاع التالي (كتابة لاحقة لا دوام
                // ما قبلها — DataStore يطبّق التحويل على الحالة المرصودة لا على
                // اللقطة). الآن: التعديل يبقى في طابور إعادة المحاولة ويُعاد قبل
                // أي تعديل جديد (الترتيب على القرص يطابق ترتيب اللقطة)، وحاجز
                // flushBlocking القادم يعيد المحاولة أيضاً؛ العلم [saveFailed]
                // لا يُمسح إلا بفراغ الطابور كله.
                retryQueue.addLast(edit)
                drainRetryQueue()
                if (retryQueue.isEmpty() && _saveFailed.value) _saveFailed.value = false
            }
        }
    }

    /**
     * يعالج طابور إعادة المحاولة بالترتيب: التعديل عند رأسه يُعاد [DISK_WRITE_MAX_ATTEMPTS]
     * محاولة؛ إن استمر فشله تُكسر المعالجة ويبقى بلا إسقاط (تُعاد عند التعديل
     * أو الحاجز التالي) مع سقف حجم للطابور.
     */
    private suspend fun drainRetryQueue() {
        while (retryQueue.isNotEmpty()) {
            val head = retryQueue.first()
            var attempt = 0
            var succeeded = false
            while (true) {
                val result = runCatching { applyDiskEdit(head) }
                if (result.isSuccess) { succeeded = true; break }
                attempt++
                if (attempt >= DISK_WRITE_MAX_ATTEMPTS) {
                    AppLog.e(
                        "settings",
                        "تعذر حفظ تعديل الإعدادات على القرص بعد $attempt محاولات (${head.javaClass.simpleName})",
                        result.exceptionOrNull()
                    )
                    break
                }
                delay(DISK_WRITE_RETRY_MS)
            }
            if (succeeded) {
                retryQueue.removeFirst()
            } else {
                _saveFailed.value = true
                if (retryQueue.size > MAX_QUEUED_EDITS) {
                    // سقف الذاكرة: الأقدم يُرمى أخيراً (مع سجل) — القيمة موجودة في
                    // اللقطة للجلسة والعلم مرفوع فالخسارة مرئية لا صامتة.
                    AppLog.e("settings", "طابور تعديلات الإعدادات المعلّقة يتجاوز الحد؛ يُرمى الأقدم")
                    retryQueue.removeFirst()
                }
                break
            }
        }
    }

    /**
     * (F-17) فشل حفظ الإعدادات على القرص مع تعديلات معلّقة لم تُصرّف: اللقطة
     * في الذاكرة تُبقي القراءة صحيحة لهذه الجلسة، والعلم يتيح للواجهة الإشارة
     * إلى أن آخر التعديلات قد لا تثبت عند إغلاق التطبيق. يُمسح فقط عند
     * فراغ طابور إعادة المحاولة (كتابة ناجحة حقيقية لكل ما قبله).
     */
    private val _saveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

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

    /**
     * ينتظر (حتى [timeoutMs]) تفريغ كل التعديلات المعلقة إلى القرص. يُنادى من onStop حتى
     * لا يضيع تعديل إعداد إن قُتلت العملية بعد ذلك مباشرة. أفضل جهد لا يحجب أكثر من المهلة.
     * (F-17) يعيد true عند اكتمال التفريغ فعلاً — انتهاء المهلة ليس flush مؤكداً،
     * ويُعلن بحالة [saveFailed] ولا يُدّعى نجاحاً صامتاً.
     */
    fun flushBlocking(timeoutMs: Long = 400L): Boolean {
        val completed = runBlocking {
            withTimeoutOrNull(timeoutMs) {
                val done = CompletableDeferred<Unit>()
                diskEdits.send(DiskEdit.Barrier(done))
                done.await()
            }
        }
        val ok = completed != null
        if (!ok) _saveFailed.value = true
        return ok
    }

    /** تحميل غير معطِّل للإقلاع: يُنادى مرة من App.onCreate. فشل التحميل يُعلن
     * (حالة + سجل) لا يُسقط صمتاً ويُنهي العملية: التطبيق يعمل على القيم
     * الافتراضية في اللقطة ويحاول الكتابة لاحقاً. */
    fun preload() {
        scope.launch {
            runCatching { ensureLoaded() }
                .onFailure { t ->
                    AppLog.e("settings", "تعذر تحميل الإعدادات من القرص؛ تعمل الجلسة على الافتراضيات", t)
                    _saveFailed.value = true
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

    /** سياسة التعارض الافتراضية للنسخ/النقل. ASK = حوار. */
    fun conflictPolicy(): ConflictPolicy {
        val raw = loaded()[CONFLICT_POLICY]
        return runCatching { ConflictPolicy.valueOf(raw ?: "") }.getOrDefault(ConflictPolicy.ASK)
    }

    fun setConflictPolicy(policy: ConflictPolicy) {
        putStringValue(CONFLICT_POLICY, policy.name)
    }

    // ---------- التاريخ والإشعارات والعودة للمصدر والتكرار ----------

    /** تكرار الموسيقى الافتراضي عند بدء التشغيل. */
    var defaultRepeat: RepeatMode
        get() = runCatching { RepeatMode.valueOf(loaded()[DEFAULT_REPEAT] ?: "") }
            .getOrDefault(RepeatMode.OFF)
        set(value) = putStringValue(DEFAULT_REPEAT, value.name)

    // ---------- المخازن الصرفة من :core فوق جسر KvStore ----------

    /**
     * جسر KvStore ← DataStore: القراءة من اللقطة، والكتابة تحدّثها فوراً وتستمر
     * في القرص من النطاق الداخلي. مفاتيح المخازن مسبوقة (srt./new.) فلا تتصادم
     * مع مفاتيح الإعدادات القياسية.
     */
    private val kv = object : KvStore {
        override fun getString(key: String): String? = loaded()[stringPreferencesKey(key)]

        override fun putString(key: String, value: String) {
            val k = stringPreferencesKey(key)
            mutateSnapshotAndPersist { prefs ->
                prefs[k] = value
                DiskEdit.SetValue(k, DiskEdit.SupportedValue.Str(value))
            }
        }

        override fun remove(key: String) {
            val k = stringPreferencesKey(key)
            mutateSnapshotAndPersist { prefs ->
                prefs.remove(k)
                DiskEdit.Remove(k)
            }
        }

        override fun keys(): Set<String> =
            loaded().asMap().keys.map { it.name }
                .filter { it.startsWith("srt.") || it.startsWith("new.") }.toSet()
    }

    /** فرز لكل مجلد بهاش المسار، سقف 200. */
    val perFolderSort = PerFolderSortStore(kv)

    /** علامات «الملفات الجديدة»، سقف 500 معرّف. */
    val newFiles = NewFilesStore(kv)

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

    companion object {
        private val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        private val FOLDERS_FIRST = booleanPreferencesKey("folders_first")
        private val GRID_VIEW = booleanPreferencesKey("grid_view")
        private val CONFLICT_POLICY = stringPreferencesKey("conflict_policy")
        private val DEFAULT_REPEAT = stringPreferencesKey("default_repeat")
        private val DEFAULT_SORT = stringPreferencesKey("default_sort")

        /** اسم ملف SharedPreferences في الإصدارات قبل 0.6.0 (يُرحَّل مرة واحدة). */
        private const val LEGACY_NAME = "open_file_manager"

        /** مهلة إعادة محاولة كتابة القرص الفاشلة. */
        private const val DISK_WRITE_RETRY_MS = 250L

        /** (F-17) عدد محاولات كتابة القرص قبل إعلان الفشل بحالة قابلة للملاحظة. */
        private const val DISK_WRITE_MAX_ATTEMPTS = 3

        /** سقف التعديلات المعلّقة في طابور إعادة المحاولة (ذاكرة الطابور محدودة). */
        private const val MAX_QUEUED_EDITS = 256

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

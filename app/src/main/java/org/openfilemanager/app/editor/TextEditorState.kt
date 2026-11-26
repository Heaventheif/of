package org.openfilemanager.app.editor

import java.io.File
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.openfilemanager.core.AtomicIO
import org.openfilemanager.core.DraftStore
import org.openfilemanager.core.TextFile

/** أسباب فشل فتح الملف — تُترجَم إلى موارد النص في الشاشة (كانت Int? لمورد سلفاً). */
internal enum class ReadFailure { TooLarge, ReadError }

/** نتيجة الحفظ — تُترجَم إلى مورد النص ولون الـSnackbar في الشاشة (كانت Int سلفاً). */
internal enum class SaveOutcome { Saved, Encoding, TooLarge, Changed, Error }

/** ملاحظة نتيجة استيراد الخط — تُترجَم إلى مورد النص في الشاشة (كانت Int? سلفاً). */
internal enum class FontNote { Imported, Error }

/** نتيجة التحميل للشاشة: النص الافتتاحي للحقل، وهل استُعيدت مسودة أحدث (لعرض رسالتها). */
internal class LoadResult(val textToOpen: String, val restoredDraft: Boolean, val error: ReadFailure?)

internal class TextEditorState(
    /** عرض فقط عند الإنشاء؛ الشاشة تُزامن القيمة الحالية في SideEffect (كما تلتقط الإغلاقات السابقة قيمتها الحالية). */
    readOnly: Boolean,
    /** مخزن المسودات (java.nio فقط — قابل للاختبار على JVM بمجلد مؤقت). */
    private val drafts: DraftStore,
    /** مجلد الخطوط المستوردة (نظير filesDir/fonts في التطبيق). */
    private val fontsDir: File,
    /** ناقل الإدخال/الإخراج — حقن لاختبار متزامن محدَّد. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** مصدر نانو-ثواني لتسمية الملف المؤقت عند استيراد الخطوط. */
    private val nowNs: () -> Long = System::nanoTime,
    /** قراءة بايتات ملف بالمسار — حقن لمحاكاة فشل القراءة/الهاش في الاختبار. */
    private val readFileBytes: (String) -> ByteArray = { File(it).readBytes() },
    /**
     * الكتابة الذرية (مؤقت ← مزامنة ← استبدال). الافتراضي AtomicIO.atomicWrite —
     * بروتوكول السلف حرفياً؛ حقنه للاختبار يتحقق من ترتيب مؤقت←إعادة تسمية وأن
     * فشله يترك الملف الأصلي سليماً بلا محتوى جزئي.
     */
    private val atomicWrite: (Path, ByteArray) -> Unit = { target, bytes ->
        AtomicIO.atomicWrite(target) { it.write(bytes) }
    },
    /** مهلة سكون الكتابة قبل كتابة المسودة — حقن لاختبار محدَّد بلا نوم. */
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {

    /**
     * عرض فقط: لا مسودات ولا حفظ، والاتساخ دائماً false. تغييره يعيد حساب الاتساخ
     * فوراً كما كانت سلفاً تُحسب في كل recomposition بقيمة readOnly الحالية.
     */
    var readOnly: Boolean = readOnly
        set(value) {
            field = value
            computeDirty()
        }

    // ---- حبات الحالة التي ترسمها الشاشة ----
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _failed = MutableStateFlow<ReadFailure?>(null)
    val failed: StateFlow<ReadFailure?> = _failed.asStateFlow()
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _matches = MutableStateFlow<List<Int>>(emptyList())
    val matches: StateFlow<List<Int>> = _matches.asStateFlow()
    private val _cur = MutableStateFlow(0)
    val cur: StateFlow<Int> = _cur.asStateFlow()
    private val _navTick = MutableStateFlow(0)
    val navTick: StateFlow<Int> = _navTick.asStateFlow()
    private val _searchNav = MutableStateFlow(false)
    val searchNav: StateFlow<Boolean> = _searchNav.asStateFlow()
    private val _importedFonts = MutableStateFlow<List<String>>(emptyList())
    val importedFonts: StateFlow<List<String>> = _importedFonts.asStateFlow()
    private val _fontNote = MutableStateFlow<FontNote?>(null)
    val fontNote: StateFlow<FontNote?> = _fontNote.asStateFlow()

    // ---- حالة وثيقة داخلية (كانت remember في الشاشة: original/hash/charset/bom) ----
    private var original = ""
    private var hash = ByteArray(0)
    private var fileCharset: Charset = Charsets.UTF_8
    private var fileBom = ByteArray(0)
    private var current = 0 // الفهرس الخام للتنقل؛ `cur` المعروض مقصوص دائماً ضمن التطابقات

    /** الاتساخ = ليس للعرض فقط والنص يخالف مرجع آخر حفظ (كانت L198 حرفياً). */
    private fun computeDirty() {
        _dirty.value = !readOnly && _text.value != original
    }

    // ---- التحميل (كان LaunchedEffect(path) في L249-269) ----

    private class Loaded(
        val text: String = "", val hash: ByteArray = ByteArray(0), val error: ReadFailure? = null,
        val charset: Charset = Charsets.UTF_8, val bom: ByteArray = ByteArray(0)
    )

    /**
     * يفتح الملف بالمسار: سقف الحجم ثم فك الترميز الأصلي وهاش SHA-256، ثم استعادة
     * مسودة أحدث من الأصل (للعرض فقط لا مسودة). loading يُنزل آخراً كما كان.
     */
    suspend fun load(path: String): LoadResult {
        val r: Loaded = withContext(io) {
            runCatching {
                val file = File(path)
                if (!file.isFile || file.length() > MAX_BYTES) Loaded(error = ReadFailure.TooLarge)
                else {
                    val bytes = readFileBytes(path)
                    val decoded = TextFile.decode(bytes)
                    Loaded(decoded.text, MessageDigest.getInstance("SHA-256").digest(bytes), null, decoded.charset, decoded.bom)
                }
            }.getOrElse { Loaded(error = ReadFailure.ReadError) }
        }
        if (r.error == null) {
            original = r.text; hash = r.hash; fileCharset = r.charset; fileBom = r.bom
            // مسودة غير محفوظة من جلسة انقطعت: تُستعاد فوق الأصل ويبقى «غير محفوظ» حتى يضغط المستخدم حفظ.
            val draft = if (readOnly) null else withContext(io) { runCatching { drafts.load(path) }.getOrNull() }
            val restored = draft?.takeIf { it != r.text }
            setText(restored ?: r.text)
            _loading.value = false
            return LoadResult(textToOpen = restored ?: r.text, restoredDraft = restored != null, error = null)
        } else {
            _failed.value = r.error
            _loading.value = false
            return LoadResult(textToOpen = "", restoredDraft = false, error = r.error)
        }
    }

    // ---- النص والاتساخ (كان onValueChange يغذي field/text في L194-198, L438-441) ----

    /** دفعة نص جديد من الحقل بعد كل ضغطة إدخال. النص نفسه: لا إعادة حساب (كما dedupe السلف). */
    fun onEditText(newText: String) {
        if (newText == _text.value) return
        setText(newText)
    }

    /** تحديث النص مع إعادة حساب التطابقات والفهرس المقصوص والاتساخ دفعة واحدة. */
    private fun setText(newText: String) {
        _text.value = newText
        _matches.value = computeMatches(newText, _query.value)
        _cur.value = coercedCur(_matches.value)
        computeDirty()
    }

    // ---- البحث (كانت L201-247) ----

    /** فتح/إغلاق شريط البحث؛ الإغلاق يفرغ الاستعلام (كانت L323 حرفياً). */
    fun toggleSearch() {
        val next = !_searching.value
        _searching.value = next
        if (!next) onQueryChanged("")
    }

    /**
     * استعلام جديد: ابدأ من أول تطابق وانتقل إليه؛ إفراغ الاستعلام يعيد التمرير لمؤشر
     * الكتابة (كانت LaunchedEffect(query) في L222-226). القيمة نفسها: بلا فعل (كما
     * كانت مفاتيح الـLaunchedEffect تقارن بالقيمة).
     */
    fun onQueryChanged(q: String) {
        if (q == _query.value) return
        _query.value = q
        _matches.value = computeMatches(_text.value, q)
        current = 0
        _cur.value = coercedCur(_matches.value)
        _searchNav.value = q.isNotEmpty()
        if (q.isNotEmpty()) _navTick.value += 1
    }

    /** التنقل بين التطابقات بلفّ حول الطرفين؛ بلا تطابقات: بلا فعل (كانت step في L227-232). */
    fun step(delta: Int) {
        val ms = _matches.value
        if (ms.isEmpty()) return
        _searchNav.value = true
        current = (coercedCur(ms) + delta + ms.size) % ms.size
        _cur.value = current
        _navTick.value += 1 // يعيد التمرير حتى لو بقي الفهرس نفسه (تطابق وحيد)
    }

    /** أي تحرير فعلي للنص أو تحريك المؤشر ينزل علم «التنقل بين النتائج» (كانت L439). */
    fun clearSearchNav() {
        _searchNav.value = false
    }

    /** مواضع كل التطابقات (بلا حساسية لحالة الأحرف) بسقف [MAX_MATCHES] — خوارزمية L205-211 حرفياً. */
    private fun computeMatches(text: String, query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var i = text.indexOf(query, 0, ignoreCase = true)
        while (i >= 0 && out.size < MAX_MATCHES) {
            out.add(i)
            i = text.indexOf(query, i + query.length, ignoreCase = true)
        }
        return out
    }

    /** الفهرس المعروض دائماً ضمن 0..size-1: تعديل النص أثناء البحث قد يقلّص التطابقات (L213). */
    private fun coercedCur(ms: List<Int>): Int = if (ms.isEmpty()) 0 else current.coerceIn(0, ms.size - 1)

    /**
     * هدف التمرير للتطابق عند قمة سطره [top]: ثلث نافذة العرض تمهيداً، محصوراً بين
     * 48 و240 بكسل، وبلا نزول تحت الصفر (كانت L241-242).
     */
    fun matchScrollTarget(top: Float, viewportH: Int): Int =
        (top - (viewportH / 3).coerceIn(MATCH_LEAD_MIN_PX, MATCH_LEAD_MAX_PX)).toInt().coerceAtLeast(0)

    /** القفزة الفورية للتطابقات البعيدة بدل التحريك المتحرك عبر الملف كله (كانت L245). */
    fun isFarJump(target: Int, currentScroll: Int): Boolean =
        kotlin.math.abs(target - currentScroll) > FAR_JUMP_PX

    // ---- المسودة (كانت L186-192 وL296-301 والتجاهل في L464) ----

    /** بوابة الحفظ التلقائي: شرط LaunchedEffect السلف حرفياً (loading/readOnly/dirty). */
    fun shouldAutosaveDraft(): Boolean = !_loading.value && !readOnly && _dirty.value

    /**
     * حفظ تلقائي للمسودة بعد سكون الكتابة: مهلة [DRAFT_AUTOSAVE_DELAY_MS] من
     * [delayFn] ثم كتابة ذرية للمسودة بالنص الحالي. الفشل يبتلع (أفضل جهد كما كان).
     */
    suspend fun autosaveDraft(path: String) {
        delayFn(DRAFT_AUTOSAVE_DELAY_MS)
        withContext(io) { runCatching { drafts.save(path, _text.value) } }
    }

    /** حذف مسودة الملف (عند الحفظ الناجح وبعد التجاهل) — أفضل جهد كما كان. */
    suspend fun clearDraft(path: String) {
        withContext(io) { runCatching { drafts.remove(path) } }
    }

    /** تنظيف المسودات اليتيمة الأقدم من [DRAFT_MAX_AGE_MILLIS] عند فتح المحرر — أفضل جهد. */
    fun pruneDrafts() {
        runCatching { drafts.prune(DRAFT_MAX_AGE_MILLIS) }
    }

    // ---- الحفظ الذري (كانت save() في L271-294) — الترتيب مثبَّت حرفياً ----

    /** ترميز بالترميز الأصلي (فشل التمثيل: [SaveOutcome.Encoding])، سقف الحجم ([SaveOutcome.TooLarge])، فحص تغيّر الملف تحتنا بالهاش. */
    suspend fun save(path: String): SaveOutcome = withContext(io) {
        runCatching {
            val bytes = try {
                TextFile.encode(_text.value, fileCharset, fileBom)
            } catch (_: CharacterCodingException) {
                return@runCatching SaveOutcome.Encoding
            }
            if (bytes.size > MAX_BYTES) return@runCatching SaveOutcome.TooLarge
            val now = MessageDigest.getInstance("SHA-256").digest(readFileBytes(path))
            if (!now.contentEquals(hash)) return@runCatching SaveOutcome.Changed
            // مؤقت + fsync + استبدال (مع بديل غير ذري على FAT) وتنظيف المؤقت عند الفشل.
            atomicWrite(File(path).toPath(), bytes)
            runCatching { drafts.remove(path) }
            hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            original = _text.value
            computeDirty()
            SaveOutcome.Saved
        }.getOrDefault(SaveOutcome.Error)
    }

    // ---- استيراد الخطوط (كانت fontsDir/listFonts L512-515 وimportFont L530-561 وFontPicker L563-615) ----

    /** يحدّث قائمة الخطوط المستوردة (ملفات عادية بلا أسماء تبدأ بنقطة) مرتبةً. */
    fun refreshImportedFonts() {
        _importedFonts.value = listImportedFonts()
    }

    /** كانت listFonts(ctx) في L514-515 حرفياً على مجلد الخطوط المحقون. */
    private fun listImportedFonts(): List<String> =
        fontsDir.listFiles { f -> f.isFile && !f.name.startsWith(".") }?.map { it.name }?.sorted().orEmpty()

    /** ضغط المستخدم زر الاستيراد: تزول الملاحظة السابقة قبل فتح المنتقي (كانت L606). */
    fun onFontPickRequested() {
        _fontNote.value = null
    }

    /** استيراد ناجح: تُحدَّث القائمة وتُعلن الملاحظة (كانت L574-576؛ مفتاح النمط يظل بالشاشة). */
    fun onFontImported() {
        refreshImportedFonts()
        _fontNote.value = FontNote.Imported
    }

    /** فشل الاستيراد بأي سبب: الملاحظة خطأ فقط (كانت L572). */
    fun onFontImportFailed() {
        _fontNote.value = FontNote.Error
    }

    /** حذف خط مستورد: يزول الملف وتُحدَّث القائمة (كانت L597-599؛ قرار النمط يظل بالشاشة). */
    fun deleteImportedFont(name: String) {
        File(fontsDir, name).delete()
        refreshImportedFonts()
    }

    /** نسخ خط مختار إلى مجلد الخطوط بعد التحقق، مع كل الفشل مطوياً في null كما كان importFont السلف (L530-561). */
    suspend fun importFont(
        display: String?,
        openStream: (() -> InputStream?)?,
        canLoad: (File) -> Boolean
    ): String? = withContext(io) {
        runCatching {
            display ?: return@runCatching null
            val ext = display.substringAfterLast('.', "").lowercase()
            if (ext != "ttf" && ext != "otf") return@runCatching null
            val safe = sanitize(display) ?: return@runCatching null
            val dir = fontsDir.apply { mkdirs() }
            val tmp = File(dir, ".import-${nowNs()}.tmp")
            try {
                val input = openStream?.invoke() ?: return@runCatching null
                input.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_FONT_BYTES) return@runCatching null
                            out.write(buf, 0, n)
                        }
                    }
                }
                if (!canLoad(tmp)) return@runCatching null
                val target = File(dir, safe)
                if (target.exists()) target.delete()
                if (tmp.renameTo(target)) safe else null
            } finally {
                tmp.delete()
            }
        }.getOrNull()
    }

    /**
     * اشتقاق الاسم الآمن للخط (كانت L536-537): استبدال ما ليس حرفاً/رقماً/نقطة/شرطة
     * سفلية/مسافة بشرطة سفلية، ثم إزالة النقاط الابتدائية، وقصّه إلى 80 محرفاً؛
     * الاسم الفارغ بعد ذلك يرفض. الامتداد يُفحص قبل الاستدعاء (ttf/otf فقط).
     */
    internal fun sanitize(display: String): String? {
        val safe = display.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trimStart('.').take(80)
        return if (safe.isBlank()) null else safe
    }

    companion object {
        /** سقف حجم الملف المفتوح (كانت L120). */
        const val MAX_BYTES = 2 * 1024 * 1024

        /** سقف عدد التطابقات المحسوبة (كانت L121). */
        const val MAX_MATCHES = 5000

        /** أقصى عمر لمسودة غير محفوظة قبل تنظيفها عند فتح المحرر: 30 يوماً (كانت L124). */
        const val DRAFT_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** مهلة سكون الكتابة قبل الحفظ التلقائي للمسودة (كانت delay(1500) في L299). */
        const val DRAFT_AUTOSAVE_DELAY_MS = 1500L

        /** سقف حجم خط مستورد (كانت L142). */
        const val MAX_FONT_BYTES = 20L * 1024 * 1024

        /** تمهيد التمرير قبل التطابق: الحدان الأدنى والأعلى للثلث المحصور (كانت L241). */
        const val MATCH_LEAD_MIN_PX = 48
        const val MATCH_LEAD_MAX_PX = 240

        /** فوق هذه المسافة للتطابق تكون القفزة فورية بدل التحريك المتحرك (كانت L245). */
        const val FAR_JUMP_PX = 2000
    }
}

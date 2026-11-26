package org.openfilemanager.app.reader

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.openfilemanager.app.AppDispatchers
import org.openfilemanager.app.FeatureFlags
import org.openfilemanager.core.DocFingerprint

private val Context.readerDataStore: DataStore<Preferences> by preferencesDataStore(name = "reader")

private fun legacyTextEditorFontOf(context: Context): Int? =
    runCatching {
        val sp = context.applicationContext.getSharedPreferences("editor_style", Context.MODE_PRIVATE)
        if (sp.contains("size")) sp.getInt("size", ReaderPrefs.DEFAULT_TEXT_EDITOR_FONT) else null
    }.getOrNull()

enum class ReaderTheme { SYSTEM, LIGHT, DARK }

enum class ReaderFormat { PDF, DOCX }

data class ReaderPrefs(
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    val docxFontPx: Int = DEFAULT_DOCX_FONT,
    val pptxFontPx: Int = DEFAULT_DOCX_FONT,
    val pdfZoom: Float = 1f,
    val pdfDark: Boolean = false,
    val textEditorFontPx: Int = DEFAULT_TEXT_EDITOR_FONT
) {
    companion object {
        const val DEFAULT_DOCX_FONT = 18
        const val MIN_DOCX_FONT = 12
        const val MAX_DOCX_FONT = 34
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 3f
        /** حدود خط محرر النصوص = ثوابت الشاشة نفسها (MIN_SIZE/MAX_SIZE في TextEditorScreen). */
        const val DEFAULT_TEXT_EDITOR_FONT = 16
        const val MIN_TEXT_EDITOR_FONT = 11
        const val MAX_TEXT_EDITOR_FONT = 28
    }
}

/**
 * Where the reader was. PDF: [a] = page index, [f] = fraction of the page already scrolled past.
 * DOCX: [a] = block index in the converted document, [f] = fraction through that block. [p] = overall progress.
 */
data class ReadingPosition(val a: Int, val f: Float, val p: Float, val updated: Long)

data class Bookmark(
    val id: String,
    val title: String,
    val format: ReaderFormat,
    val a: Int,
    val f: Float,
    val created: Long
)

/** Reading position, bookmarks and preferences, all keyed by content fingerprint ([DocFingerprint]). */
class ReaderStore private constructor(
    private val ds: DataStore<Preferences>,
    private val legacyTextEditorFont: () -> Int?
) {

    /** البناء الاعتيادي من السياق (متجر DataStore المفرد للتطبيق). */
    constructor(context: Context) : this(
        context.applicationContext.readerDataStore,
        legacyTextEditorFont = { legacyTextEditorFontOf(context) }
    )

    @Volatile private var pptxFontMigrated = false

    @Volatile private var textEditorFontMigrated = false
    private val migrationMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun migratePptxFontOnce(snapshot: Preferences): Boolean {
        if (pptxFontMigrated) return false
        migrationMutex.withLock {
            if (pptxFontMigrated) return false
            try {
                if (snapshot[K_PPTX_FONT] == null && snapshot[K_DOCX_FONT] != null) {
                    // نسخة لمرة واحدة من قيمة DOCX القديمة: تُنسخ ولا تُحذف أبداً،
                    // والكتابة ترفض نفسها إن سبقها كاتب آخر (تحرير-if-null).
                    val docx = snapshot[K_DOCX_FONT]!!
                    ds.edit { cur ->
                        if (cur[K_PPTX_FONT] == null) {
                            cur[K_PPTX_FONT] = docx.coerceIn(ReaderPrefs.MIN_DOCX_FONT, ReaderPrefs.MAX_DOCX_FONT)
                        }
                    }
                    return true
                }
            } finally {
                pptxFontMigrated = true
            }
        }
        return false
    }

    private suspend fun migrateTextEditorFontOnce(snapshot: Preferences): Boolean {
        if (!FeatureFlags.perReaderPrefs) return false
        if (textEditorFontMigrated) return false
        migrationMutex.withLock {
            if (textEditorFontMigrated) return false
            try {
                if (snapshot[K_TEXT_FONT] == null) {
                    // نسخة لمرة واحدة من القيمة القديمة (SharedPreferences المحرر):
                    // تُنسخ ولا تُحذف أبداً، والكتابة ترفض نفسها إن سبقها كاتب آخر.
                    val legacy = runCatching { legacyTextEditorFont() }.getOrNull()
                    if (legacy != null) {
                        ds.edit { cur ->
                            if (cur[K_TEXT_FONT] == null) {
                                cur[K_TEXT_FONT] = legacy.coerceIn(ReaderPrefs.MIN_TEXT_EDITOR_FONT, ReaderPrefs.MAX_TEXT_EDITOR_FONT)
                            }
                        }
                        return true
                    }
                }
            } finally {
                textEditorFontMigrated = true
            }
        }
        return false
    }

    private fun buildPrefs(p: Preferences): ReaderPrefs = ReaderPrefs(
        theme = p[K_THEME]?.let { n -> ReaderTheme.entries.firstOrNull { it.name == n } } ?: ReaderTheme.SYSTEM,
        docxFontPx = (p[K_DOCX_FONT] ?: ReaderPrefs.DEFAULT_DOCX_FONT).coerceIn(ReaderPrefs.MIN_DOCX_FONT, ReaderPrefs.MAX_DOCX_FONT),
        pptxFontPx = (p[K_PPTX_FONT] ?: ReaderPrefs.DEFAULT_DOCX_FONT).coerceIn(ReaderPrefs.MIN_DOCX_FONT, ReaderPrefs.MAX_DOCX_FONT),
        pdfZoom = (p[K_PDF_ZOOM] ?: 1f).coerceIn(ReaderPrefs.MIN_ZOOM, ReaderPrefs.MAX_ZOOM),
        pdfDark = p[K_PDF_DARK] ?: false,
        textEditorFontPx = (p[K_TEXT_FONT] ?: ReaderPrefs.DEFAULT_TEXT_EDITOR_FONT).coerceIn(ReaderPrefs.MIN_TEXT_EDITOR_FONT, ReaderPrefs.MAX_TEXT_EDITOR_FONT)
    )

    val prefs: Flow<ReaderPrefs> = ds.data.map { p ->
        // الهجرة تكتب المفتاح الجديد؛ نعيد القراءة حتى يأتي أول إصدار بالقيمة المنسوخة.
        val pptxMigrated = migratePptxFontOnce(p)
        val textMigrated = migrateTextEditorFontOnce(p)
        if (pptxMigrated || textMigrated) buildPrefs(ds.data.first()) else buildPrefs(p)
    }

    suspend fun prefsNow(): ReaderPrefs = prefs.first()

    suspend fun setTheme(t: ReaderTheme) { ds.edit { it[K_THEME] = t.name } }
    suspend fun setDocxFont(px: Int) { ds.edit { it[K_DOCX_FONT] = px.coerceIn(ReaderPrefs.MIN_DOCX_FONT, ReaderPrefs.MAX_DOCX_FONT) } }
    suspend fun setPptxFont(px: Int) { ds.edit { it[K_PPTX_FONT] = px.coerceIn(ReaderPrefs.MIN_DOCX_FONT, ReaderPrefs.MAX_DOCX_FONT) } }
    suspend fun setPdfZoom(z: Float) { ds.edit { it[K_PDF_ZOOM] = z.coerceIn(ReaderPrefs.MIN_ZOOM, ReaderPrefs.MAX_ZOOM) } }
    suspend fun setPdfDark(on: Boolean) { ds.edit { it[K_PDF_DARK] = on } }

    suspend fun setTextEditorFont(px: Int) { ds.edit { it[K_TEXT_FONT] = px.coerceIn(ReaderPrefs.MIN_TEXT_EDITOR_FONT, ReaderPrefs.MAX_TEXT_EDITOR_FONT) } }

    // ---- document identity ----------------------------------------------------------------------------------

    suspend fun docId(file: File): String = withContext(AppDispatchers.io) { DocFingerprint.of(file) }

    // ---- reading position -----------------------------------------------------------------------------------

    suspend fun position(docId: String, format: ReaderFormat): ReadingPosition? {
        val raw = ds.data.first()[stringPreferencesKey("pos.${format.name}.$docId")] ?: return null
        return runCatching {
            val o = JSONObject(raw)
            ReadingPosition(o.getInt("a"), o.optDouble("f", 0.0).toFloat(), o.optDouble("p", 0.0).toFloat(), o.optLong("t"))
        }.getOrNull()
    }

    suspend fun savePosition(docId: String, format: ReaderFormat, pos: ReadingPosition) {
        val key = stringPreferencesKey("pos.${format.name}.$docId")
        val value = JSONObject().put("a", pos.a).put("f", pos.f.toDouble()).put("p", pos.p.toDouble()).put("t", pos.updated).toString()
        ds.edit { prefs ->
            prefs[key] = value
            // Keep the list of known positions bounded: forget the least recently read documents.
            val index = decodeIndex(prefs[K_POS_INDEX])
            index.remove(key.name)
            index.add(key.name)
            while (index.size > MAX_POSITIONS) {
                val old = index.removeAt(0)
                prefs.remove(stringPreferencesKey(old))
            }
            prefs[K_POS_INDEX] = JSONArray(index).toString()
        }
    }

    private fun decodeIndex(raw: String?): MutableList<String> {
        val out = ArrayList<String>()
        if (raw == null) return out
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) out.add(a.getString(i))
        }
        return out
    }

    // ---- bookmarks ------------------------------------------------------------------------------------------

    fun bookmarks(docId: String, format: ReaderFormat): Flow<List<Bookmark>> =
        ds.data.map { decodeBookmarks(it[bmKey(docId, format)], format) }

    suspend fun addBookmark(docId: String, format: ReaderFormat, title: String, a: Int, f: Float): Bookmark {
        val b = Bookmark(UUID.randomUUID().toString(), title.trim().take(MAX_TITLE), format, a, f, System.currentTimeMillis())
        update(docId, format) { list -> (list + b).takeLast(MAX_BOOKMARKS) }
        return b
    }

    suspend fun removeBookmark(docId: String, format: ReaderFormat, id: String) {
        update(docId, format) { list -> list.filterNot { it.id == id } }
    }

    suspend fun renameBookmark(docId: String, format: ReaderFormat, id: String, title: String) {
        val t = title.trim().take(MAX_TITLE)
        if (t.isEmpty()) return
        update(docId, format) { list -> list.map { if (it.id == id) it.copy(title = t) else it } }
    }

    private suspend fun update(docId: String, format: ReaderFormat, transform: (List<Bookmark>) -> List<Bookmark>) {
        val key = bmKey(docId, format)
        ds.edit { prefs ->
            val next = transform(decodeBookmarks(prefs[key], format))
            if (next.isEmpty()) prefs.remove(key) else prefs[key] = encodeBookmarks(next)
        }
    }

    private fun bmKey(docId: String, format: ReaderFormat) = stringPreferencesKey("bm.${format.name}.$docId")

    private fun encodeBookmarks(list: List<Bookmark>): String {
        val arr = JSONArray()
        for (b in list) {
            arr.put(JSONObject().put("i", b.id).put("t", b.title).put("a", b.a).put("f", b.f.toDouble()).put("c", b.created))
        }
        return arr.toString()
    }

    private fun decodeBookmarks(raw: String?, format: ReaderFormat): List<Bookmark> {
        if (raw == null) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Bookmark(o.getString("i"), o.optString("t"), format, o.getInt("a"), o.optDouble("f", 0.0).toFloat(), o.optLong("c"))
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        /** للاختبار على JVM: بناء المتجر من DataStore فوق ملف مؤقت بدل سياق أندرويد (ومصدر القيمة القديمة محقون). */
        internal fun forDataStore(ds: DataStore<Preferences>, legacyTextEditorFont: () -> Int? = { null }): ReaderStore =
            ReaderStore(ds, legacyTextEditorFont)

        private val K_THEME = stringPreferencesKey("theme")
        private val K_DOCX_FONT = intPreferencesKey("docx_font_px")
        private val K_PPTX_FONT = intPreferencesKey("pptx_font_px")
        private val K_PDF_ZOOM = floatPreferencesKey("pdf_zoom")
        private val K_PDF_DARK = booleanPreferencesKey("pdf_dark")
        private val K_TEXT_FONT = intPreferencesKey("text_editor_font_px")
        private val K_POS_INDEX = stringPreferencesKey("pos_index")
        private const val MAX_POSITIONS = 300
        private const val MAX_BOOKMARKS = 500
        private const val MAX_TITLE = 120
    }
}

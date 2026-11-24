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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.openfilemanager.app.AppDispatchers
import org.openfilemanager.core.DocFingerprint

/** Reader-only DataStore: kept apart from the editor's settings so neither can affect the other. */
private val Context.readerDataStore: DataStore<Preferences> by preferencesDataStore(name = "reader")

enum class ReaderTheme { SYSTEM, LIGHT, DARK }

enum class ReaderFormat { PDF, DOCX }

data class ReaderPrefs(
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    val docxFontPx: Int = DEFAULT_DOCX_FONT,
    val pdfZoom: Float = 1f,
    val pdfDark: Boolean = false
) {
    companion object {
        const val DEFAULT_DOCX_FONT = 18
        const val MIN_DOCX_FONT = 12
        const val MAX_DOCX_FONT = 34
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 3f
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
class ReaderStore(context: Context) {

    private val ds = context.applicationContext.readerDataStore

    // ---- preferences ----------------------------------------------------------------------------------------

    val prefs: Flow<ReaderPrefs> = ds.data.map { p ->
        ReaderPrefs(
            theme = p[K_THEME]?.let { n -> ReaderTheme.entries.firstOrNull { it.name == n } } ?: ReaderTheme.SYSTEM,
            docxFontPx = (p[K_DOCX_FONT] ?: ReaderPrefs.DEFAULT_DOCX_FONT).coerceIn(ReaderPrefs.MIN_DOCX_FONT, ReaderPrefs.MAX_DOCX_FONT),
            pdfZoom = (p[K_PDF_ZOOM] ?: 1f).coerceIn(ReaderPrefs.MIN_ZOOM, ReaderPrefs.MAX_ZOOM),
            pdfDark = p[K_PDF_DARK] ?: false
        )
    }

    suspend fun prefsNow(): ReaderPrefs = prefs.first()

    suspend fun setTheme(t: ReaderTheme) { ds.edit { it[K_THEME] = t.name } }
    suspend fun setDocxFont(px: Int) { ds.edit { it[K_DOCX_FONT] = px.coerceIn(ReaderPrefs.MIN_DOCX_FONT, ReaderPrefs.MAX_DOCX_FONT) } }
    suspend fun setPdfZoom(z: Float) { ds.edit { it[K_PDF_ZOOM] = z.coerceIn(ReaderPrefs.MIN_ZOOM, ReaderPrefs.MAX_ZOOM) } }
    suspend fun setPdfDark(on: Boolean) { ds.edit { it[K_PDF_DARK] = on } }

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

    private companion object {
        val K_THEME = stringPreferencesKey("theme")
        val K_DOCX_FONT = intPreferencesKey("docx_font_px")
        val K_PDF_ZOOM = floatPreferencesKey("pdf_zoom")
        val K_PDF_DARK = booleanPreferencesKey("pdf_dark")
        val K_POS_INDEX = stringPreferencesKey("pos_index")
        const val MAX_POSITIONS = 300
        const val MAX_BOOKMARKS = 500
        const val MAX_TITLE = 120
    }
}

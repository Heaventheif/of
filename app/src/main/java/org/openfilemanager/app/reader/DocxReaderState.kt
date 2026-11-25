package org.openfilemanager.app.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import org.openfilemanager.app.AppDispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.openfilemanager.app.R

data class DocxHeading(val id: String, val level: Int, val text: String)

/** State and commands of the DOCX reader. The WebView only ever shows the one file in [file], served locally. */
@Stable
class DocxReaderState(
    val file: File,
    private val store: ReaderStore,
    private val scope: CoroutineScope,
    private val context: Context
) {
    var status: ReaderStatus by mutableStateOf(ReaderStatus.Loading)
        private set
    /** True once Mammoth finished and the document is in the page. */
    var contentReady by mutableStateOf(false)
        private set
    var prefs by mutableStateOf(ReaderPrefs())
        private set
    var headings by mutableStateOf<List<DocxHeading>>(emptyList())
        private set
    var bookmarks by mutableStateOf<List<Bookmark>>(emptyList())
        private set

    var progress by mutableFloatStateOf(0f)
        private set
    var block by mutableIntStateOf(0)
        private set
    var blockFraction by mutableFloatStateOf(0f)
        private set
    var currentHeading by mutableStateOf("")
        private set
    var snippet by mutableStateOf("")
        private set

    var searchOpen by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var matchCount by mutableIntStateOf(0)
        private set
    var matchIndex by mutableIntStateOf(-1)
        private set
    var matchesTruncated by mutableStateOf(false)
        private set

    var webView: WebView? = null
    private var docId = ""
    private var saved: ReadingPosition? = null
    private var searchJob: Job? = null
    private var saveJob: Job? = null
    private var bookmarkJob: Job? = null
    private var fontJob: Job? = null
    private var persistJob: Job? = null
    private val ioScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + AppDispatchers.io)
    private val main = Handler(Looper.getMainLooper())

    /** The URL the WebView loads: parameters give the right theme and font size from the first paint. */
    var startUrl: String = ""
        private set

    // ---- loading --------------------------------------------------------------------------------------------

    suspend fun load(systemDark: Boolean) {
        status = ReaderStatus.Loading
        contentReady = false
        // Disk stats off the main thread
        val (exists, readable, length) = withContext(AppDispatchers.io) { Triple(file.exists(), file.canRead(), file.length()) }
        if (!exists) { status = ReaderStatus.Error(R.string.rd_err_missing, false); return }
        if (!readable) { status = ReaderStatus.Error(R.string.rd_err_unreadable, true); return }
        if (length > MAX_BYTES) { status = ReaderStatus.Error(R.string.rd_err_too_large, true); return }
        val head = withContext(AppDispatchers.io) {
            try {
                FileInputStream(file).use { s ->
                    val n = ByteArray(4)
                    if (s.read(n) < 4) null else n
                }
            } catch (e: IOException) {
                null
            }
        }
        when {
            head == null -> { status = ReaderStatus.Error(R.string.rd_err_corrupt_docx, true); return }
            // OLE2 header: a binary .doc saved with a .docx name.
            head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() && head[2] == 0x11.toByte() && head[3] == 0xE0.toByte() ->
            { status = ReaderStatus.Error(R.string.rd_err_legacy_doc, true); return }
            head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte() -> { status = ReaderStatus.Error(R.string.rd_err_corrupt_docx, true); return }
        }
        docId = try { store.docId(file) } catch (e: Exception) { status = ReaderStatus.Error(R.string.rd_err_unreadable, true); return }
        prefs = store.prefsNow()
        saved = store.position(docId, ReaderFormat.DOCX)
        bookmarkJob?.cancel()
        bookmarkJob = scope.launch { store.bookmarks(docId, ReaderFormat.DOCX).collect { bookmarks = it } }
        startUrl = "https://$HOST/reader.html?theme=${if (resolveDark(systemDark)) "dark" else "light"}&fs=${prefs.docxFontPx}"
        status = ReaderStatus.Ready
    }

    fun resolveDark(systemDark: Boolean): Boolean = when (prefs.theme) {
        ReaderTheme.SYSTEM -> systemDark
        ReaderTheme.LIGHT -> false
        ReaderTheme.DARK -> true
    }

    fun close() {
        persistPositionNow()
        searchJob?.cancel(); saveJob?.cancel(); bookmarkJob?.cancel(); fontJob?.cancel()
        webView = null
    }

    fun onWebError() { main.post { status = ReaderStatus.Error(R.string.rd_err_web, true, canRetry = true) } }

    // ---- bridge events (arrive on the main thread) ----------------------------------------------------------

    internal fun onLoaded(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (!o.optBoolean("ok")) {
            status = when (o.optString("error")) {
                "empty" -> ReaderStatus.Error(R.string.rd_err_empty, true)
                // (F-12) تجاوز ميزانية ZIP بعد فك الضغط (عدد/أحجام/نسبة/وسائط).
                "budget" -> ReaderStatus.Error(R.string.rd_err_budget, true)
                else -> ReaderStatus.Error(R.string.rd_err_corrupt_docx, true)
            }
            return
        }
        val arr = o.optJSONArray("headings")
        val list = ArrayList<DocxHeading>()
        if (arr != null) for (i in 0 until arr.length()) {
            val h = arr.getJSONObject(i)
            list.add(DocxHeading(h.getString("id"), h.getInt("level"), h.optString("text")))
        }
        headings = list
        contentReady = true
        saved?.let { eval("OFM.restore(${it.a}, ${it.f})") }
    }

    internal fun onScroll(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        block = o.optInt("b")
        blockFraction = o.optDouble("f", 0.0).toFloat()
        progress = o.optDouble("p", 0.0).toFloat().coerceIn(0f, 1f)
        currentHeading = o.optString("ht")
        snippet = o.optString("s")
        schedulePositionSave()
    }

    internal fun onSearch(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        matchCount = o.optInt("count")
        matchIndex = o.optInt("index", -1)
        matchesTruncated = o.optBoolean("truncated")
    }

    internal fun onLink(json: String): String? = runCatching { JSONObject(json).getString("url") }.getOrNull()

    // ---- commands -------------------------------------------------------------------------------------------

    private fun eval(js: String) { webView?.evaluateJavascript(js, null) }

    fun applyTheme(dark: Boolean) { if (contentReady) eval("OFM.setTheme('${if (dark) "dark" else "light"}')") }

    fun setTheme(t: ReaderTheme) { prefs = prefs.copy(theme = t); scope.launch { store.setTheme(t) } }

    fun setFontSize(px: Int) {
        val v = px.coerceIn(ReaderPrefs.MIN_DOCX_FONT, ReaderPrefs.MAX_DOCX_FONT)
        if (v == prefs.docxFontPx) return
        prefs = prefs.copy(docxFontPx = v)
        eval("OFM.setFontSize($v)") // reflows in place; the page keeps the reading spot
        fontJob?.cancel()
        fontJob = scope.launch { delay(400); store.setDocxFont(v) }
    }

    fun scrollToHeading(id: String) { eval("OFM.scrollToHeading(${JSONObject.quote(id)})") }

    fun scrollToPercent(percent: Int) { eval("OFM.scrollToFraction(${percent.coerceIn(0, 100) / 100.0})") }

    fun jumpTo(b: Bookmark) { eval("OFM.restore(${b.a}, ${b.f})") }

    // ---- reading position -----------------------------------------------------------------------------------

    private fun schedulePositionSave() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(700); persistPositionNow() }
    }

    fun persistPositionNow() {
        if (!contentReady || docId.isEmpty()) return
        val pos = ReadingPosition(block, blockFraction, progress, System.currentTimeMillis())
        // One tracked in-flight save (latest wins); not tied to the screen scope so the final save on close survives
        persistJob?.cancel()
        persistJob = ioScope.launch { store.savePosition(docId, ReaderFormat.DOCX, pos) }
    }

    // ---- bookmarks ------------------------------------------------------------------------------------------

    fun bookmarkHere(): Bookmark? = bookmarks.firstOrNull { it.a == block }

    /** Suggested name: the heading we are under, else the first words of the current paragraph, else the percentage. */
    fun suggestedTitle(): String = currentHeading.ifBlank { snippet.take(40) }.ifBlank {
        context.getString(R.string.rd_bookmark_default_pos, (progress * 100).roundToInt())
    }

    fun addBookmark(title: String) { scope.launch { store.addBookmark(docId, ReaderFormat.DOCX, title, block, blockFraction) } }
    fun removeBookmark(b: Bookmark) { scope.launch { store.removeBookmark(docId, ReaderFormat.DOCX, b.id) } }
    fun renameBookmark(b: Bookmark, title: String) { scope.launch { store.renameBookmark(docId, ReaderFormat.DOCX, b.id, title) } }

    // ---- search ---------------------------------------------------------------------------------------------

    fun openSearch() { searchOpen = true }

    fun closeSearch() {
        searchOpen = false; query = ""; matchCount = 0; matchIndex = -1; matchesTruncated = false
        searchJob?.cancel()
        eval("OFM.clearSearch()")
    }

    fun setQueryText(q: String) {
        query = q
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(250)
            if (q.isBlank()) { matchCount = 0; matchIndex = -1; eval("OFM.clearSearch()") }
            else eval("OFM.search(${JSONObject.quote(q)})")
        }
    }

    fun nextMatch() = eval("OFM.next()")
    fun prevMatch() = eval("OFM.prev()")

    // ---- local resource server (the only thing the WebView can load) ------------------------------------------

    internal fun serve(request: WebResourceRequest): WebResourceResponse {
        val u = request.url
        if (request.method != "GET" || u.scheme != "https" || u.host != HOST) return blocked()
        return try {
            when (u.path) {
                "/reader.html" -> asset("docx/reader.html", "text/html")
                "/reader.js" -> asset("docx/reader.js", "application/javascript")
                "/reader.css" -> asset("docx/reader.css", "text/css")
                "/mammoth.min.js" -> asset("docx/mammoth.min.js", "application/javascript")
                "/doc.docx" -> response("application/vnd.openxmlformats-officedocument.wordprocessingml.document", FileInputStream(file))
                else -> blocked()
            }
        } catch (e: IOException) {
            blocked()
        }
    }

    private fun asset(name: String, mime: String) = response(mime, context.assets.open(name))

    private fun response(mime: String, data: InputStream): WebResourceResponse {
        val r = WebResourceResponse(mime, "utf-8", data)
        r.responseHeaders = mapOf(
            "Content-Security-Policy" to CSP,
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff"
        )
        return r
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** JS → app. Only string-in methods; each hops to the main thread before touching state. */
    inner class Bridge {
        @JavascriptInterface fun onLoaded(json: String) { main.post { this@DocxReaderState.onLoaded(json) } }
        @JavascriptInterface fun onScroll(json: String) { main.post { this@DocxReaderState.onScroll(json) } }
        @JavascriptInterface fun onSearch(json: String) { main.post { this@DocxReaderState.onSearch(json) } }
        @JavascriptInterface fun onLink(json: String) { main.post { pendingLink?.invoke(this@DocxReaderState.onLink(json)) } }
    }

    var pendingLink: ((String?) -> Unit)? = null

    companion object {
        const val HOST = "ofm.reader.internal"
        const val MAX_BYTES = 80L * 1024 * 1024
        private const val CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src data:; connect-src 'self'"
    }
}

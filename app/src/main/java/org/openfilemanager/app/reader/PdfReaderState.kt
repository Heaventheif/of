package org.openfilemanager.app.reader

import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import org.openfilemanager.app.AppDispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openfilemanager.app.R
import org.openfilemanager.core.pdf.PdfOutlineEntry

sealed interface ReaderStatus {
    data object Loading : ReaderStatus
    data object Ready : ReaderStatus
    data class Error(@StringRes val message: Int, val canOpenExternally: Boolean, val canRetry: Boolean = false) : ReaderStatus
}

/** Text of the page shown in the select/copy sheet; the highlighted line is [hlStart, hlEnd) (or -1). */
class PdfSelection(val page: Int, val text: String, val hlStart: Int, val hlEnd: Int)

/** Everything the PDF screen shows or does, kept out of the composables. Lives as long as the screen. */
@Stable
class PdfReaderState(
    val file: File,
    private val store: ReaderStore,
    private val scope: CoroutineScope
) {
    var status: ReaderStatus by mutableStateOf(ReaderStatus.Loading)
        private set
    var engine: PdfEngine? = null
        private set
    var pageCount by mutableIntStateOf(0)
        private set
    var aspects by mutableStateOf(FloatArray(0))
        private set
    var prefs by mutableStateOf(ReaderPrefs())
        private set
    var zoom by mutableFloatStateOf(1f)
        private set
    var bookmarks by mutableStateOf<List<Bookmark>>(emptyList())
        private set
    var outline by mutableStateOf<List<PdfOutlineEntry>>(emptyList())
        private set

    lateinit var listState: LazyListState
        private set
    var viewportWidthPx by mutableIntStateOf(0)
    var restored by mutableStateOf(false)
        private set

    private var saved: ReadingPosition? = null
    private var docId: String = ""
    private var indexJob: Deferred<PdfTextIndex?>? = null
    private var zoomJob: Job? = null
    private var searchJob: Job? = null
    private var bookmarkJob: Job? = null

    /** Request to reset the horizontal scroll (Reset zoom); consumed by the screen. */
    var resetHorizontal by mutableIntStateOf(0)
        private set

    // ---- text selection (the page is a bitmap, so text is offered through a sheet) ---------------------------
    var selection by mutableStateOf<PdfSelection?>(null)
        private set

    /** Opens the select/copy sheet for [page]; [yFrac] highlights the line under the finger. */
    fun selectAt(page: Int, yFrac: Double?) {
        scope.launch {
            val s = indexJob?.await()?.pageSelection(page, yFrac)
            selection = PdfSelection(page, s?.text.orEmpty(), s?.hlStart ?: -1, s?.hlEnd ?: -1)
        }
    }

    fun clearSelection() { selection = null }

    /** (page, fraction of the page height) under viewport-y [y], or null over a gap/outside. */
    fun pageAtViewportY(y: Float): Pair<Int, Double>? {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size } ?: return null
        val h = pageHeightPx(item.index).toFloat()
        if (h <= 0f) return null
        val f = ((y - item.offset) / h).toDouble()
        return if (f in 0.0..1.0) item.index to f else null
    }

    // ---- search ---------------------------------------------------------------------------------------------
    var searchOpen by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var hits by mutableStateOf<List<PdfHit>>(emptyList())
        private set
    var currentHit by mutableIntStateOf(-1)
        private set
    var scanned by mutableIntStateOf(0)
        private set
    var searching by mutableStateOf(false)
        private set
    var searchOutcome by mutableStateOf<PdfSearchOutcome?>(null)
        private set
    /** Page + [top, height] fractions of the line marker for the current hit. */
    var band by mutableStateOf<Pair<Int, DoubleArray>?>(null)
        private set

    // ---- lifecycle ------------------------------------------------------------------------------------------

    suspend fun load() {
        status = ReaderStatus.Loading
        if (!file.exists()) { status = ReaderStatus.Error(R.string.rd_err_missing, false); return }
        if (!file.canRead()) { status = ReaderStatus.Error(R.string.rd_err_unreadable, true); return }
        docId = try { store.docId(file) } catch (e: Exception) { status = ReaderStatus.Error(R.string.rd_err_unreadable, true); return }
        when (val r = PdfEngine.open(file)) {
            PdfOpenResult.Password -> { status = ReaderStatus.Error(R.string.rd_err_password, true); return }
            PdfOpenResult.Corrupt -> { status = ReaderStatus.Error(R.string.rd_err_corrupt_pdf, true); return }
            PdfOpenResult.Empty -> { status = ReaderStatus.Error(R.string.rd_err_empty, true); return }
            PdfOpenResult.Unreadable -> { status = ReaderStatus.Error(R.string.rd_err_unreadable, true); return }
            is PdfOpenResult.Ok -> engine = r.engine
        }
        val e = engine ?: return
        prefs = store.prefsNow()
        zoom = prefs.pdfZoom
        saved = store.position(docId, ReaderFormat.PDF)
        pageCount = e.pageCount
        val start = (saved?.a ?: 0).coerceIn(0, e.pageCount - 1)
        listState = LazyListState(firstVisibleItemIndex = start)
        aspects = FloatArray(e.pageCount) { 0.707f }
        status = ReaderStatus.Ready

        // (تصحيح التمرير) عند وصول النِّسب الحقيقية للصفحات تتغير ارتفاعات عناصر
        // القائمة، فيقفز الموضع المرئي قفزات غير منتظمة أثناء القراءة. نثبّت مرساة
        // القراءة (الصفحة + كسرها) قبل كل تحديث ونعيد تطبيقها بعده — إلا أثناء تمرير
        // نشط (سحب/قذف) فلا نقطع إيماءة المستخدم.
        scope.launch {
            e.scanSizes { snapshot ->
                val anchorPage = listState.firstVisibleItemIndex
                val oldH = (pageHeightPx(anchorPage) + GAP_PX).coerceAtLeast(1)
                val anchorFrac = (listState.firstVisibleItemScrollOffset.toFloat() / oldH).coerceIn(0f, 1f)
                aspects = snapshot
                val newH = (pageHeightPx(anchorPage) + GAP_PX).coerceAtLeast(1)
                val drift = kotlin.math.abs(newH - oldH)
                if (drift > 2 && !listState.isScrollInProgress) {
                    scope.launch { listState.scrollToItem(anchorPage, (anchorFrac * newH).roundToInt()) }
                }
            }
        }
        indexJob = scope.async(AppDispatchers.compute) {
            val idx = PdfTextIndex.open(file)
            if (idx != null) outline = idx.outline().filter { it.pageIndex in 0 until e.pageCount }
            idx
        }
        bookmarkJob = scope.launch { store.bookmarks(docId, ReaderFormat.PDF).collect { bookmarks = it } }
    }

    fun close() {
        persistPositionNow()
        searchJob?.cancel()
        bookmarkJob?.cancel()
        indexJob?.let { j ->
            if (j.isCompleted) runCatching { @Suppress("OPT_IN_USAGE") j.getCompleted()?.close() } else j.cancel()
        }
        engine?.close()
        engine = null
    }

    // ---- geometry -------------------------------------------------------------------------------------------

    fun aspectOf(page: Int): Float = aspects.getOrNull(page)?.takeIf { it > 0f } ?: 0.707f

    fun pageWidthPx(z: Float = zoom): Int = (viewportWidthPx * z).roundToInt().coerceAtLeast(1)

    fun pageHeightPx(page: Int, z: Float = zoom): Int = (pageWidthPx(z) / aspectOf(page)).roundToInt()

    // ---- position -------------------------------------------------------------------------------------------

    /** First restore once the viewport is measured; afterwards position changes are persisted. */
    suspend fun restorePosition() {
        if (restored || viewportWidthPx <= 0) return
        val p = saved
        if (p != null) {
            val page = p.a.coerceIn(0, pageCount - 1)
            listState.scrollToItem(page, (p.f * (pageHeightPx(page) + GAP_PX)).roundToInt().coerceAtLeast(0))
        }
        restored = true
    }

    fun currentPage(): Int {
        val info = listState.layoutInfo
        val vis = info.visibleItemsInfo
        if (vis.isEmpty()) return listState.firstVisibleItemIndex
        val top = info.viewportStartOffset
        val bottom = info.viewportEndOffset
        return vis.maxByOrNull { minOf(it.offset + it.size, bottom) - maxOf(it.offset, top) }?.index ?: 0
    }

    private fun currentFraction(): Float {
        val page = listState.firstVisibleItemIndex
        val h = (pageHeightPx(page) + GAP_PX).coerceAtLeast(1)
        return (listState.firstVisibleItemScrollOffset.toFloat() / h).coerceIn(0f, 1f)
    }

    fun persistPositionNow() {
        if (!restored || status != ReaderStatus.Ready || docId.isEmpty()) return
        val page = listState.firstVisibleItemIndex
        val f = currentFraction()
        val progress = ((page + f) / pageCount.coerceAtLeast(1)).coerceIn(0f, 1f)
        val pos = ReadingPosition(page, f, progress, System.currentTimeMillis())
        // The composition scope may already be gone when we leave: write on an independent scope.
        kotlinx.coroutines.CoroutineScope(AppDispatchers.io).launch { store.savePosition(docId, ReaderFormat.PDF, pos) }
    }

    fun scrollToPage(page: Int, fraction: Float = 0f) {
        if (pageCount == 0) return
        val p = page.coerceIn(0, pageCount - 1)
        scope.launch {
            listState.scrollToItem(p, (fraction * (pageHeightPx(p) + GAP_PX)).roundToInt().coerceAtLeast(0))
            persistPositionNow()
        }
    }

    // ---- zoom -----------------------------------------------------------------------------------------------

    fun applyZoom(z: Float) {
        val next = z.coerceIn(ReaderPrefs.MIN_ZOOM, ReaderPrefs.MAX_ZOOM)
        if (next == zoom) return
        val old = zoom
        val first = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        zoom = next
        // Keep the reading spot: item heights scale with zoom, so scale the in-page offset too.
        scope.launch { listState.scrollToItem(first, (offset * (next / old)).roundToInt()) }
        zoomJob?.cancel()
        zoomJob = scope.launch { delay(500); store.setPdfZoom(next) }
    }

    fun zoomBy(factor: Float) = applyZoom(zoom * factor)

    fun fitWidth() = applyZoom(1f)

    fun resetZoom() { applyZoom(1f); resetHorizontal++ }

    fun toggleZoom() = if (zoom > 1.2f) resetZoom() else applyZoom(2.5f)

    fun setDark(on: Boolean) { prefs = prefs.copy(pdfDark = on); scope.launch { store.setPdfDark(on) } }

    // ---- bookmarks ------------------------------------------------------------------------------------------

    fun bookmarkOnPage(page: Int): Bookmark? = bookmarks.firstOrNull { it.a == page }

    fun addBookmark(title: String) {
        val page = currentPage()
        val frac = if (listState.firstVisibleItemIndex == page) currentFraction() else 0f
        scope.launch { store.addBookmark(docId, ReaderFormat.PDF, title, page, frac) }
    }

    fun removeBookmark(b: Bookmark) { scope.launch { store.removeBookmark(docId, ReaderFormat.PDF, b.id) } }

    fun renameBookmark(b: Bookmark, title: String) { scope.launch { store.renameBookmark(docId, ReaderFormat.PDF, b.id, title) } }

    // ---- search ---------------------------------------------------------------------------------------------

    fun openSearch() { searchOpen = true }

    fun closeSearch() {
        searchOpen = false
        searchJob?.cancel()
        query = ""; hits = emptyList(); currentHit = -1; band = null
        searching = false; searchOutcome = null; scanned = 0
    }

    fun setQueryText(q: String) {
        query = q
        searchJob?.cancel()
        hits = emptyList(); currentHit = -1; band = null; searchOutcome = null; scanned = 0
        if (q.isBlank()) { searching = false; return }
        searching = true
        searchJob = scope.launch {
            delay(300) // let the user finish typing
            val idx = indexJob?.await()
            if (idx == null) { searching = false; searchOutcome = PdfSearchOutcome.UNREADABLE; return@launch }
            val outcome = try {
                idx.search(q) { found, done ->
                    val first = hits.isEmpty() && found.isNotEmpty()
                    hits = found
                    scanned = done
                    if (first) { currentHit = 0; scope.launch { showHit(0) } }
                }
            } finally {
                searching = false
            }
            searchOutcome = outcome
        }
    }

    fun stepHit(delta: Int) {
        if (hits.isEmpty()) return
        val next = (currentHit + delta + hits.size) % hits.size
        currentHit = next
        scope.launch { showHit(next) }
    }

    private suspend fun showHit(i: Int) {
        val h = hits.getOrNull(i) ?: return
        val idx = indexJob?.await()
        val b = idx?.band(h)
        band = b?.let { h.page to it }
        // Put the matching line near the top third of the screen when we know where it is.
        val frac = if (b != null) (b[0] - 0.08).coerceAtLeast(0.0).toFloat() else 0f
        val target = h.page.coerceIn(0, pageCount - 1)
        val offset = (frac * (pageHeightPx(target) + GAP_PX)).roundToInt()
        // (تصحيح التمرير) انتقال سلس بين النتائج المتقاربة؛ أما القفزات البعيدة
        // ففورية كي لا يعبر التمرير المتحرك مئات الصفحات ببطء.
        val distance = kotlin.math.abs(target - listState.firstVisibleItemIndex)
        if (distance <= 3) listState.animateScrollToItem(target, offset)
        else listState.scrollToItem(target, offset)
    }

    companion object {
        /** Space between pages in px (also part of every list item's height). */
        const val GAP_PX = 24
    }
}

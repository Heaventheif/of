package org.openfilemanager.app.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import org.openfilemanager.app.ui.theme.Dim
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import org.openfilemanager.app.R
import org.openfilemanager.app.appContainer
import org.openfilemanager.app.ui.components.ActionSheet
import org.openfilemanager.app.ui.components.SheetAction
import org.openfilemanager.app.ui.openWithSystem

/** invert(1) hue-rotate(180°): flips brightness but keeps hues, so colour figures stay recognisable. */
private val DARK_FILTER = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.574f, -1.430f, -0.144f, 0f, 255f,
            -0.426f, -0.430f, -0.144f, 0f, 255f,
            -0.426f, -1.430f, 0.856f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

@OptIn(FlowPreview::class)
@Composable
// (BidiSpoofing) عزلات اتجاه LRI/PDI مقصودة: أرقام داخل نص RTL للعربية
@Suppress("BidiSpoofing")
fun PdfReaderScreen(path: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ctx.appContainer.readerStore }
    val file = remember(path) { java.io.File(path) }
    var attempt by remember { mutableStateOf(0) }
    val state = remember(path, attempt) { PdfReaderState(file, store, scope) }

    LaunchedEffect(state) { state.load() }
    DisposableEffect(state) { onDispose { state.close() } }

    var showToc by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var addBookmark by remember { mutableStateOf(false) }

    BackHandler(enabled = state.searchOpen) { state.closeSearch() }

    val ready = state.status == ReaderStatus.Ready
    val page by remember(state) { derivedStateOf { if (state.status == ReaderStatus.Ready && state.restored) state.currentPage() else 0 } }

    // Persist the reading position shortly after scrolling settles.
    LaunchedEffect(state, ready) {
        if (!ready) return@LaunchedEffect
        snapshotFlow { state.listState.firstVisibleItemIndex to state.listState.firstVisibleItemScrollOffset }
            .debounce(600).collect { state.persistPositionNow() }
    }

    ReaderScaffold(
        title = file.name,
        onBack = { if (state.searchOpen) state.closeSearch() else onBack() },
        onSearch = { if (ready) { if (state.searchOpen) state.closeSearch() else state.openSearch() } },
        onToc = if (ready && state.outline.isNotEmpty()) ({ showToc = true }) else null,
        onMore = { showMenu = true },
        searchStrip = if (ready && state.searchOpen) ({
            val msg: String? = when {
                state.searching -> stringResource(R.string.rd_search_progress, state.scanned, state.pageCount)
                state.searchOutcome == PdfSearchOutcome.NO_TEXT -> stringResource(R.string.rd_search_unavailable)
                state.searchOutcome == PdfSearchOutcome.UNREADABLE -> stringResource(R.string.rd_search_unreadable)
                state.searchOutcome == PdfSearchOutcome.LIMIT -> stringResource(R.string.rd_search_limit, 1000)
                state.query.isNotBlank() && state.hits.isEmpty() && state.searchOutcome == PdfSearchOutcome.DONE -> stringResource(R.string.rd_search_none)
                else -> null
            }
            val hit = state.hits.getOrNull(state.currentHit)
            val label = if (state.hits.isEmpty()) "" else "⁦${state.currentHit + 1}/${state.hits.size}⁩"
            ReaderSearchStrip(
                query = state.query, onQuery = state::setQueryText, countLabel = label,
                message = listOfNotNull(msg, hit?.let { stringResource(R.string.rd_search_page_marker, it.page + 1) }).joinToString(" · ").ifEmpty { null },
                hasResults = state.hits.isNotEmpty(), onPrev = { state.stepHit(-1) }, onNext = { state.stepHit(1) },
                onClose = state::closeSearch
            )
        }) else null,
        bottomBar = {
            if (ready) {
                val count = state.pageCount
                ReaderBottomBar(
                    locationLabel = "⁦" + stringResource(R.string.rd_page_of, page + 1, count) + "⁩",
                    goToLabel = stringResource(R.string.rd_go_to_page),
                    progress = if (count > 0) (page + 1f) / count else 0f,
                    bookmarked = state.bookmarkOnPage(page) != null,
                    onGoTo = { showGoTo = true },
                    onToggleBookmark = {
                        val existing = state.bookmarkOnPage(page)
                        if (existing != null) state.removeBookmark(existing) else addBookmark = true
                    },
                    onBookmarks = { showBookmarks = true },
                    onSettings = { showSettings = true }
                )
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(pad)) {
            when (val s = state.status) {
                ReaderStatus.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ReaderStatus.Error -> ReaderErrorState(
                    stringResource(s.message),
                    onOpenExternal = if (s.canOpenExternally) ({ openWithSystem(ctx, path) }) else null,
                    onRetry = if (s.canRetry) ({ attempt++ }) else null, onBack = onBack
                )
                ReaderStatus.Ready -> PdfPages(state)
            }
        }
    }

    if (ready) {
        if (showToc) TableOfContentsSheet(
            entries = state.outline.map { TocEntry(it.title, it.level, it.pageIndex.toString()) },
            onPick = { e -> e.target.toIntOrNull()?.let { state.scrollToPage(it) } },
            onDismiss = { showToc = false }
        )
        if (showBookmarks) BookmarksSheet(
            bookmarks = state.bookmarks,
            subtitle = { ctx.getString(R.string.rd_bookmark_default_page, it.a + 1) },
            onPick = { state.scrollToPage(it.a, it.f) },
            onRename = state::renameBookmark, onDelete = state::removeBookmark,
            onDismiss = { showBookmarks = false }
        )
        if (showGoTo) NumberDialog(
            title = stringResource(R.string.rd_go_to_page),
            hint = stringResource(R.string.rd_page_hint, state.pageCount),
            invalidMessage = stringResource(R.string.rd_page_invalid, state.pageCount),
            initial = page + 1, min = 1, max = state.pageCount,
            onConfirm = { state.scrollToPage(it - 1); showGoTo = false }, onDismiss = { showGoTo = false }
        )
        if (addBookmark) TextInputDialog(
            title = stringResource(R.string.rd_bookmark_add), label = stringResource(R.string.rd_bookmark_name),
            initial = stringResource(R.string.rd_bookmark_default_page, page + 1), confirmLabel = stringResource(R.string.ui_save),
            onConfirm = { state.addBookmark(it); addBookmark = false }, onDismiss = { addBookmark = false }
        )
        if (showSettings) ReaderSettingsSheet({ showSettings = false }) {
            Text(stringResource(R.string.rd_zoom), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ state.zoomBy(1f / 1.25f) }) { Icon(Icons.Outlined.Remove, stringResource(R.string.rd_zoom_out)) }
                Text(
                    "⁦${(state.zoom * 100).roundToInt()}%⁩", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.width(72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                IconButton({ state.zoomBy(1.25f) }) { Icon(Icons.Outlined.Add, stringResource(R.string.rd_zoom_in)) }
                TextButton({ state.fitWidth() }) { Text(stringResource(R.string.rd_fit_width)) }
                TextButton({ state.resetZoom() }) { Text(stringResource(R.string.rd_reset_zoom)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.rd_pdf_dark), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.rd_pdf_dark_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(state.prefs.pdfDark, { state.setDark(it) })
            }
        }
    }
    state.selection?.let { sel -> PdfTextSheet(sel, onDismiss = state::clearSelection) }
    if (showMenu) ActionSheet(
        org.openfilemanager.app.BidiText.wrap(file.name),
        listOfNotNull(
            if (ready) SheetAction(Icons.Outlined.ContentCopy, stringResource(R.string.rd_select_text)) { state.selectAt(page, null) } else null,
            if (ready) SheetAction(Icons.Outlined.Bookmarks, stringResource(R.string.rd_bookmarks)) { showBookmarks = true } else null,
            if (ready) SheetAction(Icons.Outlined.Tune, stringResource(R.string.rd_reader_settings)) { showSettings = true } else null,
            SheetAction(Icons.Outlined.OpenInNew, stringResource(R.string.ui_open_with)) { openWithSystem(ctx, path) }
        )
    ) { showMenu = false }
}

@Composable
private fun PdfPages(state: PdfReaderState) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val hScroll = rememberScrollState()
    var settledZoom by remember { mutableStateOf(state.zoom) }
    LaunchedEffect(state.zoom) { delay(250); settledZoom = state.zoom }
    LaunchedEffect(state.resetHorizontal) { hScroll.scrollTo(0) }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val viewportPx = with(density) { maxWidth.roundToPx() }
        LaunchedEffect(viewportPx) { state.viewportWidthPx = viewportPx }
        LaunchedEffect(viewportPx, state.restored) { if (viewportPx > 0 && !state.restored) state.restorePosition() }

        val pageWidthPx = state.pageWidthPx()
        val containerPx = maxOf(viewportPx, pageWidthPx)
        val renderWidth = (((state.viewportWidthPx * settledZoom).roundToInt() + 63) / 64 * 64).coerceAtLeast(64)
        val gapDp = with(density) { PdfReaderState.GAP_PX.toDp() }

        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    // Two fingers = pinch zoom; one finger is left alone so normal scrolling keeps working.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.size >= 2 && event.changes.all { it.pressed }) {
                                val z = event.calculateZoom()
                                if (z != 1f) {
                                    state.zoomBy(z)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { state.toggleZoom() },
                        onLongPress = { off ->
                            state.pageAtViewportY(off.y)?.let { (p, f) ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                state.selectAt(p, f)
                            }
                        }
                    )
                }
                .horizontalScroll(hScroll)
        ) {
            LazyColumn(state = state.listState, modifier = Modifier.width(with(density) { containerPx.toDp() }).fillMaxSize()) {
                items(state.pageCount, key = { it }) { index ->
                    val heightPx = state.pageHeightPx(index)
                    Column(Modifier.width(with(density) { containerPx.toDp() })) {
                        Box(
                            Modifier.align(Alignment.CenterHorizontally)
                                .width(with(density) { pageWidthPx.toDp() })
                                .height(with(density) { heightPx.toDp() })
                        ) {
                            PdfPage(state, index, renderWidth, state.prefs.pdfDark)
                            state.band?.takeIf { it.first == index }?.let { (_, b) ->
                                Box(
                                    Modifier.fillMaxWidth()
                                        .offset(y = with(density) { (b[0] * heightPx).toFloat().toDp() })
                                        .height(with(density) { (b[1] * heightPx).toFloat().coerceAtLeast(6f).toDp() })
                                        .background(Color(0x66FFD600))
                                )
                            }
                        }
                        Spacer(Modifier.height(gapDp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(state: PdfReaderState, index: Int, renderWidth: Int, dark: Boolean) {
    val engine = state.engine
    var bmp by remember(index) { mutableStateOf<Bitmap?>(engine?.cached(index, renderWidth)) }
    var failed by remember(index) { mutableStateOf(false) }
    LaunchedEffect(index, renderWidth) {
        val e = engine ?: return@LaunchedEffect
        e.cached(index, renderWidth)?.let { bmp = it; failed = false; return@LaunchedEffect }
        val r = e.render(index, renderWidth)
        if (r != null) { bmp = r; failed = false } else if (bmp == null) failed = true
    }
    val desc = stringResource(R.string.rd_page_of, index + 1, state.pageCount)
    Box(
        Modifier.fillMaxSize().background(if (dark) Color.Black else Color.White)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            val image = remember(b) { b.asImageBitmap() }
            Image(
                bitmap = image, contentDescription = null, contentScale = ContentScale.FillBounds,
                colorFilter = if (dark) DARK_FILTER else null, modifier = Modifier.fillMaxSize()
            )
        } else if (failed) {
            Text(stringResource(R.string.rd_err_render), color = Color.Gray)
        }
    }
}

/**
 * الصفحة تُرسم صورةً فلا يمكن تحديد نصها مباشرة؛ فنعرض نصها المستخرج في نافذة قابلة للتحديد
 * (اضغط مطولاً على كلمة ← مقابض التحديد ← نسخ) مع تمييز السطر الذي ضُغط عليه وأزرار نسخ سريعة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfTextSheet(sel: PdfSelection, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()
    var layout by remember(sel) { mutableStateOf<TextLayoutResult?>(null) }
    val hasLine = sel.hlStart in 0 until sel.hlEnd && sel.hlEnd <= sel.text.length
    val annotated = remember(sel) {
        buildAnnotatedString {
            append(sel.text)
            if (hasLine) addStyle(SpanStyle(background = Color(0x66FFD600)), sel.hlStart, sel.hlEnd)
        }
    }
    LaunchedEffect(layout) {
        val l = layout ?: return@LaunchedEffect
        if (hasLine) scroll.scrollTo((l.getLineTop(l.getLineForOffset(sel.hlStart)).toInt() - 80).coerceAtLeast(0))
    }
    fun copy(text: String) {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("pdf", text))
        android.widget.Toast.makeText(ctx, ctx.getString(R.string.rd_copied), android.widget.Toast.LENGTH_SHORT).show()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = Dim.Gutter + 8.dp)) {
            Text(stringResource(R.string.rd_select_title, sel.page + 1), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
            if (sel.text.isBlank()) {
                Text(stringResource(R.string.rd_select_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.rd_select_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(scroll)) {
                    SelectionContainer {
                        Text(annotated, style = MaterialTheme.typography.bodyLarge, onTextLayout = { layout = it })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ copy(sel.text) }) { Text(stringResource(R.string.rd_copy_page)) }
                    if (hasLine) TextButton({ copy(sel.text.substring(sel.hlStart, sel.hlEnd).trim()) }) { Text(stringResource(R.string.rd_copy_line)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

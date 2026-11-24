package org.openfilemanager.app.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import org.openfilemanager.app.R
import org.openfilemanager.app.appContainer
import org.openfilemanager.app.ui.components.ActionSheet
import org.openfilemanager.app.ui.components.SheetAction
import org.openfilemanager.app.ui.openWithSystem

@Composable
// (BidiSpoofing) عزلات اتجاه LRI/PDI مقصودة: أرقام داخل نص RTL للعربية
@Suppress("BidiSpoofing")
fun DocxReaderScreen(path: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ctx.appContainer.readerStore }
    val file = remember(path) { java.io.File(path) }
    val systemDark = isSystemInDarkTheme()
    var attempt by remember { mutableStateOf(0) }
    val state = remember(path, attempt) { DocxReaderState(file, store, scope, ctx.applicationContext) }

    LaunchedEffect(state) { state.load(systemDark) }
    DisposableEffect(state) { onDispose { state.close() } }

    val dark = state.resolveDark(systemDark)
    LaunchedEffect(dark, state.contentReady) { state.applyTheme(dark) }

    var showToc by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var addBookmark by remember { mutableStateOf(false) }

    BackHandler(enabled = state.searchOpen) { state.closeSearch() }

    // External links open in another app only on an explicit tap, and only for web/mail schemes.
    DisposableEffect(state) {
        state.pendingLink = { url ->
            val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (uri != null && uri.scheme?.lowercase() in setOf("http", "https", "mailto")) {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        onDispose { state.pendingLink = null }
    }

    val ready = state.status == ReaderStatus.Ready && state.contentReady
    val toc = remember(state.headings) { buildToc(state.headings) }

    ReaderScaffold(
        title = file.name,
        onBack = { if (state.searchOpen) state.closeSearch() else onBack() },
        onSearch = { if (ready) { if (state.searchOpen) state.closeSearch() else state.openSearch() } },
        onToc = if (ready && toc.isNotEmpty()) ({ showToc = true }) else null,
        onMore = { showMenu = true },
        searchStrip = if (ready && state.searchOpen) ({
            val msg: String? = when {
                state.query.isNotBlank() && state.matchCount == 0 -> stringResource(R.string.rd_search_none)
                state.matchesTruncated -> stringResource(R.string.rd_search_limit, 3000)
                else -> null
            }
            ReaderSearchStrip(
                query = state.query, onQuery = state::setQueryText,
                countLabel = if (state.matchCount == 0) "" else "⁦${state.matchIndex + 1}/${state.matchCount}⁩",
                message = msg, hasResults = state.matchCount > 0,
                onPrev = state::prevMatch, onNext = state::nextMatch, onClose = state::closeSearch
            )
        }) else null,
        bottomBar = {
            if (ready) {
                val pct = (state.progress * 100).roundToInt()
                val label = "⁦" + stringResource(R.string.rd_progress, pct) + "⁩" +
                    (if (state.currentHeading.isNotBlank()) "  ·  " + state.currentHeading else "")
                ReaderBottomBar(
                    locationLabel = label,
                    goToLabel = stringResource(R.string.rd_go_to_position),
                    progress = state.progress,
                    bookmarked = state.bookmarkHere() != null,
                    onGoTo = { showGoTo = true },
                    onToggleBookmark = { state.bookmarkHere()?.let(state::removeBookmark) ?: run { addBookmark = true } },
                    onBookmarks = { showBookmarks = true },
                    onSettings = { showSettings = true }
                )
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.background)) {
            when (val s = state.status) {
                ReaderStatus.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ReaderStatus.Error -> ReaderErrorState(
                    stringResource(s.message),
                    onOpenExternal = if (s.canOpenExternally) ({ openWithSystem(ctx, path) }) else null,
                    onRetry = if (s.canRetry) ({ attempt++ }) else null, onBack = onBack
                )
                ReaderStatus.Ready -> {
                    DocxWebView(state, dark)
                    if (!state.contentReady) CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
    }

    if (ready) {
        if (showToc) TableOfContentsSheet(
            entries = toc, onPick = { state.scrollToHeading(it.target) }, onDismiss = { showToc = false }
        )
        if (showBookmarks) BookmarksSheet(
            bookmarks = state.bookmarks,
            subtitle = { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.created)) },
            onPick = state::jumpTo, onRename = state::renameBookmark, onDelete = state::removeBookmark,
            onDismiss = { showBookmarks = false }
        )
        if (showGoTo) NumberDialog(
            title = stringResource(R.string.rd_go_to_position), hint = stringResource(R.string.rd_position_hint),
            invalidMessage = stringResource(R.string.rd_position_invalid),
            initial = (state.progress * 100).roundToInt(), min = 0, max = 100,
            onConfirm = { state.scrollToPercent(it); showGoTo = false }, onDismiss = { showGoTo = false }
        )
        if (addBookmark) TextInputDialog(
            title = stringResource(R.string.rd_bookmark_add), label = stringResource(R.string.rd_bookmark_name),
            initial = state.suggestedTitle(), confirmLabel = stringResource(R.string.ui_save),
            onConfirm = { state.addBookmark(it); addBookmark = false }, onDismiss = { addBookmark = false }
        )
        if (showSettings) ReaderSettingsSheet({ showSettings = false }) {
            Text(stringResource(R.string.rd_theme), style = MaterialTheme.typography.labelLarge)
            ThemeChips(state.prefs.theme, state::setTheme)
            Text(stringResource(R.string.rd_text_size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ state.setFontSize(state.prefs.docxFontPx - 1) }) { Icon(Icons.Outlined.Remove, stringResource(R.string.rd_zoom_out)) }
                Slider(
                    value = state.prefs.docxFontPx.toFloat(), onValueChange = { state.setFontSize(it.roundToInt()) },
                    valueRange = ReaderPrefs.MIN_DOCX_FONT.toFloat()..ReaderPrefs.MAX_DOCX_FONT.toFloat(),
                    steps = ReaderPrefs.MAX_DOCX_FONT - ReaderPrefs.MIN_DOCX_FONT - 1, modifier = Modifier.weight(1f)
                )
                IconButton({ state.setFontSize(state.prefs.docxFontPx + 1) }) { Icon(Icons.Outlined.Add, stringResource(R.string.rd_zoom_in)) }
                Text("⁦${state.prefs.docxFontPx}⁩", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
            }
            Text(
                stringResource(R.string.rd_docx_note), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
    if (showMenu) ActionSheet(
        org.openfilemanager.app.BidiText.wrap(file.name),
        listOfNotNull(
            if (ready) SheetAction(Icons.Outlined.Bookmarks, stringResource(R.string.rd_bookmarks)) { showBookmarks = true } else null,
            if (ready) SheetAction(Icons.Outlined.Tune, stringResource(R.string.rd_reader_settings)) { showSettings = true } else null,
            SheetAction(Icons.Outlined.OpenInNew, stringResource(R.string.ui_open_with)) { openWithSystem(ctx, path) }
        )
    ) { showMenu = false }
}

/** Nested hierarchy from heading levels: a heading is a child of the closest earlier heading with a smaller level. */
private fun buildToc(headings: List<DocxHeading>): List<TocEntry> {
    val stack = ArrayList<Int>()
    return headings.map { h ->
        while (stack.isNotEmpty() && stack.last() >= h.level) stack.removeAt(stack.size - 1)
        val depth = stack.size
        stack.add(h.level)
        TocEntry(h.text, depth, h.id)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DocxWebView(state: DocxReaderState, dark: Boolean) {
    val bg = if (dark) AndroidColor.BLACK else AndroidColor.WHITE
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> createWebView(ctx, state, bg).also { state.webView = it; it.loadUrl(state.startUrl) } },
        update = { it.setBackgroundColor(bg) },
        onRelease = { wv ->
            if (state.webView === wv) state.webView = null
            wv.stopLoading()
            wv.removeJavascriptInterface("OfmBridge")
            wv.destroy()
        }
    )
}

/**
 * A WebView that can show exactly one thing: our bundled reader page over the open document.
 * No file/content access, no network, no storage, no navigation; JS is on only because the converter needs it.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(ctx: Context, state: DocxReaderState, bg: Int): WebView = WebView(ctx).apply {
    setBackgroundColor(bg)
    overScrollMode = View.OVER_SCROLL_NEVER
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = false
        databaseEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
        blockNetworkLoads = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_NO_CACHE
        setSupportZoom(false)
        builtInZoomControls = false
        setGeolocationEnabled(false)
        textZoom = 100 // the reader's own font-size control is the single source of truth
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
    }
    addJavascriptInterface(state.Bridge(), "OfmBridge")
    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = state.serve(request)

        // Never navigate: links are handled by the page script and forwarded to the app.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) state.onWebError()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            state.onWebError()
            return true
        }
    }
}

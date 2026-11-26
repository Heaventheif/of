package org.openfilemanager.app.reader

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openfilemanager.app.ArchiveRejections
import org.openfilemanager.app.FeatureFlags
import org.openfilemanager.app.R
import org.openfilemanager.app.appContainer
import org.openfilemanager.app.AppDispatchers
import org.openfilemanager.app.ui.components.ActionSheet
import org.openfilemanager.app.ui.components.SheetAction
import org.openfilemanager.app.ui.openWithSystem
import org.openfilemanager.app.ui.theme.Dim
import org.openfilemanager.core.pptx.PptxException
import org.openfilemanager.core.pptx.PptxReader
import org.openfilemanager.core.pptx.Slide
import org.openfilemanager.core.pptx.SlideBlock

/**
 * قارئ PPTX المضمّن: يعرض الشرائح نصاً وجداول بترتيبها مع ملاحظات المحاضر.
 * عرض Compose خالص (بلا WebView ولا فك ضغط للصور) فالكلفة صغيرة حتى مع ملف كبير.
 */
@Composable
fun PptxReaderScreen(path: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(path) { java.io.File(path) }
    var attempt by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<ReaderStatus>(ReaderStatus.Loading) }
    var deck by remember { mutableStateOf<org.openfilemanager.core.pptx.Presentation?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    val list = rememberLazyListState()
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchIdx by remember { mutableIntStateOf(0) }
    var showToc by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(path, attempt) {
        status = ReaderStatus.Loading
        deck = null
        val exists = withContext(AppDispatchers.io) { file.exists() && file.canRead() }
        if (!exists) { status = ReaderStatus.Error(R.string.rd_err_missing, false); return@LaunchedEffect }
        scale = ctx.appContainer.readerStore.prefsNow().pptxFontPx / DEFAULT_FONT_PX
        withContext(AppDispatchers.io) { runCatching { PptxReader.parse(file) } }
            .onSuccess { deck = it; status = ReaderStatus.Ready }
            .onFailure { e ->
                status = ReaderStatus.Error(PptxLoad.messageFor(e), true)
            }
    }

    val slides = deck?.slides.orEmpty()
    val current by remember(deck) { derivedStateOf { (list.firstVisibleItemIndex - 1).coerceIn(0, (slides.size - 1).coerceAtLeast(0)) } }
    val matches = remember(deck, query) { deck?.find(query).orEmpty() }
    LaunchedEffect(matches) { matchIdx = 0 }

    fun goTo(slideIndex: Int) {
        if (slideIndex in slides.indices) scope.launch { list.animateScrollToItem(slideIndex + 1) }
    }
    fun step(delta: Int) {
        if (matches.isEmpty()) return
        matchIdx = (matchIdx + delta).let { if (it < 0) matches.size - 1 else it % matches.size }
        goTo(matches[matchIdx])
    }

    val ready = status == ReaderStatus.Ready && slides.isNotEmpty()
    val toc = remember(deck) {
        slides.mapIndexed { i, s ->
            TocEntry(s.title.ifBlank { ctx.getString(R.string.rd_slide_number, s.number) }, 0, i.toString())
        }
    }

    ReaderScaffold(
        title = file.name,
        onBack = { if (searchOpen) searchOpen = false else onBack() },
        onSearch = { if (ready) searchOpen = !searchOpen },
        onToc = if (ready) ({ showToc = true }) else null,
        onMore = { showMenu = true },
        searchStrip = if (ready && searchOpen) ({
            ReaderSearchStrip(
                query = query, onQuery = { query = it },
                countLabel = if (matches.isEmpty()) "" else "${matchIdx + 1}/${matches.size}",
                message = if (query.isNotBlank() && matches.isEmpty()) stringResource(R.string.rd_search_none) else null,
                hasResults = matches.isNotEmpty(),
                onPrev = { step(-1) }, onNext = { step(1) }, onClose = { searchOpen = false; query = "" }
            )
        }) else null,
        bottomBar = {
            if (ready) Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { (current + 1f) / slides.size }, modifier = Modifier.fillMaxWidth().height(3.dp)
                    )
                    Text(
                        stringResource(R.string.rd_slide_of, current + 1, slides.size),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = Dim.Gutter, vertical = 8.dp)
                    )
                }
            }
        }
    ) { pad ->
        when (val s = status) {
            ReaderStatus.Loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ReaderStatus.Error -> Box(Modifier.fillMaxSize().padding(pad)) {
                ReaderErrorState(
                    stringResource(s.message),
                    onOpenExternal = if (s.canOpenExternally) ({ openWithSystem(ctx, path) }) else null,
                    onRetry = null, onBack = onBack
                )
            }
            ReaderStatus.Ready -> LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Dim.Gutter, end = Dim.Gutter, top = pad.calculateTopPadding() + 8.dp,
                    bottom = pad.calculateBottomPadding() + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "note") {
                    Text(
                        stringResource(R.string.rd_pptx_note), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                itemsIndexed(slides, key = { _, s -> "s${s.number}" }) { i, slide ->
                    SlideCard(slide, i, slides.size, scale, onClick = { goTo(i) })
                }
            }
        }
    }

    if (showToc) TableOfContentsSheet(
        entries = toc, onPick = { runCatching { goTo(it.target.toInt()) } }, onDismiss = { showToc = false }
    )
    if (showMenu) ActionSheet(
        org.openfilemanager.app.BidiText.wrap(file.name),
        listOf(SheetAction(Icons.Outlined.OpenInNew, stringResource(R.string.ui_open_with)) { openWithSystem(ctx, path) })
    ) { showMenu = false }
}

/** رسالة الخطأ المناسبة لسبب تعذّر فتح العرض. */
internal object PptxLoad {
    fun messageFor(e: Throwable): Int {
        if (FeatureFlags.archiveRejectionFeedback) {
            ArchiveRejections.reasonOf(e)?.let { return ArchiveRejections.messageRes(it) }
        }
        return when ((e as? PptxException)?.reason) {
            PptxException.Reason.ENCRYPTED -> R.string.rd_err_encrypted_pptx
            PptxException.Reason.TOO_LARGE -> R.string.rd_err_too_large
            PptxException.Reason.EMPTY -> R.string.rd_err_empty
            PptxException.Reason.NOT_OOXML -> R.string.rd_err_not_pptx
            else -> R.string.rd_err_corrupt_pptx
        }
    }
}

private const val DEFAULT_FONT_PX = 18f

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlideCard(
    slide: Slide, index: Int, count: Int, scale: Float, onClick: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val copied = stringResource(R.string.rd_copied)
    val hiddenLabel = stringResource(R.string.rd_pptx_hidden)
    val notesLabel = stringResource(R.string.rd_pptx_notes)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                // ضغطة مطوّلة: نسخ كل نص الشريحة
                onLongClick = { clipboard.setText(AnnotatedString(slide.searchText.trim())); Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show() }
            )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        slide.number.toString(), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    slide.title.ifBlank { stringResource(R.string.rd_slide_number, slide.number) },
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = (18 * scale).sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (slide.hidden) Text(
                    hiddenLabel, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            if (slide.blocks.isEmpty() && slide.notes.isEmpty()) {
                Text(
                    stringResource(R.string.rd_pptx_no_text), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                )
            }
            for (block in slide.blocks) when (block) {
                is SlideBlock.Text -> Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (p in block.paragraphs) ParagraphRow(p.text, p.level, p.bullet, scale)
                }
                is SlideBlock.Table -> SlideTable(block, scale)
            }
            if (slide.notes.isNotBlank()) Column(Modifier.padding(top = 12.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    notesLabel, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
                Text(
                    slide.notes, style = MaterialTheme.typography.bodyMedium, fontSize = (13 * scale).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ParagraphRow(text: String, level: Int, bullet: Boolean, scale: Float) {
    Row(Modifier.fillMaxWidth().padding(start = (level * 16).dp)) {
        if (bullet) Text(
            if (level % 2 == 0) "•" else "–", fontSize = (15 * scale).sp,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp)
        )
        Text(text, style = MaterialTheme.typography.bodyLarge, fontSize = (15 * scale).sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SlideTable(table: SlideBlock.Table, scale: Float) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .padding(top = 10.dp)
            .horizontalScroll(rememberScrollState())
            .border(1.dp, outline, RoundedCornerShape(8.dp))
    ) {
        for ((r, row) in table.rows.withIndex()) Column {
            for ((c, cell) in row.withIndex()) Box(
                Modifier
                    .width(112.dp)
                    .background(if (r == 0) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                    .border(0.5.dp, outline)
                    .padding(8.dp)
            ) {
                Text(
                    cell, style = MaterialTheme.typography.bodyMedium, fontSize = (13 * scale).sp,
                    fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

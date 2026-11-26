package org.openfilemanager.app.ui.screens

import org.openfilemanager.app.ui.components.show
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openfilemanager.app.BidiText
import org.openfilemanager.app.FeatureFlags
import org.openfilemanager.app.R
import org.openfilemanager.app.appContainer
import org.openfilemanager.app.editor.FontNote
import org.openfilemanager.app.editor.ReadFailure
import org.openfilemanager.app.editor.SaveOutcome
import org.openfilemanager.app.editor.TextEditorState
import org.openfilemanager.app.ui.LocalSnack
import org.openfilemanager.app.ui.theme.Dim

private const val MIN_SIZE = 11
private const val MAX_SIZE = 28

/** Editor look, remembered between sessions (plain SharedPreferences: it is a tiny UI-only preference). */
@Stable
private class EditorStyle(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("editor_style", Context.MODE_PRIVATE)
    var lineNumbers by mutableStateOf(prefs.getBoolean("line_numbers", true))
    /** "default" | "serif" | "sans" | "mono" | "file:<name>" (an imported font in filesDir/fonts). */
    var font by mutableStateOf(prefs.getString("font", null) ?: if (prefs.getBoolean("mono", false)) "mono" else "default")
    var size by mutableStateOf(prefs.getInt("size", 16).coerceIn(MIN_SIZE, MAX_SIZE))
    var weight by mutableStateOf(prefs.getInt("weight", 1).coerceIn(0, WEIGHTS.lastIndex))
    var bg by mutableStateOf(prefs.getInt("bg", 0).coerceIn(0, BG_PRESETS.lastIndex))
    var fg by mutableStateOf(prefs.getInt("fg", 0).coerceIn(0, TEXT_PRESETS.lastIndex))

    fun save() {
        prefs.edit().putBoolean("line_numbers", lineNumbers).putString("font", font)
            .putInt("size", size).putInt("weight", weight).putInt("bg", bg).putInt("fg", fg).apply()
    }

    fun reset() {
        lineNumbers = true; font = "default"; size = 16; weight = 1; bg = 0; fg = 0
        save()
    }
}

/** Index 0 = follow the app theme; the rest are fixed presets. */
private val BG_PRESETS: List<Color?> = listOf(
    null, Color(0xFF1E1E1E), Color(0xFF0D1B2A), Color(0xFF14261A),
    Color(0xFF2B2118), Color(0xFFFFFFFF), Color(0xFFF5ECD9), Color(0xFFE8EAED)
)
private val TEXT_PRESETS: List<Color?> = listOf(
    null, Color(0xFFFFFFFF), Color(0xFFD4D4D4), Color(0xFF00E476),
    Color(0xFFFFC857), Color(0xFF7FDBFF), Color(0xFF111111), Color(0xFF3B2F1E)
)
private val WEIGHTS = listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.Bold)
private val WEIGHT_VALUES = listOf(300, 400, 500, 700)

/** Full-screen text editor (replaces the old dialog). Saves atomically and refuses if the file changed underneath. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(path: String, readOnly: Boolean, onBack: () -> Unit, onSaved: () -> Unit = {}) {
    val file = remember(path) { File(path) }
    val snack = LocalSnack.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val style = remember { EditorStyle(ctx) }
    if (FeatureFlags.perReaderPrefs) {
        var sizeLoaded by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            runCatching { style.size = ctx.appContainer.readerStore.prefsNow().textEditorFontPx }
            sizeLoaded = true
        }
        LaunchedEffect(style.size) {
            // قبل اكتمال التحميل لا شيء يُكتب (تثبيت جديد لا يكتب المفتاح بمجرد فتح المحرر)،
            // وبعده كل تغييرة فعليّة تُكتب في مفتاح المحرر الخاص فقط.
            if (!sizeLoaded) return@LaunchedEffect
            delay(500)
            runCatching { ctx.appContainer.readerStore.setTextEditorFont(style.size) }
        }
    }
    var showStyle by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val editor = remember {
        TextEditorState(
            readOnly = readOnly,
            drafts = org.openfilemanager.core.DraftStore(File(ctx.filesDir, "drafts").toPath()),
            fontsDir = File(ctx.filesDir, "fonts"),
        ).also { state ->
            runCatching { state.pruneDrafts() }
            state.refreshImportedFonts()
        }
    }
    SideEffect { editor.readOnly = readOnly }
    val loading by editor.loading.collectAsState()
    val failed by editor.failed.collectAsState()
    val text by editor.text.collectAsState()
    val dirty by editor.dirty.collectAsState()
    val searching by editor.searching.collectAsState()
    val query by editor.query.collectAsState()
    val matches by editor.matches.collectAsState()
    val cur by editor.cur.collectAsState()
    val navTick by editor.navTick.collectAsState()
    val searchNav by editor.searchNav.collectAsState()
    // The field keeps its own selection/composition (IME) state; `text` is just its string for the rest of the screen.
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var askDiscard by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val searchFocus = remember { FocusRequester() }
    var viewportH by remember { mutableIntStateOf(0) }
    val layoutNow by rememberUpdatedState(layout)
    // الانتقال بالتمرير إلى التطابق الحالي. المفاتيح بلا `layout`: إعادة القياس بسبب التظليل
    // لم تعد تُلغي الحركة وتعيد تشغيلها (تقطيع/ارتداد)؛ ننتظر فقط تخطيطاً يطابق النص الحالي.
    LaunchedEffect(cur, navTick) {
        if (navTick == 0) return@LaunchedEffect
        val at = matches.getOrNull(cur) ?: return@LaunchedEffect
        val l = snapshotFlow { layoutNow?.takeIf { it.layoutInput.text.text == text } }.filterNotNull().first()
        if (at > l.layoutInput.text.length) return@LaunchedEffect
        val top = l.getLineTop(l.getLineForOffset(at))
        val target = editor.matchScrollTarget(top, viewportH)
        // (تصحيح التمرير) قفزة فورية للتطابقات البعيدة كي لا يعبر التحريك المتحرك
        // الملف كله ببطء وبتقطّع؛ وتحريك سلس للتطابقات المتقاربة فقط.
        if (editor.isFarJump(target, scroll.value)) scroll.scrollTo(target)
        else scroll.animateScrollTo(target)
    }

    LaunchedEffect(path) {
        val r = editor.load(path)
        if (r.error == null) {
            field = androidx.compose.ui.text.input.TextFieldValue(r.textToOpen)
            // مسودة غير محفوظة من جلسة انقطعت: تُستعاد فوق الأصل ويبقى «غير محفوظ» حتى يضغط المستخدم حفظ.
            if (r.restoredDraft) snack.show(ctx.getString(R.string.ui_draft_restored), org.openfilemanager.app.ui.components.SnackKind.INFO)
        }
    }

    fun save() {
        scope.launch {
            val outcome = editor.save(path)
            if (outcome == SaveOutcome.Saved) onSaved()
            val msg = when (outcome) {
                SaveOutcome.Saved -> R.string.ui_saved
                SaveOutcome.Encoding -> R.string.ui_err_encoding
                SaveOutcome.TooLarge -> R.string.ui_err_too_large
                SaveOutcome.Changed -> R.string.ui_err_changed
                SaveOutcome.Error -> R.string.ui_err_save
            }
            snack.show(ctx.getString(msg), if (outcome == SaveOutcome.Saved) org.openfilemanager.app.ui.components.SnackKind.SUCCESS else org.openfilemanager.app.ui.components.SnackKind.ERROR)
        }
    }

    // حفظ تلقائي للمسودة بعد توقف الكتابة (المهلة داخل editor.autosaveDraft بمُوفِّت محقون)؛
    // تُحذف عند الحفظ أو التجاهل. المفتاح والشرط كما كانا حرفياً.
    LaunchedEffect(text, dirty, loading) {
        if (!editor.shouldAutosaveDraft()) return@LaunchedEffect
        editor.autosaveDraft(path)
    }

    BackHandler { if (dirty) askDiscard = true else onBack() }

    val bgColor = BG_PRESETS[style.bg] ?: MaterialTheme.colorScheme.background
    val fgColor = TEXT_PRESETS[style.fg] ?: MaterialTheme.colorScheme.onSurface
    val fontWeight = WEIGHTS[style.weight]
    val fontFamily = remember(style.font, style.weight) { resolveFamily(ctx, style.font, WEIGHT_VALUES[style.weight]) }
    val lineHeight = (style.size * 1.5f).sp

    Scaffold(
        containerColor = bgColor,
        snackbarHost = { org.openfilemanager.app.ui.components.OfmSnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(BidiText.wrap(file.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton({ if (dirty) askDiscard = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_back))
                    }
                },
                actions = {
                    IconButton({ editor.toggleSearch() }) {
                        Icon(Icons.Outlined.Search, stringResource(R.string.ui_search))
                    }
                    IconButton({ showStyle = true }) { Icon(Icons.Outlined.FormatSize, stringResource(R.string.ui_editor_style)) }
                    if (!readOnly) IconButton(::save, enabled = dirty) {
                        Icon(Icons.Outlined.Save, stringResource(R.string.ui_save))
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize().imePadding()) {
        if (searching) {
            LaunchedEffect(Unit) { searchFocus.requestFocus() }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dim.Gutter, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { editor.onQueryChanged(it) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.ui_search_in_file)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { editor.step(1) }),
                    modifier = Modifier.weight(1f).focusRequester(searchFocus)
                )
                Text(
                    if (query.isEmpty()) "" else if (matches.isEmpty()) "0" else "\u2066${cur + 1}/${matches.size}\u2069",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
                IconButton({ editor.step(-1) }, enabled = matches.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.ui_prev_match)) }
                IconButton({ editor.step(1) }, enabled = matches.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.ui_next_match)) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                failed != null -> Text(
                    stringResource(readFailureRes(failed)), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp)
                )
                else -> {
                    val codeStyle = LocalTextStyle.current.copy(
                        color = fgColor, textDirection = TextDirection.Content,
                        fontSize = style.size.sp, lineHeight = lineHeight,
                        fontFamily = fontFamily, fontWeight = fontWeight
                    )
                    val matchBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                    val currentBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.70f)
                    val highlight = remember(matches, cur, query, matchBg, currentBg) {
                        if (matches.isEmpty()) VisualTransformation.None
                        else VisualTransformation { t ->
                            val out = buildAnnotatedString {
                                append(t)
                                matches.forEachIndexed { i, at ->
                                    addStyle(SpanStyle(background = if (i == cur) currentBg else matchBg), at, (at + query.length).coerceAtMost(t.length))
                                }
                            }
                            TransformedText(out, OffsetMapping.Identity)
                        }
                    }
                    // One number per logical line; wrapped continuation lines get a blank slot so everything stays aligned.
                    val numbers = remember(layout, style.lineNumbers) {
                        val l = layout
                        if (!style.lineNumbers || l == null) ""
                        else {
                            val src = l.layoutInput.text.text
                            val sb = StringBuilder()
                            var n = 0
                            for (i in 0 until l.lineCount) {
                                val start = l.getLineStart(i)
                                if (i == 0 || (start > 0 && src[start - 1] == '\n')) { n++; sb.append(n) }
                                if (i < l.lineCount - 1) sb.append('\n')
                            }
                            sb.toString()
                        }
                    }
                    // Code reads left-to-right with the gutter on the left; Arabic lines still align by their own direction.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                      BoxWithConstraints(Modifier.fillMaxSize()) {
                        val viewport = maxHeight
                        // Keep the caret on screen: scroll down as the text grows past the visible area (above the
                        // keyboard) and back up when lines are deleted.
                        val viewportPx = with(LocalDensity.current) { viewport.roundToPx() }
                        SideEffect { viewportH = viewportPx }
                        val gutterPx = with(LocalDensity.current) { Dim.Gutter.roundToPx() }
                        LaunchedEffect(field.selection.end, layout, viewportPx) {
                            val l = layout ?: return@LaunchedEffect
                            if (readOnly || searchNav) return@LaunchedEffect
                            val end = field.selection.end.coerceIn(0, l.layoutInput.text.length)
                            val r = l.getCursorRect(end)
                            val top = gutterPx + r.top
                            val bottom = gutterPx + r.bottom
                            val margin = r.bottom - r.top
                            val v = scroll.value
                            if (bottom + margin > v + viewportPx) scroll.animateScrollTo((bottom + margin - viewportPx).toInt())
                            else if (top - margin < v) scroll.animateScrollTo((top - margin).toInt().coerceAtLeast(0))
                        }
                        // The editing surface is at least one screen tall and tapping anywhere on it focuses the text.
                        Row(
                            Modifier.fillMaxSize().verticalScroll(scroll).heightIn(min = viewport)
                                .then(if (readOnly) Modifier else Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() })
                                .padding(vertical = Dim.Gutter)
                        ) {
                            if (style.lineNumbers) {
                                Text(
                                    numbers, style = codeStyle.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.End),
                                    color = fgColor.copy(alpha = 0.45f), softWrap = false,
                                    modifier = Modifier.padding(start = 2.dp, end = 4.dp)
                                )
                            }
                            BasicTextField(
                                value = field,
                                onValueChange = {
                                    if (it.text != field.text || it.selection != field.selection) editor.clearSearchNav()
                                    field = it
                                    editor.onEditText(it.text)
                                },
                                readOnly = readOnly,
                                textStyle = codeStyle,
                                cursorBrush = SolidColor(if (TEXT_PRESETS[style.fg] == null) MaterialTheme.colorScheme.primary else fgColor),
                                onTextLayout = { layout = it },
                                visualTransformation = highlight,
                                modifier = Modifier.weight(1f).focusRequester(focus).padding(start = 6.dp, end = Dim.Gutter)
                            )
                        }
                      }
                    }
                }
            }
        }
        }
    }

    if (showStyle) StyleSheet(style, editor) { showStyle = false }

    if (askDiscard) AlertDialog(
        onDismissRequest = { askDiscard = false },
        title = { Text(stringResource(R.string.ui_discard_title)) },
        text = { Text(stringResource(R.string.ui_discard_body)) },
        confirmButton = { TextButton({
            askDiscard = false
            scope.launch { editor.clearDraft(path); onBack() }
        }) { Text(stringResource(R.string.ui_discard), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ askDiscard = false }) { Text(stringResource(R.string.ui_keep_editing)) } }
    )
}

/** ترجمة فشل الفتح إلى مورد النص (كانت الحالة Int? للمورد سلفاً). */
private fun readFailureRes(f: ReadFailure?): Int = when (f) {
    ReadFailure.TooLarge -> R.string.ui_err_too_large
    ReadFailure.ReadError, null -> R.string.ui_err_read_file
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StyleSheet(style: EditorStyle, editor: TextEditorState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = { style.save(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Dim.Gutter + 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.ui_editor_style), style = MaterialTheme.typography.titleMedium)
            SwitchRow(stringResource(R.string.ui_line_numbers), style.lineNumbers) { style.lineNumbers = it }
            FontPicker(style, editor)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ui_font_size), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                IconButton({ style.size = (style.size - 1).coerceAtLeast(MIN_SIZE) }, enabled = style.size > MIN_SIZE) {
                    Icon(Icons.Outlined.Remove, stringResource(R.string.ui_font_size) + " −")
                }
                Text("${style.size}", Modifier.width(32.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                IconButton({ style.size = (style.size + 1).coerceAtMost(MAX_SIZE) }, enabled = style.size < MAX_SIZE) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.ui_font_size) + " +")
                }
            }

            Text(stringResource(R.string.ui_font_weight), style = MaterialTheme.typography.bodyLarge)
            val labels = listOf(R.string.ui_weight_light, R.string.ui_weight_normal, R.string.ui_weight_medium, R.string.ui_weight_bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, label ->
                    FilterChip(selected = style.weight == i, onClick = { style.weight = i }, label = { Text(stringResource(label)) })
                }
            }

            Text(stringResource(R.string.ui_bg_color), style = MaterialTheme.typography.bodyLarge)
            Swatches(BG_PRESETS, style.bg, MaterialTheme.colorScheme.background) { style.bg = it }

            Text(stringResource(R.string.ui_text_color), style = MaterialTheme.typography.bodyLarge)
            Swatches(TEXT_PRESETS, style.fg, MaterialTheme.colorScheme.onSurface) { style.fg = it }

            TextButton({ style.reset() }) { Text(stringResource(R.string.ui_reset_style)) }
            Spacer(Modifier.size(12.dp))
        }
    }
}

private fun fontsDir(ctx: Context) = File(ctx.filesDir, "fonts")

/** Built-in families, or an imported font file. Imported fonts get the chosen weight through Typeface.create. */
private fun resolveFamily(ctx: Context, key: String, weight: Int): FontFamily = when {
    key == "serif" -> FontFamily.Serif
    key == "sans" -> FontFamily.SansSerif
    key == "mono" -> FontFamily.Monospace
    key.startsWith("file:") -> runCatching {
        val base = Typeface.Builder(File(fontsDir(ctx), key.removePrefix("file:"))).build() ?: error("bad font")
        FontFamily(Typeface.create(base, weight, false))
    }.getOrDefault(FontFamily.Default)
    else -> FontFamily.Default
}

/**
 * أجزاء الاستيراد المحتكّة بأندرويد (ContentResolver): اسم العميل وفتح دفق القراءة —
 * على IO كما كان importFont السلف. القرار والحالة في TextEditorState.importFont.
 */
private suspend fun fontDisplayAndOpener(ctx: Context, uri: Uri): Pair<String?, (() -> InputStream?)?> =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolver = ctx.contentResolver
            val display = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            display to { resolver.openInputStream(uri) }
        }.getOrNull() ?: (null to null)
    }

/** ترجمة ملاحظة استيراد الخط إلى مورد النص (كانت Int? للمورد سلفاً). */
private fun fontNoteRes(n: FontNote?): Int = when (n) {
    FontNote.Imported -> R.string.ui_font_imported
    FontNote.Error -> R.string.ui_err_font
    null -> R.string.ui_font_hint
}

@Composable
private fun FontPicker(style: EditorStyle, editor: TextEditorState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val imported by editor.importedFonts.collectAsState()
    val note by editor.fontNote.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val (display, opener) = fontDisplayAndOpener(ctx, uri)
            // تحقق أندرويد الحقيقي أن الخط يُحمَّل (كانت L554) — يمرَّر محقوناً إلى آلة الحالة.
            val canLoad: (File) -> Boolean = { f -> runCatching { Typeface.Builder(f).build() }.getOrNull() != null }
            val name = editor.importFont(display, opener, canLoad)
            if (name == null) editor.onFontImportFailed()
            else {
                editor.onFontImported()
                style.font = "file:$name"
            }
        }
    }
    Text(stringResource(R.string.ui_font_family), style = MaterialTheme.typography.bodyLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "default" to R.string.ui_font_default, "sans" to R.string.ui_font_sans,
            "serif" to R.string.ui_font_serif, "mono" to R.string.ui_font_mono
        ).forEach { (key, label) ->
            FilterChip(selected = style.font == key, onClick = { style.font = key }, label = { Text(stringResource(label)) })
        }
        imported.forEach { name ->
            val key = "file:$name"
            FilterChip(
                selected = style.font == key, onClick = { style.font = key },
                label = { Text(name.substringBeforeLast('.'), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp)) },
                trailingIcon = {
                    Icon(
                        Icons.Outlined.Close, null,
                        modifier = Modifier.size(18.dp).clip(CircleShape).clickable {
                            editor.deleteImportedFont(name)
                            if (style.font == key) style.font = "default"
                        }
                    )
                }
            )
        }
    }
    TextButton({ editor.onFontPickRequested(); picker.launch(arrayOf("*/*")) }) {
        Icon(Icons.Outlined.FileOpen, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.ui_import_font))
    }
    Text(
        stringResource(fontNoteRes(note)), style = MaterialTheme.typography.bodyMedium,
        color = if (note == FontNote.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}

/** Color circles with a theme outline; the first one is the app-theme default. */
@Composable
private fun Swatches(colors: List<Color?>, selected: Int, themeColor: Color, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEachIndexed { i, c ->
            val fill = c ?: themeColor
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(fill)
                    .border(if (i == selected) 2.dp else 1.dp, if (i == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onPick(i) },
                contentAlignment = Alignment.Center
            ) {
                if (i == selected) {
                    val luminance = fill.red * 0.299f + fill.green * 0.587f + fill.blue * 0.114f
                    Icon(Icons.Filled.Check, null, tint = if (luminance > 0.55f) Color.Black else Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

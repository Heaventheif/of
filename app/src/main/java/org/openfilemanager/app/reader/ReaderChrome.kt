package org.openfilemanager.app.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Toc
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openfilemanager.app.BidiText
import org.openfilemanager.app.R
import org.openfilemanager.app.ui.theme.Dim

/** One row of a table of contents; [level] 0 = top level. [target] is interpreted by the format-specific screen. */
data class TocEntry(val title: String, val level: Int, val target: String)

/** Shared reader layout: compact top bar, optional search strip, content, compact bottom bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScaffold(
    title: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onToc: (() -> Unit)?,
    onMore: () -> Unit,
    searchStrip: (@Composable () -> Unit)?,
    bottomBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(BidiText.wrap(title), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_back)) }
                    },
                    actions = {
                        IconButton(onSearch) { Icon(Icons.Outlined.Search, stringResource(R.string.ui_search)) }
                        if (onToc != null) IconButton(onToc) { Icon(Icons.Outlined.Toc, stringResource(R.string.rd_toc)) }
                        IconButton(onMore) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.ui_more)) }
                    }
                )
                if (searchStrip != null) searchStrip()
            }
        },
        bottomBar = bottomBar,
        content = content
    )
}

/** Search strip: field, "n / m", previous / next, close. [message] explains limits (scanned PDF, progress…). */
@Composable
fun ReaderSearchStrip(
    query: String,
    onQuery: (String) -> Unit,
    countLabel: String,
    message: String?,
    hasResults: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dim.Gutter, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    placeholder = { Text(stringResource(R.string.ui_search_in_file)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onNext() }),
                    modifier = Modifier.weight(1f).focusRequester(focus)
                )
                Text(
                    countLabel, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp)
                )
                IconButton(onPrev, enabled = hasResults) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.ui_prev_match)) }
                IconButton(onNext, enabled = hasResults) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.ui_next_match)) }
                IconButton(onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.ui_cancel)) }
            }
            if (message != null) {
                Text(
                    message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
                )
            }
        }
    }
}

/** Bottom reading bar: location label (tap = go to), thin progress, bookmark toggle, bookmarks list, settings. */
@Composable
fun ReaderBottomBar(
    locationLabel: String,
    goToLabel: String,
    progress: Float,
    bookmarked: Boolean,
    onGoTo: () -> Unit,
    onToggleBookmark: () -> Unit,
    onBookmarks: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding()) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height1(),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onGoTo, modifier = Modifier.heightIn(min = Dim.TouchTarget).weight(1f)) {
                    Text(locationLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                }
                IconButton(onToggleBookmark) {
                    Icon(
                        if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        stringResource(if (bookmarked) R.string.rd_bookmark_remove else R.string.rd_bookmark_add),
                        tint = if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onBookmarks) { Icon(Icons.Outlined.Bookmarks, stringResource(R.string.rd_bookmarks)) }
                IconButton(onSettings) { Icon(Icons.Outlined.Tune, stringResource(R.string.rd_reader_settings)) }
            }
        }
    }
}

private fun Modifier.height1(): Modifier = this.then(Modifier.heightIn(max = 2.dp))

/** Hierarchical table of contents; indentation follows [TocEntry.level]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableOfContentsSheet(entries: List<TocEntry>, onPick: (TocEntry) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.rd_toc), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Dim.Gutter + 8.dp, vertical = 8.dp)
            )
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.rd_toc_empty), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Dim.Gutter + 8.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(entries) { e ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Dim.TouchTarget + 4.dp)
                                .clickable { onDismiss(); onPick(e) }
                                .padding(start = Dim.Gutter + 8.dp + (e.level.coerceAtMost(6) * 16).dp, end = Dim.Gutter + 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                BidiText.wrap(e.title), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = if (e.level == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Saved bookmarks of the open document: jump, rename, delete. [subtitle] describes a bookmark's location. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<Bookmark>,
    subtitle: (Bookmark) -> String,
    onPick: (Bookmark) -> Unit,
    onRename: (Bookmark, String) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onDismiss: () -> Unit
) {
    var renaming by remember { mutableStateOf<Bookmark?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.rd_bookmarks), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Dim.Gutter + 8.dp, vertical = 8.dp)
            )
            if (bookmarks.isEmpty()) {
                Text(
                    stringResource(R.string.rd_bookmarks_empty), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Dim.Gutter + 8.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(bookmarks, key = { it.id }) { b ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Dim.SheetItem)
                                .clickable { onDismiss(); onPick(b) }
                                .padding(start = Dim.Gutter + 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(BidiText.wrap(b.title), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                Text(subtitle(b), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton({ renaming = b }) { Icon(Icons.Outlined.Edit, stringResource(R.string.rd_bookmark_rename)) }
                            IconButton({ onDelete(b) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.ui_delete), tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
    renaming?.let { b ->
        TextInputDialog(
            title = stringResource(R.string.rd_bookmark_rename), label = stringResource(R.string.rd_bookmark_name),
            initial = b.title, confirmLabel = stringResource(R.string.ui_save),
            onConfirm = { onRename(b, it); renaming = null }, onDismiss = { renaming = null }
        )
    }
}

/** Single-line text prompt (bookmark name). Confirm is disabled while the text is blank. */
@Composable
fun TextInputDialog(
    title: String, label: String, initial: String, confirmLabel: String,
    onConfirm: (String) -> Unit, onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it.take(120) }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton({ onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.ui_cancel)) } }
    )
}

/**
 * Numeric prompt shared by "go to page" and "go to position". Validates against [min]..[max] and shows
 * [invalidMessage] instead of closing, so a wrong number never silently does nothing.
 */
@Composable
fun NumberDialog(
    title: String, hint: String, invalidMessage: String, initial: Int, min: Int, max: Int,
    onConfirm: (Int) -> Unit, onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial.toString()) }
    var invalid by remember { mutableStateOf(false) }
    fun submit() {
        val n = text.trim().toIntOrNull()
        if (n == null || n < min || n > max) invalid = true else onConfirm(n)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.filter(Char::isDigit).take(7); invalid = false },
                label = { Text(hint) }, singleLine = true, isError = invalid,
                supportingText = if (invalid) ({ Text(invalidMessage) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton({ submit() }) { Text(stringResource(R.string.rd_go)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.ui_cancel)) } }
    )
}

/** Titled bottom sheet container for reading settings (theme, text size / zoom…). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = Dim.Gutter + 8.dp)) {
            Text(stringResource(R.string.rd_reader_settings), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
            content()
            Spacer(Modifier.size(16.dp))
        }
    }
}

/** Full-screen message with optional actions: used for every reader error state. */
@Composable
fun ReaderErrorState(message: String, onOpenExternal: (() -> Unit)?, onRetry: (() -> Unit)?, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.size(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onRetry != null) TextButton(onRetry) { Text(stringResource(R.string.rd_retry)) }
            if (onOpenExternal != null) TextButton(onOpenExternal) { Text(stringResource(R.string.ui_open_with)) }
            TextButton(onBack) { Text(stringResource(R.string.ui_back)) }
        }
    }
}

/** Theme chips (System / Light / Dark). */
@Composable
fun ThemeChips(selected: ReaderTheme, onSelect: (ReaderTheme) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            ReaderTheme.SYSTEM to R.string.rd_theme_system,
            ReaderTheme.LIGHT to R.string.rd_theme_light,
            ReaderTheme.DARK to R.string.rd_theme_dark
        ).forEach { (t, label) ->
            androidx.compose.material3.FilterChip(selected = selected == t, onClick = { onSelect(t) }, label = { Text(stringResource(label)) })
        }
    }
}

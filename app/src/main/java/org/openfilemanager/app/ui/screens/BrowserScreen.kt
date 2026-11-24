package org.openfilemanager.app.ui.screens

import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PushPin
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
// (تصحيح F13) اسم الحزمة الصحيح nestedscroll بحرف صغير: الحزمة nestedScroll لا وجود لها
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.Button
import androidx.compose.runtime.produceState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.openfilemanager.app.OpProgressInfo
import org.openfilemanager.app.appContainer
import org.openfilemanager.core.BatchRename
import org.openfilemanager.core.BatchRenameSpec
import org.openfilemanager.core.RenamePlan
import org.openfilemanager.core.RenameRow
import org.openfilemanager.core.RenameStatus
import org.openfilemanager.app.ui.BulkRenameContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import org.openfilemanager.app.MusicPlaybackService
import org.openfilemanager.app.R
import org.openfilemanager.app.ui.BrowserViewModel
import org.openfilemanager.app.ui.FileItem
import org.openfilemanager.app.ui.Location
import org.openfilemanager.app.ui.UiEvent
import org.openfilemanager.app.ui.components.ActionSheet
import org.openfilemanager.app.ui.components.BorderedIcon
import org.openfilemanager.app.ui.components.EmptyState
import org.openfilemanager.app.ui.components.FileGridCell
import org.openfilemanager.app.ui.components.FileRow
import org.openfilemanager.app.ui.components.SheetAction
import org.openfilemanager.app.ui.components.SortSheet
import org.openfilemanager.app.ui.installApk
import org.openfilemanager.app.ui.openWithSystem
import org.openfilemanager.app.ui.LocalSnack
import org.openfilemanager.app.BidiText
import org.openfilemanager.app.ui.mimeOf
import org.openfilemanager.app.ui.shareFiles
import org.openfilemanager.app.ui.theme.Dim
import org.openfilemanager.core.FileKind
import org.openfilemanager.core.FileKinds
import org.openfilemanager.core.SortFieldCore

private data class Crumb(val label: String, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    vm: BrowserViewModel,
    onLeave: () -> Unit,
    onViewImages: (paths: List<String>, index: Int) -> Unit,
    onEditText: (path: String) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenReader: (path: String) -> Unit
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snack = LocalSnack.current
    BackHandler { if (!vm.navigateUp()) onLeave() }

    var sheetItem by remember { mutableStateOf<FileItem?>(null) }
    val pinnedPaths by ctx.appContainer.pinned.paths.collectAsStateWithLifecycle(emptyList())
    var showSort by remember { mutableStateOf(false) }
    var showSelectionMore by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renameItem by remember { mutableStateOf<FileItem?>(null) }
    var openAsItem by remember { mutableStateOf<FileItem?>(null) }
    var compressName by remember { mutableStateOf<String?>(null) }
    var showBulkRename by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var newFile by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val shown = s.visibleItems
    val loc = s.location
    val archiveFile = (loc as? Location.Archive)?.file
    val rootLabel = stringResource(R.string.ui_internal_storage)
    val canCreate = !s.selecting && s.query == null && s.clipboard == null &&
        (loc is Location.Dir || (loc is Location.Archive && s.archiveEditable))

    fun playFrom(item: FileItem) {
        val tracks = shown.filter { it.kind == FileKind.AUDIO }.mapNotNull { it.path }
        // (F-03) مسارات خام للمشغل: FileProvider كان يحولها إلى content:// فتفشل
        // وسوم المقطع وملفات الكلمات الجانبية داخل الخدمة.
        MusicPlaybackService.start(ctx, tracks, tracks.indexOf(item.path).coerceAtLeast(0))
        onOpenPlayer()
    }

    fun openAs(item: FileItem, type: Int) {
        val p = item.path ?: return
        when (type) {
            0 -> onEditText(p)
            1 -> onViewImages(listOf(p), 0)
            2 -> { MusicPlaybackService.start(ctx, listOf(p), 0); onOpenPlayer() }
            3 -> openWithSystem(ctx, p, "video/*")
            else -> openWithSystem(ctx, p, "*/*")
        }
    }

    fun open(item: FileItem) {
        if (s.selecting) { vm.toggleSelect(item.id); return }
        val p = item.path
        when {
            item.isDir && item.isArchiveEntry -> item.entry?.let { vm.openInner(it) }
            item.isArchiveEntry -> vm.openArchiveEntry(item)
            p == null -> Unit // عنصر بلا مسار على القرص ولا مدخل أرشيف: لا شيء يُفتح
            item.isDir -> vm.openDir(File(p))
            item.name.endsWith(".apk", ignoreCase = true) ->
                if (!installApk(ctx, p)) vm.notify(ctx.getString(R.string.ui_allow_installs))
            item.kind == FileKind.ARCHIVE && FileKinds.isExtractableArchive(item.name) -> vm.openArchive(File(p))
            item.kind == FileKind.IMAGE -> {
                val imgs = shown.filter { it.kind == FileKind.IMAGE }.mapNotNull { it.path }
                onViewImages(imgs, imgs.indexOf(item.path).coerceAtLeast(0))
            }
            item.kind == FileKind.AUDIO -> playFrom(item)
            item.kind == FileKind.TEXT || item.kind == FileKind.CODE -> onEditText(p)
            org.openfilemanager.app.reader.ReaderRouting.formatOf(item.name) != null -> onOpenReader(p)
            else -> openWithSystem(ctx, p)
        }
    }

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { org.openfilemanager.app.ui.components.OfmSnackbarHost(snack) },
        topBar = {
            when {
                s.selecting -> TopAppBar(
                    title = { Text(stringResource(R.string.ui_selected_count, s.selection.size)) },
                    navigationIcon = { IconButton(vm::clearSelection) { Icon(Icons.Outlined.Close, stringResource(R.string.ui_cancel)) } },
                    actions = { IconButton(vm::selectAll) { Icon(Icons.Outlined.SelectAll, stringResource(R.string.ui_select_all)) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    scrollBehavior = scroll
                )
                s.query != null -> TopAppBar(
                    title = { SearchField(s.query ?: "", vm::setQuery) },
                    navigationIcon = { IconButton({ vm.setQuery(null) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_back)) } },
                    scrollBehavior = scroll
                )
                else -> TopAppBar(
                    title = {
                        Text(
                            when (loc) {
                                is Location.Dir -> if (loc.dir == vm.root) rootLabel else loc.dir.name
                                is Location.Archive -> loc.file.name
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton({ if (!vm.navigateUp()) onLeave() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_back))
                        }
                    },
                    actions = {
                        if (archiveFile != null && s.archiveEditable && s.archiveDirty) {
                            IconButton({ vm.saveArchive() }) {
                                Icon(Icons.Outlined.Save, stringResource(R.string.ui_save), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton({ vm.setQuery("") }) { Icon(Icons.Outlined.Search, stringResource(R.string.ui_search)) }
                        IconButton(org.openfilemanager.app.ui.LocalOpenDrawer.current) { Icon(Icons.Outlined.Menu, stringResource(R.string.drawer_menu)) }
                    },
                    scrollBehavior = scroll
                )
            }
        },
        bottomBar = {
            when {
                s.selecting && archiveFile != null && s.archiveEditable -> ActionBar(
                    listOf(
                        ActionBarItem(Icons.Outlined.ContentCopy, stringResource(R.string.ui_copy)) { vm.copySelection(false) },
                        ActionBarItem(Icons.Outlined.DriveFileMove, stringResource(R.string.ui_move)) { vm.copySelection(true) },
                        ActionBarItem(Icons.Outlined.Delete, stringResource(R.string.ui_delete), destructive = true) { confirmDelete = true },
                        ActionBarItem(Icons.Outlined.MoreVert, stringResource(R.string.ui_more)) { showSelectionMore = true }
                    )
                )
                s.selecting && archiveFile != null -> ActionBar(
                    listOf(ActionBarItem(Icons.Outlined.Archive, stringResource(R.string.ui_extract_selected)) {
                        vm.extractHere(archiveFile, vm.selectedArchiveEntries())
                    })
                )
                s.selecting -> ActionBar(
                    listOf(
                        ActionBarItem(Icons.Outlined.ContentCopy, stringResource(R.string.ui_copy)) { vm.copySelection(false) },
                        ActionBarItem(Icons.Outlined.DriveFileMove, stringResource(R.string.ui_move)) { vm.copySelection(true) },
                        ActionBarItem(Icons.Outlined.Share, stringResource(R.string.ui_share)) {
                            shareFiles(ctx, shown.filter { it.id in s.selection && !it.isDir }.mapNotNull { it.path })
                        },
                        ActionBarItem(Icons.Outlined.Delete, stringResource(R.string.ui_delete), destructive = true) { confirmDelete = true },
                        ActionBarItem(Icons.Outlined.MoreVert, stringResource(R.string.ui_more)) { showSelectionMore = true }
                    )
                )
                s.clipboard != null && (loc is Location.Dir || (loc is Location.Archive && s.archiveEditable)) -> PasteBar(
                    count = s.clipboard?.paths?.size ?: 0, move = s.clipboard?.move ?: false,
                    onPaste = vm::paste, onCancel = vm::cancelClipboard
                )
                // Nothing else to show: keep the player reachable while browsing folders too.
                else -> MiniPlayer(onOpenPlayer)
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!s.selecting) Breadcrumbs(
                when (loc) {
                    is Location.Dir -> dirCrumbs(loc.dir, vm.root, rootLabel, vm::openDir)
                    is Location.Archive -> archiveCrumbs(loc.file, loc.inner, vm::openArchive, vm::openInner, vm::openDir, vm.root)
                }
            )
            val ops = ctx.appContainer.opsCenter
            val opProgress by ops.progress.collectAsStateWithLifecycle()
            opProgress?.let { p -> OpsProgressStrip(p) { ops.requestCancelQueue(); ops.requestStopOp(p.opId) } }
            if (s.saving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Dim.Gutter))
            if (archiveFile != null && !s.selecting) {
                when {
                    s.archiveDirty -> Row(Modifier.fillMaxWidth().padding(start = Dim.Gutter, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.ui_unsaved), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f)
                        )
                        TextButton({ vm.saveArchive() }) { Text(stringResource(R.string.ui_save)) }
                    }
                    !s.archiveEditable && !s.loading -> Text(
                        stringResource(R.string.ui_archive_readonly), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dim.Gutter, vertical = 4.dp)
                    )
                }
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ vm.extractHere(archiveFile, null) }) { Text(stringResource(R.string.ui_extract_all)) }
                    if (s.archiveDirty) TextButton({ confirmDiscard = true }) {
                        Text(stringResource(R.string.ui_discard_changes), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = Dim.Gutter, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.ui_items_count, shown.size), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
                if (canCreate) IconButton({ showCreate = true }, Modifier.size(Dim.TouchTarget)) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.ui_create_new))
                }
                IconButton({ showSort = true }, Modifier.size(Dim.TouchTarget)) {
                    Icon(Icons.Outlined.Sort, stringResource(R.string.ui_sort_by))
                }
            }
            if (s.searchBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Dim.Gutter), trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            if (s.searchTruncated) {
                Text(
                    stringResource(R.string.ui_search_truncated), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dim.Gutter, vertical = 4.dp)
                )
            }
            // (المرحلة 4) النتائج الجزئية لا تُعرض كأنها كاملة: عدد ما تعذر الوصول
            // إليه أثناء البحث يظهر للنتائج الصحيحة — دون حجبها ودون كشف مسارات.
            if (s.searchErrors > 0) {
                Text(
                    stringResource(R.string.ui_search_errors, s.searchErrors), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dim.Gutter, vertical = 4.dp)
                )
            }
            if (s.archiveTruncated) {
                Text(
                    stringResource(R.string.ui_archive_truncated), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = Dim.Gutter, vertical = 4.dp)
                )
            }
            // (تصحيح) أيقونة التحديث كانت تبقى ظاهرة بعد السحب: PullToRefreshBox لا يُرجع
            // المؤشر إلا بعد أن تنتقل isRefreshing من true إلى false، والتحميل المحلي
            // السريع ينتهي قبل أن تتحول إلى true أصلاً. الآن نحتفظ بحالة سحب خاصة
            // ننهيها بعد حدّ أدنى للعرض وانتهاء التحميل الفعلي.
            var pullRefreshing by remember { mutableStateOf(false) }
            val loadingNow by rememberUpdatedState(s.loading)
            LaunchedEffect(pullRefreshing) {
                if (pullRefreshing) {
                    kotlinx.coroutines.delay(600)
                    while (loadingNow) kotlinx.coroutines.delay(50)
                    pullRefreshing = false
                }
            }
            PullToRefreshBox(
                isRefreshing = pullRefreshing || (s.loading && s.items.isNotEmpty()),
                onRefresh = { pullRefreshing = true; vm.reload() },
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                when {
                    s.loading && s.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    shown.isEmpty() -> EmptyState(
                        if (s.query.isNullOrBlank()) Icons.Outlined.FolderOpen else Icons.Outlined.SearchOff,
                        stringResource(if (s.query.isNullOrBlank()) R.string.ui_empty_folder else R.string.ui_no_results),
                        Modifier.align(Alignment.Center)
                    )
                    s.grid -> LazyVerticalGrid(
                        GridCells.Adaptive(Dim.GridCell), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(shown, key = { it.id }) { item ->
                            FileGridCell(item, item.id in s.selection, { open(item) }, { vm.longSelect(item.id) }, Modifier.animateItem())
                        }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(shown.size, key = { shown[it].id }) { i ->
                            val item = shown[i]
                            Column(Modifier.animateItem()) {
                                FileRow(
                                    item, item.id in s.selection, s.selecting,
                                    onClick = { open(item) }, onLongClick = { vm.longSelect(item.id) },
                                    onMore = { sheetItem = item }
                                )
                                HorizontalDivider(
                                    Modifier.padding(start = Dim.Gutter * 2 + Dim.Tile), color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- sheets and dialogs ----
    sheetItem?.let { item ->
        val acts = buildList {
            val itemPath = item.path
            if (!item.isArchiveEntry && itemPath != null) {
                if (item.isDir) {
                    val isPinned = item.path in pinnedPaths
                    add(SheetAction(Icons.Outlined.PushPin, stringResource(if (isPinned) R.string.drawer_unpin else R.string.drawer_pin)) {
                        if (isPinned) ctx.appContainer.pinned.unpin(itemPath)
                        else { ctx.appContainer.pinned.pin(itemPath); vm.notify(ctx.getString(R.string.drawer_pinned_done)) }
                    })
                }
                if (!item.isDir) add(SheetAction(Icons.Outlined.Share, stringResource(R.string.ui_share)) { shareFiles(ctx, listOf(itemPath)) })
                if (!item.isDir) add(SheetAction(Icons.Outlined.OpenInNew, stringResource(R.string.ui_open_with)) { openWithSystem(ctx, itemPath) })
                if (!item.isDir) add(SheetAction(Icons.Outlined.InsertDriveFile, stringResource(R.string.ui_open_as)) { openAsItem = item })
                if (item.kind == FileKind.ARCHIVE) add(SheetAction(Icons.Outlined.Archive, stringResource(R.string.ui_extract_here)) { vm.extractHere(File(itemPath), null) })
                add(SheetAction(Icons.Outlined.Edit, stringResource(R.string.ui_rename)) { renameItem = item })
                add(SheetAction(Icons.Outlined.ContentCopy, stringResource(R.string.ui_copy)) { vm.toggleSelect(item.id); vm.copySelection(false) })
                add(SheetAction(Icons.Outlined.DriveFileMove, stringResource(R.string.ui_move)) { vm.toggleSelect(item.id); vm.copySelection(true) })
                add(SheetAction(Icons.Outlined.FolderZip, stringResource(R.string.ui_compress)) { vm.toggleSelect(item.id); compressName = vm.defaultArchiveName() })
                add(SheetAction(Icons.Outlined.Delete, stringResource(R.string.ui_delete), destructive = true, dividerBefore = true) {
                    vm.toggleSelect(item.id); confirmDelete = true
                })
            } else if (archiveFile != null) {
                if (!item.isDir) add(SheetAction(Icons.Outlined.OpenInNew, stringResource(R.string.ui_open)) { open(item) })
                add(SheetAction(Icons.Outlined.Archive, stringResource(R.string.ui_extract_here)) {
                    item.entry?.let { vm.extractHere(archiveFile, setOf(it)) }
                })
                if (s.archiveEditable) {
                    add(SheetAction(Icons.Outlined.Edit, stringResource(R.string.ui_rename)) { renameItem = item })
                    add(SheetAction(Icons.Outlined.ContentCopy, stringResource(R.string.ui_copy)) { vm.toggleSelect(item.id); vm.copySelection(false) })
                    add(SheetAction(Icons.Outlined.DriveFileMove, stringResource(R.string.ui_move)) { vm.toggleSelect(item.id); vm.copySelection(true) })
                    add(SheetAction(Icons.Outlined.Delete, stringResource(R.string.ui_delete), destructive = true, dividerBefore = true) {
                        vm.toggleSelect(item.id); confirmDelete = true
                    })
                }
            }
        }
        ActionSheet(BidiText.wrap(item.name), acts) { sheetItem = null }
    }
    openAsItem?.let { item ->
        ActionSheet(
            stringResource(R.string.ui_open_as),
            listOf(
                SheetAction(Icons.Outlined.TextSnippet, stringResource(R.string.ui_as_text)) { openAs(item, 0) },
                SheetAction(Icons.Outlined.Image, stringResource(R.string.ui_as_image)) { openAs(item, 1) },
                SheetAction(Icons.Outlined.AudioFile, stringResource(R.string.ui_as_audio)) { openAs(item, 2) },
                SheetAction(Icons.Outlined.VideoFile, stringResource(R.string.ui_as_video)) { openAs(item, 3) },
                SheetAction(Icons.Outlined.InsertDriveFile, stringResource(R.string.ui_as_other)) { openAs(item, 4) }
            )
        ) { openAsItem = null }
    }
    if (showSort) SortSheet(s.sort, vm::setSort) { showSort = false }
    if (showCreate) ActionSheet(
        stringResource(R.string.ui_create_new),
        listOf(
            SheetAction(Icons.Outlined.NoteAdd, stringResource(R.string.ui_new_file)) { newFile = true },
            SheetAction(Icons.Outlined.CreateNewFolder, stringResource(R.string.ui_new_folder)) { newFolder = true }
        )
    ) { showCreate = false }
    if (showSelectionMore) ActionSheet(
        null,
        buildList {
            val chosen = shown.filter { it.id in s.selection }
            val single = chosen.singleOrNull()
            if (single != null && (!single.isArchiveEntry || s.archiveEditable)) {
                add(SheetAction(Icons.Outlined.Edit, stringResource(R.string.ui_rename)) { renameItem = single })
            } else if (chosen.size > 1 && (archiveFile == null || s.archiveEditable)) {
                add(SheetAction(Icons.Outlined.DriveFileRenameOutline, stringResource(R.string.ui_bulk_rename)) { showBulkRename = true })
            }
            if (archiveFile == null) add(SheetAction(Icons.Outlined.FolderZip, stringResource(R.string.ui_compress)) { compressName = vm.defaultArchiveName() })
            if (archiveFile != null) add(SheetAction(Icons.Outlined.Archive, stringResource(R.string.ui_extract_selected)) { vm.extractHere(archiveFile, vm.selectedArchiveEntries()) })
            val singlePath = single?.path
            if (single != null && singlePath != null && !single.isDir && !single.isArchiveEntry) {
                add(SheetAction(Icons.Outlined.OpenInNew, stringResource(R.string.ui_open_with)) { openWithSystem(ctx, singlePath) })
                add(SheetAction(Icons.Outlined.InsertDriveFile, stringResource(R.string.ui_open_as)) { openAsItem = single })
            }
        }
    ) { showSelectionMore = false }

    compressName?.let { initial ->
        NameDialog(stringResource(R.string.ui_compress), initial, { vm.compressSelection(it); compressName = null }) { compressName = null; vm.clearSelection() }
    }
    if (showBulkRename && s.selection.size > 1) BulkRenameDialog(vm, { spec -> showBulkRename = false; vm.bulkRename(spec) }) { showBulkRename = false }

    if (s.askSave) AlertDialog(
        onDismissRequest = { vm.cancelAskSave() },
        title = { Text(stringResource(R.string.ui_save_changes_title)) },
        text = { Text(stringResource(R.string.ui_save_changes_body)) },
        confirmButton = {
            Row {
                TextButton({ vm.cancelAskSave() }) { Text(stringResource(R.string.ui_cancel)) }
                TextButton({ vm.discardArchiveChanges() }) { Text(stringResource(R.string.ui_dont_save), color = MaterialTheme.colorScheme.error) }
                TextButton({ vm.saveArchive() }) { Text(stringResource(R.string.ui_save)) }
            }
        }
    )
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.ui_discard_changes)) },
        text = { Text(stringResource(R.string.ui_discard_archive_body)) },
        confirmButton = { TextButton({ confirmDiscard = false; vm.discardArchiveChanges() }) { Text(stringResource(R.string.ui_discard), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text(stringResource(R.string.ui_cancel)) } }
    )
    if (newFolder) NameDialog(stringResource(R.string.ui_new_folder), "", { vm.createItem(it, folder = true); newFolder = false }) { newFolder = false }
    if (newFile) NameDialog(stringResource(R.string.ui_new_file), "", { vm.createItem(it, folder = false); newFile = false }) { newFile = false }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.ui_delete_title, s.selection.size)) },
        text = { Text(stringResource(if (archiveFile != null) R.string.ui_delete_body_archive else R.string.ui_delete_body)) },
        confirmButton = {
            TextButton({ confirmDelete = false; vm.deleteSelection() }) {
                Text(stringResource(R.string.ui_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.ui_cancel)) } }
    )
    renameItem?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.name) }
        AlertDialog(
            onDismissRequest = { renameItem = null },
            title = { Text(stringResource(R.string.ui_rename)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ vm.rename(item, name); renameItem = null; vm.clearSelection() }) { Text(stringResource(R.string.ui_ok)) } },
            dismissButton = { TextButton({ renameItem = null }) { Text(stringResource(R.string.ui_cancel)) } }
        )
    }
}

private fun sortLabel(f: SortFieldCore): Int = when (f) {
    SortFieldCore.NAME -> R.string.ui_sort_name
    SortFieldCore.MODIFIED -> R.string.ui_sort_modified
    SortFieldCore.SIZE -> R.string.ui_sort_size
    SortFieldCore.TYPE -> R.string.ui_sort_type
}

private fun dirCrumbs(dir: File, root: File, rootLabel: String, open: (File) -> Unit): List<Crumb> {
    val chain = ArrayList<File>()
    var cur: File? = dir
    while (cur != null && cur != root) { chain.add(0, cur); cur = cur.parentFile }
    return listOf(Crumb(rootLabel) { open(root) }) + chain.map { f -> Crumb(f.name) { open(f) } }
}

private fun archiveCrumbs(
    archive: File, inner: String, openArchive: (File) -> Unit, openInner: (String) -> Unit,
    openDir: (File) -> Unit, root: File
): List<Crumb> {
    val parts = inner.split('/').filter { it.isNotEmpty() }
    val base = Crumb(archive.name) { openArchive(archive) }
    return listOf(base) + parts.mapIndexed { i, p -> Crumb(p) { openInner(parts.take(i + 1).joinToString("/") + "/") } }
}

@Composable
private fun Breadcrumbs(crumbs: List<Crumb>) {
    val state = rememberLazyListState()
    LaunchedEffect(crumbs.size) { state.scrollToItem((crumbs.size - 1).coerceAtLeast(0)) }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(crumbs) { i, c ->
            val last = i == crumbs.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (i > 0) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                Text(
                    c.label, style = MaterialTheme.typography.labelLarge,
                    color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.heightIn(min = 36.dp).clickable(enabled = !last, onClick = c.onClick).padding(horizontal = 8.dp, vertical = 10.dp)
                )
            }
        }
    }
}

internal data class ActionBarItem(val icon: ImageVector, val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

@Composable
internal fun ActionBar(items: List<ActionBarItem>) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.navigationBarsPadding().fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            items.forEach { a ->
                val tint = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                Column(
                    Modifier.weight(1f).heightIn(min = 52.dp).clickable(onClick = a.onClick).padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    BorderedIcon(a.icon, tint = tint)
                    Text(a.label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PasteBar(count: Int, move: Boolean, onPaste: () -> Unit, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = Dim.Gutter, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(if (move) R.string.ui_moving_n else R.string.ui_copying_n, count),
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)
            )
            TextButton(onCancel) { Text(stringResource(R.string.ui_cancel)) }
            androidx.compose.material3.Button(onPaste) { Text(stringResource(R.string.ui_paste_here)) }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraLarge)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(
                        stringResource(R.string.ui_search_here), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    inner()
                }
            }
        }
    )
}

@Composable
internal fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton({ onConfirm(name) }) { Text(stringResource(R.string.ui_ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.ui_cancel)) } }
    )
}

@Composable
private fun OpsProgressStrip(p: OpProgressInfo, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = Dim.Gutter, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (p.itemCount > 0) stringResource(
                    R.string.file_ops_progress_line, p.label, minOf(p.itemIndex + 1, p.itemCount), p.itemCount, BidiText.wrap(p.currentFile)
                ) else "${p.label} — ${BidiText.wrap(p.currentFile)}",
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onCancel) { Text(stringResource(R.string.file_ops_cancel)) }
        }
        if (p.bytesTotal > 0) LinearProgressIndicator(
            progress = { (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(end = 12.dp)
        ) else LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 12.dp))
    }
}

/**
 * Bulk rename: four fields (template, global extension, start, extra zeros) and a live preview.
 * The preview and the real rename both come from [BatchRename.plan] (same engine, same inputs).
 */
@Composable
internal fun BulkRenameDialog(vm: BrowserViewModel, onConfirm: (BatchRenameSpec) -> Unit, onDismiss: () -> Unit) {
    var data by remember { mutableStateOf<BulkRenameContext?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        data = vm.bulkRenameContext()
        loaded = true
        if (data == null) onDismiss()
    }
    var template by remember { mutableStateOf("%%") }
    var ext by remember { mutableStateOf("") }
    var startText by remember { mutableStateOf("1") }
    var zerosText by remember { mutableStateOf("") }

    val start = BatchRename.parseStart(startText)
    val zeros = BatchRename.parseZeros(zerosText)
    val spec = if (start != null && zeros != null) BatchRenameSpec(template, ext, start, zeros) else null
    val context = data
    // Recomputed off the main thread, debounced; a newer edit cancels the previous run.
    val plan by produceState<RenamePlan?>(null, context, spec) {
        if (context == null || spec == null) { value = null; return@produceState }
        delay(120)
        value = withContext(Dispatchers.Default) { BatchRename.plan(context.inputs, spec, context.existing, context.strictFat) }
    }
    val current = plan

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp).heightIn(max = 680.dp)
        ) {
            Column(Modifier.padding(top = 20.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                Text(stringResource(R.string.ui_bulk_rename), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                template, { template = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.ui_bulk_template)) },
                                supportingText = { Text(stringResource(R.string.ui_bulk_template_hint)) }
                            )
                            OutlinedTextField(
                                ext, { ext = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.ui_bulk_ext)) },
                                placeholder = { Text("png") },
                                supportingText = { Text(stringResource(R.string.ui_bulk_ext_hint)) }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    startText, { startText = it.filter { c -> c in '0'..'9' }.take(18) }, singleLine = true,
                                    modifier = Modifier.weight(1f), isError = start == null,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    label = { Text(stringResource(R.string.ui_bulk_start)) },
                                    supportingText = { Text(stringResource(R.string.ui_bulk_start_hint)) }
                                )
                                OutlinedTextField(
                                    zerosText, { zerosText = it.filter { c -> c in '0'..'9' }.take(2) }, singleLine = true,
                                    modifier = Modifier.weight(1f), isError = zeros == null,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    label = { Text(stringResource(R.string.ui_bulk_zeros)) },
                                    supportingText = { Text(stringResource(R.string.ui_bulk_zeros_hint)) }
                                )
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            when {
                                !loaded || (spec != null && current == null) ->
                                    CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp))
                                current != null -> Text(
                                    stringResource(R.string.ui_bulk_summary, current.changedCount, current.unchangedCount, current.blockingCount),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (current.blockingCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (current != null) {
                        val numbered = template.contains("##")
                        items(current.rows.size, key = { current.rows[it].index }) { i ->
                            BulkPreviewRow(current.rows[i], numbered)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text(stringResource(R.string.ui_cancel)) }
                    Button({ spec?.let(onConfirm) }, enabled = current?.canApply == true) { Text(stringResource(R.string.ui_bulk_apply)) }
                }
            }
        }
    }
}

@Composable
private fun BulkPreviewRow(row: RenameRow, numbered: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val color = when {
        row.blocking -> scheme.error
        row.status == RenameStatus.UNCHANGED -> scheme.onSurfaceVariant
        else -> scheme.onSurface
    }
    val note = when (row.status) {
        RenameStatus.UNCHANGED -> stringResource(R.string.ui_bulk_st_unchanged)
        RenameStatus.DUPLICATE -> stringResource(R.string.ui_bulk_st_dup)
        RenameStatus.CONFLICT -> stringResource(R.string.ui_bulk_st_conflict)
        RenameStatus.INVALID -> stringResource(R.string.ui_bulk_st_invalid)
        RenameStatus.OK -> listOfNotNull(
            if (numbered) stringResource(R.string.ui_bulk_meta_number, row.number) else null,
            row.extension.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.ui_bulk_meta_ext, it) }
        ).joinToString(" · ")
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            BidiText.wrap(row.original), style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        Text(
            BidiText.wrap(row.newName), style = MaterialTheme.typography.bodyMedium,
            color = color, maxLines = 3, overflow = TextOverflow.Ellipsis
        )
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.labelSmall, color = if (row.blocking) scheme.error else scheme.outline)
    }
}

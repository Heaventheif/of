package org.openfilemanager.app.ui.screens

import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PushPin
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import org.openfilemanager.app.ui.components.FileDetailsDialog
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.delay
import org.openfilemanager.app.appContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import org.openfilemanager.app.MusicPlaybackService
import org.openfilemanager.app.R
import org.openfilemanager.app.ui.BrowserViewModel
import org.openfilemanager.app.ui.FileItem
import org.openfilemanager.app.ui.Location
import org.openfilemanager.app.ui.components.ActionSheet
import org.openfilemanager.app.ui.components.EmptyState
import org.openfilemanager.app.ui.components.FileGridCell
import org.openfilemanager.app.ui.components.FileRow
import org.openfilemanager.app.ui.components.SheetAction
import org.openfilemanager.app.ui.components.SortSheet
import org.openfilemanager.app.ui.installApk
import org.openfilemanager.app.ui.openWithSystem
import org.openfilemanager.app.ui.LocalSnack
import org.openfilemanager.app.BidiText
import org.openfilemanager.app.ui.shareFiles
import org.openfilemanager.app.ui.theme.Dim
import org.openfilemanager.core.FileKind
import org.openfilemanager.core.FileKinds

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
    var detailsPaths by remember { mutableStateOf<List<String>?>(null) }
    var renameItem by remember { mutableStateOf<FileItem?>(null) }
    var openAsItem by remember { mutableStateOf<FileItem?>(null) }
    var compressName by remember { mutableStateOf<String?>(null) }
    var showBulkRename by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var newFile by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // وضعا التمرير للقائمة والشبكة: كل موقع جديد يبدأ من الأعلى بدل أن يرث
    // موضع المجلد السابق، وزر العودة للأعلى يظهر بعد تمرير طويل فقط
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    LaunchedEffect(s.location) { listState.scrollToItem(0); gridState.scrollToItem(0) }
    val scrolledDown by remember {
        androidx.compose.runtime.derivedStateOf { if (s.grid) gridState.firstVisibleItemIndex > 24 else listState.firstVisibleItemIndex > 24 }
    }
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()

    val shown = s.visibleItems
    val loc = s.location
    val archiveFile = (loc as? Location.Archive)?.file
    val rootLabel = stringResource(R.string.ui_internal_storage)
    val canCreate = !s.selecting && s.query == null && s.clipboard == null &&
        (loc is Location.Dir || (loc is Location.Archive && s.archiveEditable))

    fun playFrom(item: FileItem) {
        val tracks = shown.filter { it.kind == FileKind.AUDIO }.mapNotNull { it.path }
        // مسارات خام للمشغل: FileProvider كان يحولها إلى content:// فتفشل
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
            org.openfilemanager.app.reader.ReaderRouting.let { it.formatOf(item.name) != null || it.isLegacyDoc(item.name) || it.isPresentation(item.name) } -> onOpenReader(p)
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
                        // Rename sits in the main bar (Share moved to "More"): one item renames it,
                        // several open the bulk-rename dialog.
                        ActionBarItem(Icons.Outlined.Edit, stringResource(R.string.ui_rename)) {
                            val chosen = shown.filter { it.id in s.selection }
                            val single = chosen.singleOrNull()
                            if (single != null) renameItem = single
                            else if (chosen.size > 1) showBulkRename = true
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
            // أيقونة التحديث كانت تبقى ظاهرة بعد السحب: PullToRefreshBox لا يُرجع المؤشر إلا بعد أن تنتقل isRefreshing من true إلى false، والتحميل المحلي السريع…
            var pullRefreshing by remember { mutableStateOf(false) }
            val loadingNow by rememberUpdatedState(s.loading)
            LaunchedEffect(pullRefreshing) {
                if (pullRefreshing) {
                    kotlinx.coroutines.delay(600)
                    while (loadingNow) kotlinx.coroutines.delay(50)
                    pullRefreshing = false
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            PullToRefreshBox(
                isRefreshing = pullRefreshing || (s.loading && s.items.isNotEmpty()),
                onRefresh = { pullRefreshing = true; vm.reload() },
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    s.loading && s.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    shown.isEmpty() -> EmptyState(
                        if (s.query.isNullOrBlank()) Icons.Outlined.FolderOpen else Icons.Outlined.SearchOff,
                        stringResource(if (s.query.isNullOrBlank()) R.string.ui_empty_folder else R.string.ui_no_results),
                        Modifier.align(Alignment.Center)
                    )
                    s.grid -> LazyVerticalGrid(
                        GridCells.Adaptive(Dim.GridCell), state = gridState, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(shown, key = { it.id }) { item ->
                            FileGridCell(item, item.id in s.selection, { open(item) }, { vm.longSelect(item.id) }, Modifier.animateItem())
                        }
                    }
                    else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(shown.size, key = { shown[it].id }) { i ->
                            val item = shown[i]
                            Column(Modifier.animateItem()) {
                                FileRow(
                                    item, item.id in s.selection, s.selecting,
                                    onClick = { open(item) }, onLongClick = { vm.longSelect(item.id) },
                                    onMore = { sheetItem = item },
                                    // Per-row three-dots removed: all actions live in the selection bar / its "more" menu
                                    showMore = false
                                )
                                HorizontalDivider(
                                    Modifier.padding(start = Dim.Gutter * 2 + Dim.Tile), color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = scrolledDown && !s.selecting,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = Dim.Gutter, bottom = 16.dp)
            ) {
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = { uiScope.launch { if (s.grid) gridState.animateScrollToItem(0) else listState.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowUp,
                        stringResource(R.string.ui_scroll_top)
                    )
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
                add(SheetAction(Icons.Outlined.Info, stringResource(R.string.ui_details)) { detailsPaths = listOf(itemPath.toString()) })
                if (item.isDir) {
                    val isPinned = item.path in pinnedPaths
                    add(SheetAction(Icons.Outlined.PushPin, stringResource(if (isPinned) R.string.drawer_unpin else R.string.drawer_pin)) {
                        if (isPinned) ctx.appContainer.pinned.unpin(itemPath)
                        else { ctx.appContainer.pinned.pin(itemPath); vm.notify(ctx.getString(R.string.drawer_pinned_done)) }
                    })
                }
                if (item.kind == FileKind.ARCHIVE) add(SheetAction(Icons.Outlined.Archive, stringResource(R.string.ui_extract_here)) { vm.extractHere(File(itemPath), null) })
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
    detailsPaths?.let { FileDetailsDialog(it) { detailsPaths = null } }
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
            // (جديد) التفاصيل: الاسم والمسار الكامل والحجم والتواريخ للعناصر المحددة.
            val detailPaths = chosen.filter { !it.isArchiveEntry }.mapNotNull { it.path?.toString() }
            if (detailPaths.isNotEmpty()) {
                add(SheetAction(Icons.Outlined.Info, stringResource(R.string.ui_details)) { detailsPaths = detailPaths })
            }
            if (archiveFile == null) {
                // Normal browsing: Rename lives in the bottom bar, so Share takes its place here.
                val sharePaths = chosen.filter { !it.isDir }.mapNotNull { it.path }
                if (sharePaths.isNotEmpty()) {
                    add(SheetAction(Icons.Outlined.Share, stringResource(R.string.ui_share)) { shareFiles(ctx, sharePaths) })
                }
            } else if (single != null && (!single.isArchiveEntry || s.archiveEditable)) {
                // Inside an editable archive the bottom bar has no Rename, so keep it here.
                add(SheetAction(Icons.Outlined.Edit, stringResource(R.string.ui_rename)) { renameItem = single })
            } else if (chosen.size > 1 && s.archiveEditable) {
                add(SheetAction(Icons.Outlined.DriveFileRenameOutline, stringResource(R.string.ui_bulk_rename)) { showBulkRename = true })
            }
            // An archive is already compressed: don't offer to compress a selection made only of archives.
            val onlyArchives = chosen.isNotEmpty() && chosen.all { !it.isDir && it.kind == FileKind.ARCHIVE }
            if (archiveFile == null && !onlyArchives) add(SheetAction(Icons.Outlined.FolderZip, stringResource(R.string.ui_compress)) { compressName = vm.defaultArchiveName() })
            if (archiveFile != null) add(SheetAction(Icons.Outlined.Archive, stringResource(R.string.ui_extract_selected)) { vm.extractHere(archiveFile, vm.selectedArchiveEntries()) })
            val singlePath = single?.path
            if (single != null && singlePath != null && !single.isArchiveEntry) {
                if (single.isDir) {
                    val isPinned = singlePath in pinnedPaths
                    add(SheetAction(Icons.Outlined.PushPin, stringResource(if (isPinned) R.string.drawer_unpin else R.string.drawer_pin)) {
                        if (isPinned) ctx.appContainer.pinned.unpin(singlePath)
                        else { ctx.appContainer.pinned.pin(singlePath); vm.notify(ctx.getString(R.string.drawer_pinned_done)) }
                        vm.clearSelection()
                    })
                }
                if (single.kind == FileKind.ARCHIVE) add(SheetAction(Icons.Outlined.Archive, stringResource(R.string.ui_extract_here)) { vm.extractHere(File(singlePath), null); vm.clearSelection() })
            }
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

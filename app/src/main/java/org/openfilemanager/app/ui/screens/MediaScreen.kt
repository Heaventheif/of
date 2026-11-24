package org.openfilemanager.app.ui.screens

import org.openfilemanager.app.ui.components.show
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.openfilemanager.app.FileOperationService
import org.openfilemanager.app.OpRequest
import org.openfilemanager.app.ui.shareFiles
import org.openfilemanager.app.ui.LocalSnack
import java.io.File
import org.openfilemanager.app.LibraryTab
import org.openfilemanager.app.MediaItem
import org.openfilemanager.app.MediaLibrary
import org.openfilemanager.app.MusicPlaybackService
import org.openfilemanager.app.R
import org.openfilemanager.app.ui.components.FileRow
import org.openfilemanager.app.ui.FileItem
import org.openfilemanager.app.ui.installApk
import org.openfilemanager.app.ui.openUriWithSystem
import org.openfilemanager.app.ui.openWithSystem
import org.openfilemanager.app.ui.theme.Dim
import org.openfilemanager.core.FileKinds

private val TABS = listOf(
    LibraryTab.IMAGES to R.string.ui_images, LibraryTab.VIDEOS to R.string.ui_videos,
    LibraryTab.AUDIO to R.string.ui_music, LibraryTab.DOCUMENTS to R.string.ui_documents, LibraryTab.APK to R.string.ui_apps
)

/**
 * Categories come straight from MediaStore (indexed by the system, paged 200 at a time),
 * not from a depth-limited folder crawl: that crawl is why these pages used to say "No media found".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaScreen(
    initialTab: LibraryTab,
    onViewImages: (paths: List<String>, index: Int) -> Unit,
    onOpenPlayer: () -> Unit,
    bottomBar: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val lib = remember { MediaLibrary(ctx) }
    var tab by remember { mutableStateOf(initialTab) }
    var media by remember(tab) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember(tab) { mutableStateOf(true) }
    var exhausted by remember(tab) { mutableStateOf(false) }
    var error by remember(tab) { mutableStateOf<String?>(null) }
    // (F-02) الإزاحة المستهلكة من MediaStore منفصلة عن عدد العناصر المعروضة:
    // الحذف المحلي يقلّص media.size فكان العدد الجديد يُستخدم QUERY_ARG_OFFSET
    // فيعود ما رأيناه وتتكرر العناصر وتتقلب المفاتيح. الإزاحة تتقدم بالصفوف
    // المستهلكة فعلاً لا بما تظهره القائمة بعد الحذف/التصفية.
    var loadedOffset by remember(tab) { mutableStateOf(0) }
    // (تحديث مؤجل) حدث ContentObserver أثناء تحميل جارٍ كان يُهمل تماماً
    // ("if (loading) return") فتضيع التغييرات إن وقعت أثناء تصفّح أول صفحة —
    // الآن يُعلَم أن تحديثاً مطلوباً فيُنفّذ عند انتهاء التحميل الجاري.
    var refreshQueued by remember(tab) { mutableStateOf(false) }

    // ---- وضع التحديد (ضغط مطوّل يبدأه، والضغط اللاحق يبدّل، والضغط المطوّل على عنصر آخر يحدد النطاق) ----
    var selection by remember(tab) { mutableStateOf<Set<String>>(emptySet()) }
    var anchor by remember(tab) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackState = LocalSnack.current
    BackHandler(enabled = selecting) { selection = emptySet(); anchor = null }

    fun toggle(key: String) {
        selection = if (key in selection) selection - key else selection + key
        anchor = if (key in selection) key else null
    }
    fun longSelect(key: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val a = anchor?.let { k -> media.indexOfFirst { it.idKey == k } } ?: -1
        val b = media.indexOfFirst { it.idKey == key }
        if (!selecting || a < 0 || b < 0 || a == b) { toggle(key); return }
        selection = selection + media.subList(minOf(a, b), maxOf(a, b) + 1).map { it.idKey }
        anchor = key
    }
    fun selectedPaths(): List<String> = media.filter { it.idKey in selection }.mapNotNull { it.dataPath }

    /** جسم إعادة التحميل الكامل (F-13) بلا أي نداء أمامي — يُنادى من الأخيرين فقط. */
    suspend fun reloadLoadedPages() {
        loading = true
        val target = loadedOffset
        val fresh = ArrayList<MediaItem>(media.size)
        var offset = 0
        var shortPage = false
        var failed = false
        while (offset < target && !shortPage) {
            val page = runCatching { lib.page(tab, offset) }
                .onFailure { failed = true; error = it.message ?: it.javaClass.simpleName }
                .getOrDefault(emptyList())
            fresh += page
            shortPage = page.size < MediaLibrary.PAGE_SIZE
            offset += page.size
        }
        loadedOffset = offset
        val seen = HashSet<Long>()
        media = fresh.filter { seen.add(it.id) }
        exhausted = shortPage
        // عناصر حُذفت خلف ظهرنا: لا تبقى محددة بلا وجود فعلي.
        val present = media.mapTo(HashSet()) { it.idKey }
        selection = selection.intersect(present)
        loading = false
        // نجاح لاحق يطهّر خطأً سابقاً: لا يعلق نص الخطأ فوق قائمة سليمة.
        if (!failed) error = null
    }

    suspend fun loadMore() {
        loading = true
        val page = runCatching { lib.page(tab, loadedOffset) }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
            .getOrDefault(emptyList())
        loadedOffset += page.size
        // دمج بلا تكرار: تغيّر البيانات بين الصفحات (خارجي أو حذف) قد يعيد
        // صفوفاً رأيناها — idKey المكرر كان يكسر مفاتيح Compose (F-02).
        val seen = media.mapTo(HashSet()) { it.id }
        media = media + page.filter { seen.add(it.id) }
        exhausted = page.size < MediaLibrary.PAGE_SIZE
        loading = false
        // نجاح واضح بعد خطأ سابق: الخطأ القديم لا يعلق فوق قائمة سليمة.
        error = null
        if (refreshQueued) {
            refreshQueued = false
            reloadLoadedPages()
        }
    }

    /**
     * (F-13) تحديث عند تغيّر MediaStore من تطبيق آخر: يُعاد تحميل كل الصفحات
     * المحمّلة (الترتيب تغيّر فالإزاحات القديمة وحدها لا تصف قائمة سليمة) مع
     * إزالة التكرار، والاختناق الموجود في observeChanges يحمي من تتابع الأحداث.
     * تحميل جارٍ؟ يُؤجَّل الحدث لا يُهمل — انتهاء التحميل ينفّذه فوراً.
     */
    suspend fun refreshLoaded() {
        if (loading) { refreshQueued = true; return }
        if (loadedOffset == 0) { loadMore(); return }
        reloadLoadedPages()
    }
    LaunchedEffect(tab) { loadMore() }

    // (F-13) ربط observer دورة حياة الشاشة: تسجيل عند الظهور وإلغاء عند المغادرة،
    // فتغييرات المكتبة من تطبيقات أخرى تنعكس بدل بقائها حتى تبديل الشاشة.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, tab) {
        val unregister = lib.observeChanges { scope.launch { refreshLoaded() } }
        onDispose { unregister() }
    }

    val gridState = rememberLazyGridState()
    val nearEnd by remember(media.size) {
        derivedStateOf { (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= media.size - 24 }
    }
    LaunchedEffect(nearEnd, media.size, loading) { if (nearEnd && !loading && !exhausted && media.isNotEmpty()) loadMore() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { org.openfilemanager.app.ui.components.OfmSnackbarHost(org.openfilemanager.app.ui.LocalSnack.current) },
        topBar = {
            if (selecting) TopAppBar(
                title = { Text(stringResource(R.string.ui_selected_count, selection.size)) },
                navigationIcon = { IconButton({ selection = emptySet(); anchor = null }) { Icon(Icons.Outlined.Close, stringResource(R.string.ui_cancel)) } },
                actions = { IconButton({ selection = media.mapTo(HashSet()) { it.idKey } }) { Icon(Icons.Outlined.SelectAll, stringResource(R.string.ui_select_all)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) else TopAppBar(
                title = { Text(stringResource(R.string.ui_library)) },
                navigationIcon = {
                    IconButton(org.openfilemanager.app.ui.LocalOpenDrawer.current) {
                        Icon(androidx.compose.material.icons.Icons.Outlined.Menu, stringResource(R.string.drawer_menu))
                    }
                }
            )
        },
        bottomBar = {
            if (selecting) ActionBar(
                listOf(
                    ActionBarItem(Icons.Outlined.Share, stringResource(R.string.ui_share)) { shareFiles(ctx, selectedPaths()) },
                    ActionBarItem(Icons.Outlined.Delete, stringResource(R.string.ui_delete), destructive = true) { confirmDelete = true }
                )
            ) else bottomBar()
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyRow(contentPadding = PaddingValues(horizontal = Dim.Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TABS.size) { i ->
                    val (t, label) = TABS[i]
                    FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(stringResource(label)) })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading && media.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    media.isEmpty() -> Text(
                        error?.let { stringResource(R.string.ui_media_error, it) } ?: stringResource(R.string.ui_no_media), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(32.dp)
                    )
                    tab == LibraryTab.IMAGES || tab == LibraryTab.VIDEOS -> LazyVerticalGrid(
                        GridCells.Adaptive(104.dp), state = gridState, contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(media.size, key = { media[it].idKey }) { i ->
                            val m = media[i]
                            val picked = m.idKey in selection
                            Box(
                                Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainer).combinedClickable(
                                    onClick = {
                                        if (selecting) toggle(m.idKey)
                                        else if (tab == LibraryTab.IMAGES) {
                                            val paths = media.mapNotNull { it.dataPath }
                                            onViewImages(paths, paths.indexOf(m.dataPath).coerceAtLeast(0))
                                        } else openUriWithSystem(ctx, m.uri, m.mimeType)
                                    },
                                    onLongClick = { longSelect(m.idKey) }
                                )
                            ) {
                                AsyncImage(m.uri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                if (picked) {
                                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)))
                                    Box(
                                        Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)) }
                                }
                            }
                        }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(media.size, key = { media[it].idKey }) { i ->
                            val m = media[i]
                            val fi = FileItem("media:${m.id}", m.name, false, m.size, m.addedAt, FileKinds.of(m.name), m.dataPath)
                            FileRow(fi, m.idKey in selection, selecting, onClick = {
                                val p = m.dataPath
                                when {
                                    selecting -> toggle(m.idKey)
                                    tab == LibraryTab.AUDIO && p != null -> {
                                        val tracks = media.mapNotNull { it.dataPath }
                                        // (F-03) مسارات خام للمشغل لا FileProvider URIs:
                                        // الوسوم والكلمات الجانبية تُقرأ من المسار نفسه.
                                        MusicPlaybackService.start(ctx, tracks, tracks.indexOf(p).coerceAtLeast(0))
                                        onOpenPlayer()
                                    }
                                    tab == LibraryTab.APK && p != null -> installApk(ctx, p)
                                    p != null -> openWithSystem(ctx, p)
                                    else -> openUriWithSystem(ctx, m.uri, m.mimeType)
                                }
                            }, onLongClick = { longSelect(m.idKey) }, onMore = {}, showMore = false)
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.ui_delete_title, selection.size)) },
        text = { Text(stringResource(R.string.ui_delete_body)) },
        confirmButton = {
            TextButton({
                confirmDelete = false
                val paths = selectedPaths()
                val queued = paths.isNotEmpty() &&
                    FileOperationService.enqueue(ctx, OpRequest.Delete(paths.map { File(it).toPath() }))
                if (queued) {
                    val gone = selection
                    media = media.filterNot { it.idKey in gone }
                    selection = emptySet(); anchor = null
                }
                scope.launch { snackState.show(ctx.getString(if (queued) R.string.ui_op_queued else R.string.ui_op_queue_failed), if (queued) org.openfilemanager.app.ui.components.SnackKind.SUCCESS else org.openfilemanager.app.ui.components.SnackKind.ERROR) }
            }) { Text(stringResource(R.string.ui_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.ui_cancel)) } }
    )
}

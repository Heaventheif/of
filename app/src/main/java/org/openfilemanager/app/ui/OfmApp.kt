package org.openfilemanager.app.ui

import org.openfilemanager.app.ui.components.kindLabelRes
import org.openfilemanager.app.ui.components.SnackKind
import org.openfilemanager.app.ui.components.show
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import org.openfilemanager.app.AppLog
import org.openfilemanager.app.BidiText
import org.openfilemanager.app.FeatureFlags
import org.openfilemanager.app.LibraryTab
import org.openfilemanager.app.MusicPlaybackService
import org.openfilemanager.app.OpRecoveryActions
import org.openfilemanager.app.R
import org.openfilemanager.app.appContainer
import org.openfilemanager.app.reader.DocumentReaderScreen
import org.openfilemanager.app.reader.ReaderRouting
import org.openfilemanager.app.imageedit.ui.ImageEditorScreen
import org.openfilemanager.app.ui.screens.BrowserScreen
import org.openfilemanager.app.ui.screens.HomeScreen
import org.openfilemanager.app.ui.screens.ImageViewerScreen
import org.openfilemanager.app.ui.screens.MediaScreen
import org.openfilemanager.app.ui.screens.MiniPlayer
import org.openfilemanager.app.ui.screens.OperationHistoryScreen
import org.openfilemanager.app.ui.screens.PlayerScreen
import org.openfilemanager.app.ui.screens.RecoveryCenterScreen
import org.openfilemanager.app.ui.screens.SettingsScreen
import org.openfilemanager.app.ui.screens.TextEditorScreen
import org.openfilemanager.core.ConflictDecision
import org.openfilemanager.core.ConflictPolicy
import org.openfilemanager.core.FileKind
import org.openfilemanager.core.FileKinds
import org.openfilemanager.core.OpKind

/** One snackbar host for the whole app so operation results show on whichever screen is open. */
val LocalSnack = staticCompositionLocalOf<SnackbarHostState> { error("SnackbarHostState not provided") }

sealed interface Route {
    data object Home : Route
    data object Files : Route
    data class Media(val tab: LibraryTab) : Route
    data class Viewer(val paths: List<String>, val index: Int, val returnTo: Route) : Route
    data class Editor(val path: String, val readOnly: Boolean, val returnTo: Route, val zipEntry: String? = null) : Route
    /** محرّر الصور — مسار مستقل عن محرّر النصوص Route.Editor. */
    data class ImageEditor(val path: String, val returnTo: Route) : Route
    data class Reader(val path: String, val returnTo: Route) : Route
    data class Player(val returnTo: Route) : Route
    data object Settings : Route
    data object Recent : Route

    data object RecoveryCenter : Route

    data object OperationHistory : Route
}

/** Holds the current screen so it survives rotation. */
class NavViewModel : ViewModel() {
    var route: Route by mutableStateOf(Route.Home)

    /** Opens any file with the right in-app screen; unknown types go to another app. */
    fun openFile(ctx: Context, path: String, readOnly: Boolean, returnTo: Route) {
        ctx.appContainer.lastOpened.record(File(path).toPath())
        if (File(path).name.let { ReaderRouting.formatOf(it) != null || ReaderRouting.isLegacyDoc(it) || ReaderRouting.isPresentation(it) }) { route = Route.Reader(path, returnTo); return }
        when (FileKinds.of(File(path).name)) {
            FileKind.IMAGE -> route = Route.Viewer(listOf(path), 0, returnTo)
            FileKind.TEXT, FileKind.CODE -> route = Route.Editor(path, readOnly, returnTo)
            FileKind.AUDIO -> {
                // مسارات محلية خام للمشغل: الخدمة تحوّلها لأصل تشغيل بنفسها
                // وتقرأ الوسوم والكلمات من المسار مباشرة — FileProvider كان يحوّلها
                // إلى content:// فتفشل الوسوم والكلمات الجانبية.
                MusicPlaybackService.start(ctx, listOf(path), 0)
                route = Route.Player(returnTo)
            }
            else -> openWithSystem(ctx, path)
        }
    }

    /** Files that arrived from another app (open/share). */
    fun openIncoming(ctx: Context, files: List<File>, browser: BrowserViewModel) {
        val one = files.singleOrNull()
        when {
            one == null -> { browser.openDir(files.first().parentFile ?: browser.root); route = Route.Files }
            FileKinds.isExtractableArchive(one.name) -> { browser.openArchive(one); route = Route.Files }
            else -> openFile(ctx, one.absolutePath, readOnly = false, returnTo = Route.Home)
        }
    }
}

@Composable
fun OfmApp() {
    val nav: NavViewModel = viewModel(LocalActivityOwner.current)
    val browser: BrowserViewModel = viewModel(LocalActivityOwner.current)
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val route = nav.route
    val undoLabel = stringResource(R.string.ui_undo)

    LaunchedEffect(Unit) {
        browser.events.collect { e ->
            when (e) {
                is UiEvent.Message -> snack.show(e.text, e.kind)
                is UiEvent.RenameDone -> {
                    val r = snack.show(e.text, SnackKind.SUCCESS, undoLabel)
                    if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) browser.undoBulkRename()
                }
                is UiEvent.OpenExtracted ->
                    if (e.entry != null && browser.state.value.archiveEditable &&
                        FileKinds.of(File(e.path).name).let { it == FileKind.TEXT || it == FileKind.CODE }
                    ) nav.route = Route.Editor(e.path, readOnly = false, returnTo = Route.Files, zipEntry = e.entry)
                    else nav.openFile(ctx, e.path, readOnly = true, returnTo = Route.Files)
            }
        }
    }

    // (نتائج دائمة) حلقة عرض النتائج غير المقرّ بها: كل نتيجة تُعرض فعلاً
    // ثم يُقرّ عليها بعدها لا قبله — إعادة إنشاء الواجهة (تدوير وغيره) لا
    // تفقد نتيجة (تُعاد عرضها)، ولا تُداس نتيجة فوق أخرى (طابور لا خانة).
    LaunchedEffect(Unit) {
        val ops = ctx.appContainer.opsCenter
        while (true) {
            val entry = ops.nextUnacknowledged()
            if (entry == null) {
                ops.pendingResults.first { it.isNotEmpty() }
                continue
            }
            val oc = entry.outcome
            val kind = when {
                entry.isError || (oc?.failed ?: 0) > 0 -> SnackKind.ERROR
                oc != null && oc.ok == 0 && oc.skipped > 0 -> SnackKind.INFO
                else -> SnackKind.SUCCESS
            }
            snack.show(entry.message, kind)
            ops.acknowledgeResult(entry.seq)
        }
    }

    // Navigation lives in the side menu now; only the mini player stays at the bottom.
    val bottomBar: @Composable () -> Unit = { MiniPlayer { nav.route = Route.Player(route) } }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val pinned by ctx.appContainer.pinned.paths.collectAsStateWithLifecycle(emptyList())
    var showAbout by remember { mutableStateOf(false) }
    BackHandler(drawerState.isOpen) { drawerScope.launch { drawerState.close() } }
    fun go(action: () -> Unit) { drawerScope.launch { drawerState.close() }; action() }

    CompositionLocalProvider(LocalSnack provides snack, LocalOpenDrawer provides { drawerScope.launch { drawerState.open() } }) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = route is Route.Home || route is Route.Files || route is Route.Media || route is Route.Recent || drawerState.isOpen,
            drawerContent = {
                AppDrawer(
                    route = route, pinned = pinned,
                    onHome = { go { nav.route = Route.Home } },
                    onFiles = { go { nav.route = Route.Files } },
                    onLibrary = { go { nav.route = Route.Media(LibraryTab.IMAGES) } },
                    onRecent = { go { nav.route = Route.Recent } },
                    onOpenPinned = { dir -> go { browser.openDir(dir); nav.route = Route.Files } },
                    onUnpin = { ctx.appContainer.pinned.unpin(it) },
                    onSettings = { go { nav.route = Route.Settings } },
                    onAbout = { go { showAbout = true } }
                )
            }
        ) {
        Crossfade(targetState = route, animationSpec = tween(160), label = "screen") { route ->
            when (route) {
                Route.Home -> HomeScreen(
                    onOpenFolder = { browser.openDir(it); nav.route = Route.Files },
                    onOpenMedia = { nav.route = Route.Media(it) },
                    onOpenFile = { f ->
                        if (f.isDirectory) { browser.openDir(f); nav.route = Route.Files }
                        else nav.openFile(ctx, f.absolutePath, readOnly = false, returnTo = Route.Home)
                    },
                    onOpenSettings = { nav.route = Route.Settings },
                    bottomBar = bottomBar
                )
                Route.Files -> BrowserScreen(
                    browser, onLeave = { nav.route = Route.Home },
                    onViewImages = { paths, i -> paths.getOrNull(i)?.let { ctx.appContainer.lastOpened.record(File(it).toPath()) }; nav.route = Route.Viewer(paths, i, Route.Files) },
                    onEditText = { ctx.appContainer.lastOpened.record(File(it).toPath()); nav.route = Route.Editor(it, false, Route.Files) },
                    onOpenPlayer = { nav.route = Route.Player(Route.Files) },
                    onOpenReader = { ctx.appContainer.lastOpened.record(File(it).toPath()); nav.route = Route.Reader(it, Route.Files) }
                )
                is Route.Media -> MediaScreen(
                    route.tab,
                    onViewImages = { paths, i -> nav.route = Route.Viewer(paths, i, route) },
                    onOpenPlayer = { nav.route = Route.Player(route) },
                    bottomBar = bottomBar
                )
                is Route.Viewer -> ImageViewerScreen(
                    route.paths, route.index,
                    onBack = { nav.route = route.returnTo },
                    onEdit = { path, page ->
                        nav.route = Route.ImageEditor(path, Route.Viewer(route.paths, page, route.returnTo))
                    }
                )
                is Route.Editor -> TextEditorScreen(
                    route.path, route.readOnly, onBack = { nav.route = route.returnTo },
                    onSaved = { route.zipEntry?.let { browser.replaceArchiveEntry(it, route.path) } }
                )
                is Route.ImageEditor -> ImageEditorScreen(
                    route.path,
                    onBack = { nav.route = route.returnTo },
                    onSaved = { nav.route = route.returnTo }
                )
                is Route.Reader -> DocumentReaderScreen(route.path, onBack = { nav.route = route.returnTo })
                is Route.Player -> PlayerScreen(onBack = { nav.route = route.returnTo })
                Route.Settings -> SettingsScreen(
                    browser,
                    onBack = { nav.route = Route.Home },
                    onOpenRecoveryCenter = { nav.route = Route.RecoveryCenter },
                    onOpenOperationHistory = { nav.route = Route.OperationHistory }
                )
                Route.RecoveryCenter -> {
                    // بوابة التنقل (الحماية الأولى فوق نقطة دخول الشاشة): العلم
                    // مطفأ يعني لا مسار للشاشة — رجوع فوري للإعدادات.
                    if (FeatureFlags.recoveryCenter) {
                        RecoveryCenterScreen(onBack = { nav.route = Route.Settings })
                    } else {
                        LaunchedEffect(Unit) { nav.route = Route.Settings }
                    }
                }
                Route.OperationHistory -> {
                    // بوابة التنقل (الحماية الأولى فوق نقطة دخول الشاشة): العلم
                    // مطفأ يعني لا مسار للشاشة — رجوع فوري للإعدادات.
                    if (FeatureFlags.operationHistory) {
                        OperationHistoryScreen(onBack = { nav.route = Route.Settings })
                    } else {
                        LaunchedEffect(Unit) { nav.route = Route.Settings }
                    }
                }
                Route.Recent -> org.openfilemanager.app.ui.screens.RecentScreen(
                    onOpenFile = { f -> nav.openFile(ctx, f.absolutePath, readOnly = false, returnTo = Route.Recent) },
                    bottomBar = bottomBar
                )
            }
        }
        }
        if (showAbout) AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.app_name)) },
            text = { Text(stringResource(R.string.ui_about_body)) },
            confirmButton = { TextButton({ showAbout = false }) { Text(stringResource(R.string.ui_ok)) } }
        )
        ConflictHost()
        RecoveryHost()
    }
}

/** Copy/move name clashes: the operation service waits for this answer (replace / keep both / skip). */
@Composable
private fun ConflictHost() {
    val ctx = LocalContext.current
    val pending by ctx.appContainer.opsCenter.conflict.collectAsStateWithLifecycle()
    val p = pending?.takeIf { !it.deferred.isCompleted } ?: return
    var all by remember(p) { mutableStateOf(false) }
    fun answer(policy: ConflictPolicy) { p.deferred.complete(ConflictDecision(policy, all)) }
    val src = BidiText.wrap(p.request.source.fileName?.toString() ?: p.request.source.toString())
    val dst = BidiText.wrap(p.request.target.fileName?.toString() ?: p.request.target.toString())
    AlertDialog(
        onDismissRequest = { answer(ConflictPolicy.SKIP) },
        title = { Text(stringResource(R.string.file_ops_conflict_title)) },
        text = {
            Column {
                Text(stringResource(R.string.file_ops_conflict_message, src, dst))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(all, { all = it })
                    Text(stringResource(R.string.file_ops_conflict_apply_all))
                }
            }
        },
        confirmButton = {
            Row {
                TextButton({ answer(ConflictPolicy.SKIP) }) { Text(stringResource(R.string.file_ops_conflict_skip)) }
                TextButton({ answer(ConflictPolicy.KEEP_BOTH) }) { Text(stringResource(R.string.file_ops_conflict_keep_both)) }
                TextButton({ answer(ConflictPolicy.REPLACE) }) { Text(stringResource(R.string.file_ops_conflict_replace)) }
            }
        }
    )
}

/** حوار استرداد العمليات المنقطعة — الواجهة التي كانت موعودة بالرسائل وغير موصولة بمحرك الاسترداد. */
@Composable
private fun RecoveryHost() {
    val ctx = LocalContext.current
    val container = ctx.appContainer
    val opsCenter = container.opsCenter
    val recoverable by opsCenter.recoverable.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) opsCenter.requestRecoveryCheck()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val op = recoverable.firstOrNull() ?: return
    // تأجيل المستخدم للسؤال: يخفى الحوار لهذه الجلسة ويبقى قابلاً للاسترداد
    // عند الإقلاع التالي — إلغاء كامل يظل متطلباً زراً صريحاً.
    var postponed by rememberSaveable(op.id) { mutableStateOf(false) }
    if (postponed) return
    val snack = LocalSnack.current
    val failedMessage = stringResource(R.string.ui_recovery_failed)
    var busy by remember(op.id) { mutableStateOf(false) }

    fun act(resume: Boolean) {
        if (busy) return
        busy = true
        val opId = op.id
        // نطاق العملية: القرار يكمل حتى لو أُعيد إنشاء الواجهة منتصفه؛ نافذة
        // «قُتل القرار بين enqueue والخاتمة» كانت تبقي العملية معروضة بلا قرار.
        container.processScope.launch {
            var err: String? = null
            try {
                withContext(Dispatchers.IO) {
                    err = OpRecoveryActions.resolve(container, op, resume) { request ->
                        org.openfilemanager.app.FileOperationService.enqueue(ctx, request)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // قُتل القرار منتصفه: لا نُخفي العملية ولا ندّعي نجاحاً — تبقى
                // قابلة للعرض عند الفحص التالي (ومحرك الاسترداد يرفض تكرارها).
                throw e
            } catch (e: Exception) {
                // استثناء مرمي (I/O سجل مثلاً): يُعرض لا يُبتلع — كان runCatching
                // يحوّله «نجاحاً» صامتاً فيُخفي العملية بلا قرار.
                AppLog.e("ops", "فشل قرار الاسترداد للعملية ${op.id}", e)
                err = e.message ?: e.javaClass.simpleName
            }
            withContext(Dispatchers.Main) {
                if (err != null) {
                    AppLog.e("ops", "فشل قرار الاسترداد للعملية $opId: $err")
                    snack.show(failedMessage, SnackKind.ERROR)
                }
                opsCenter.dismissRecoverable(opId)
                opsCenter.requestRecoveryCheck()
            }
        }
    }

    val kindLabel = stringResource(kindLabelRes(op.kind))
    val src = BidiText.wrap(op.sources.firstOrNull() ?: op.targetDir)
    val dst = BidiText.wrap(op.targetDir.ifEmpty { op.sources.firstOrNull() ?: "" })
    AlertDialog(
        // الإغلاق (رجوع/خارج الحوار) تأجيل لا قرار: العملية تبقى كما هي
        // والسؤال يعود عند الإقلاع التالي — لا إلغاء ولا تنظيف دون زر صريح.
        onDismissRequest = { postponed = true },
        title = { Text(stringResource(R.string.ui_recovery_title)) },
        text = {
            Column {
                Text(stringResource(R.string.ui_recovery_message, kindLabel, src, dst))
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ui_recovery_counts, op.ok, op.failed, op.skipped),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Row {
                TextButton({ act(resume = false) }) {
                    Text(stringResource(if (op.kind == OpKind.RENAME) R.string.ui_recovery_revert_rename else R.string.ui_recovery_cancel))
                }
                TextButton({ act(resume = true) }) {
                    Text(stringResource(if (op.kind == OpKind.RENAME) R.string.ui_recovery_complete_rename else R.string.ui_recovery_resume))
                }
            }
        }
    )
}

/**
 * (Android 13+) طلب إذن الإشعارات مرة واحدة بعد منح الوصول للتخزين: إشعار
 * التشغيل الإعلامي وإشعار التقدم يحتاجانه، والرفض لا يمنع التطبيق (التحكم
 * يبقى داخل الشاشة). الأذونات أدنى من SDK 33 لا تحتاج شيئاً.
 */
@Composable
private fun NotificationPermissionRequest() {
    if (android.os.Build.VERSION.SDK_INT < 33) return
    val ctx = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Asks for All-files access once, with a clear explanation, and re-checks when the user returns from Settings. */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    // اختيار المستخدم المتابعة بوضع محدود: قرار جلسة واحدة لا يُحفظ — بعد منح
    // الصلاحية من الإعدادات ينتقل التطبيق للوضع الكامل تلقائياً (ON_RESUME).
    var limitedChosen by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = Environment.isExternalStorageManager() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (granted || limitedChosen) {
        if (granted) NotificationPermissionRequest()
        content()
    } else {
        Column(
            Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.Storage, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.ui_perm_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ui_perm_body), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button({
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                )
            }) { Text(stringResource(R.string.ui_perm_grant)) }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.ui_perm_limited_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { limitedChosen = true }) {
                Text(stringResource(R.string.ui_perm_limited))
            }
        }
    }
}

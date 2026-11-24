package org.openfilemanager.app.ui

import org.openfilemanager.app.ui.components.SnackKind
import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openfilemanager.app.AppDispatchers
import org.openfilemanager.app.AppLog
import org.openfilemanager.app.BrowserLocation
import org.openfilemanager.app.ExtractOutcome
import org.openfilemanager.app.FileOperationService
import org.openfilemanager.app.OpRequest
import org.openfilemanager.app.R
import org.openfilemanager.app.appContainer
import org.openfilemanager.core.ArchiveInspector
import org.openfilemanager.core.BatchRename
import org.openfilemanager.core.BatchRenameSpec
import org.openfilemanager.core.RenameEngine
import org.openfilemanager.core.RenameInput
import org.openfilemanager.core.RenameJournal
import org.openfilemanager.core.RenameOutcome
import org.openfilemanager.core.RenamePhase
import org.openfilemanager.core.RenameStep
import org.openfilemanager.core.EntryFilter
import org.openfilemanager.core.FileKinds
import org.openfilemanager.core.FileOpEngine
import org.openfilemanager.core.FolderSort
import org.openfilemanager.core.NameRules
import org.openfilemanager.core.PathRedactor
import org.openfilemanager.core.SearchEngine
import org.openfilemanager.core.SafeRename
import org.openfilemanager.core.SortDirectionCore
import org.openfilemanager.core.SortFieldCore
import org.openfilemanager.core.ZipEditException
import org.openfilemanager.core.ZipLoad
import org.openfilemanager.core.ZipModel
import org.openfilemanager.core.ZipRewriter
import org.openfilemanager.core.ZipSource

/**
 * The only bridge between the Compose UI and :core / the existing services.
 * UI code never touches java.io.File listing, engines or services directly:
 * it reads [state] and calls the functions below.
 */
class BrowserViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer
    private val settings = container.settings
    val root: File = Environment.getExternalStorageDirectory()

    private val _state = MutableStateFlow(
        BrowserUiState(Location.Dir(root), showHidden = settings.showHidden, sort = settings.defaultSort, grid = settings.gridView)
    )
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var loadJob: Job? = null

    private var searchJob: Job? = null

    // Edit session of the open ZIP. Lives only while the archive is open; nothing is written until Save.
    private var zipFile: File? = null
    private var zipLoad: ZipLoad? = null
    private var pendingNav: Location? = null
    private val pendingSourceDeletes = ArrayList<String>()

    init {
        load()
        // Any finished copy/move/delete/extract refreshes the listing automatically.
        viewModelScope.launch { container.opsCenter.outcomes.collect { load() } }
        // (F-14) عرض نتائج العمليات والاعتراف عليها انتقلا إلى حلقة العرض في
        // OfmApp: الإقرار كان يحدث هنا قبل التأكد من عرض Snackbar فعلاً فكانت
        // الرسالة تضيع بإعادة إنشاء الواجهة بين القول والإظهار.
        viewModelScope.launch(AppDispatchers.io) {
            runCatching { File(app.cacheDir, "zip_view").deleteRecursively() }
        }
        // (سباق التحميل) القراءة المتزامنة عند البناء كانت تسبق تحميل DataStore
        // أحياناً (preload يمنح سبقاً لا يضمنه) فيفتح المتصفح بالافتراضيات طول
        // عمر ViewModel. بعد اكتمال التحميل تُطبَّق القيم ويُعاد العرض — إلا إن
        // عدّل المستخدم خياراً قبل ذلك فلا تُداس تغييراته بالقيم المرصودة.
        viewModelScope.launch {
            runCatching { settings.ensureLoaded() }
            if (!userAdjustedOptions) {
                _state.update { it.copy(showHidden = settings.showHidden, sort = settings.defaultSort, grid = settings.gridView) }
                load()
            }
        }
    }

    // (F23) vararg Any مفروضة من توقيع getString نفسه (resources قياسي في Android
    // يمررها للتنسيق)؛ لا يمكن تضييقها أكثر من هذا دون تكرار مساعد لكل نوع.
    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)
    private fun say(text: String) { _events.tryEmit(UiEvent.Message(text)) }
    private fun sayErr(text: String) { _events.tryEmit(UiEvent.Message(text, SnackKind.ERROR)) }
    private fun sayInfo(text: String) { _events.tryEmit(UiEvent.Message(text, SnackKind.INFO)) }
    /** نجاح → أخضر، فشل → أحمر. */
    private fun sayResult(ok: Boolean, id: Int) { _events.tryEmit(UiEvent.Message(str(id), if (ok) SnackKind.SUCCESS else SnackKind.ERROR)) }
    /** For UI-side helpers (intents) that need to show a short message. */
    fun notify(text: String) = say(text)

    // ---------- loading ----------

    fun reload() = load()

    private fun load() {
        loadJob?.cancel()
        val snapshot = _state.value
        _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            val result = withContext(AppDispatchers.io) {
                runCatching { listLocation(snapshot.location, snapshot.showHidden, snapshot.sort) }
            }
            result.onSuccess { (items, truncated) ->
                _state.update { cur ->
                    if (cur.location != snapshot.location) cur
                    else {
                        // (المرحلة 5) فحص بقاء التحديد بفهرس O(1) لكل عنصر بدل
                        // items.any (O(n) لكل عنصر محدد) — فرق ملموس في المجلدات
                        // الكبيرة مع تحديد واسع.
                        val ids = items.mapTo(HashSet(items.size)) { it.id }
                        cur.copy(
                            items = items, loading = false, archiveTruncated = truncated,
                            archiveEditable = cur.location is Location.Archive && zipLoad?.model != null,
                            archiveDirty = cur.location is Location.Archive && zipLoad?.model?.dirty == true,
                            selection = cur.selection.filterTo(HashSet()) { id -> id in ids }
                        )
                    }
                }
            }.onFailure {
                _state.update { cur -> cur.copy(items = emptyList(), loading = false) }
                sayErr(str(R.string.ui_err_read_folder))
            }
        }
    }

    private fun listLocation(loc: Location, hidden: Boolean, sort: FolderSort): Pair<List<FileItem>, Boolean> = when (loc) {
        is Location.Dir -> {
            // (F-15) القراءة عبر طبقة التخزين الموحدة StorageRepository لا
            // File.listFiles مباشرة: خصائص واحدة لكل عنصر، وتخطي الروابط
            // الرمزية، وتمييز «تعذر التعداد» عن «المجلد فارغ» (F-11) — الفشل
            // يرمي فيصير رسالة خطأ للمستخدم بدل قائمة فارغة صامتة.
            val entries = container.storage.list(BrowserLocation(loc.dir.toPath()))
                ?: throw java.io.IOException("unreadable directory: ${loc.dir}")
            sorted(entries
                .filter { hidden || !it.name.startsWith(".") }
                .map { f ->
                    FileItem(
                        id = f.path.toString(), name = f.name, isDir = f.isDirectory,
                        size = if (f.isDirectory) -1L else f.size, modified = f.modified,
                        kind = if (f.isDirectory) null else FileKinds.of(f.name), path = f.path.toString()
                    )
                }, sort) to false
        }
        is Location.Archive -> {
            val zip = if (isZip(loc.file)) ensureZip(loc.file) else null
            if (zip != null) {
                sorted(zip.children(loc.inner).map { e ->
                    FileItem(
                        id = (if (e.isDirectory) "d:" else "f:") + e.name, name = e.baseName, isDir = e.isDirectory,
                        size = if (e.isDirectory) -1L else e.size, modified = e.modifiedAt,
                        kind = if (e.isDirectory) null else FileKinds.of(e.baseName), path = null, entry = e.name
                    )
                }, sort) to false
            } else {
                val listing = ArchiveInspector.list(loc.file.toPath())
                val children = LinkedHashMap<String, FileItem>()
                for (e in listing.entries) {
                    if (e.unsafe || e.isLink) continue // never show what the extractor would refuse
                    val name = e.name.trimStart('/')
                    if (!name.startsWith(loc.inner)) continue
                    val rest = name.removePrefix(loc.inner)
                    if (rest.isEmpty()) continue
                    val seg = rest.substringBefore('/')
                    val folder = rest.contains('/') || e.isDirectory
                    val modified = e.modifiedAt.coerceAtLeast(0L)
                    if (folder) {
                        val prefix = loc.inner + seg + "/"
                        children.getOrPut("d:$prefix") {
                            FileItem("d:$prefix", seg, true, -1L, modified, null, null, prefix)
                        }
                    } else {
                        children["f:$name"] = FileItem("f:$name", seg, false, e.size, modified, FileKinds.of(seg), null, name)
                    }
                }
                sorted(children.values.toList(), sort) to listing.truncated
            }
        }
    }

    private fun isZip(f: File) = f.name.endsWith(".zip", ignoreCase = true)

    /** Loads (once per open archive) the editable model; null when the zip must stay read-only. */
    private fun ensureZip(file: File): ZipModel? {
        if (zipFile != file) {
            zipFile = file
            zipLoad = runCatching { ZipModel.load(file.toPath()) }.getOrNull()
        }
        return zipLoad?.model
    }

    private fun sorted(items: List<FileItem>, sort: FolderSort): List<FileItem> {
        val base: Comparator<FileItem> = when (sort.field) {
            SortFieldCore.NAME -> Comparator { a, b -> naturalCompare(a.name, b.name) }
            SortFieldCore.SIZE -> compareBy { it.size }
            SortFieldCore.MODIFIED -> compareBy { it.modified }
            SortFieldCore.TYPE -> Comparator { a, b ->
                val ea = a.name.substringAfterLast('.', "").lowercase()
                val eb = b.name.substringAfterLast('.', "").lowercase()
                ea.compareTo(eb).takeIf { it != 0 } ?: naturalCompare(a.name, b.name)
            }
        }
        val ordered = if (sort.direction == SortDirectionCore.DESCENDING) base.reversed() else base
        return if (settings.foldersFirst) items.sortedWith(compareByDescending<FileItem> { it.isDir }.then(ordered))
        else items.sortedWith(ordered)
    }

    // ---------- navigation ----------

    fun openDir(dir: File) = navigate(Location.Dir(dir))
    fun openArchive(file: File) = navigate(Location.Archive(file, ""))
    fun openInner(prefix: String) {
        val l = _state.value.location as? Location.Archive ?: return
        navigate(Location.Archive(l.file, prefix))
    }

    private fun navigate(loc: Location, force: Boolean = false) {
        val cur = _state.value.location
        val leaving = cur is Location.Archive && !(loc is Location.Archive && loc.file == cur.file)
        if (leaving && !force && zipLoad?.model?.dirty == true) {
            pendingNav = loc
            _state.update { it.copy(askSave = true) }
            return
        }
        if (leaving) { zipFile = null; zipLoad = null; pendingSourceDeletes.clear() }
        searchJob?.cancel()
        _state.update {
            it.copy(location = loc, selection = emptySet(), query = null, items = emptyList(),
                searchHits = null, searchBusy = false, searchTruncated = false, searchErrors = 0, askSave = false,
                archiveEditable = false, archiveDirty = false)
        }
        load()
    }

    /** Back handling in one place. Returns false when already at the top (app should leave the browser). */
    fun navigateUp(): Boolean {
        val s = _state.value
        if (s.selecting) { clearSelection(); return true }
        if (s.query != null) { setQuery(null); return true }
        return when (val l = s.location) {
            is Location.Archive -> {
                if (l.inner.isEmpty()) navigate(Location.Dir(l.file.parentFile ?: root))
                else {
                    val parent = l.inner.trimEnd('/').substringBeforeLast('/', "")
                    navigate(Location.Archive(l.file, if (parent.isEmpty()) "" else "$parent/"))
                }
                true
            }
            is Location.Dir -> {
                val parent = l.dir.parentFile
                if (l.dir == root || parent == null) false else { navigate(Location.Dir(parent)); true }
            }
        }
    }

    // ---------- view options ----------

    /** عدّل المستخدم خيار عرض قبل اكتمال تحميل الإعدادات: قيمه تسود على المرصودة. */
    private var userAdjustedOptions = false

    fun setSort(sort: FolderSort) {
        userAdjustedOptions = true
        settings.defaultSort = sort
        // (تصحيح) الترتيب الجديد يُطبَّق أيضاً على نتائج البحث المعروضة، وإلا بقيت
        // بترتيبها القديم حتى إعادة البحث.
        _state.update {
            it.copy(
                sort = sort,
                items = sorted(it.items, sort),
                searchHits = it.searchHits?.let { h -> sorted(h, sort) }
            )
        }
    }
    fun setGrid(grid: Boolean) {
        userAdjustedOptions = true
        settings.gridView = grid
        _state.update { it.copy(grid = grid) }
    }
    fun toggleHidden() {
        userAdjustedOptions = true
        val next = !_state.value.showHidden
        settings.showHidden = next
        _state.update { it.copy(showHidden = next) }
        load()
    }
    /** null closes search; text searches this folder and everything under it. */
    fun setQuery(q: String?) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        val text = q?.trim().orEmpty()
        val dir = (_state.value.location as? Location.Dir)?.dir
        if (text.isEmpty() || dir == null) {
            _state.update { it.copy(searchHits = null, searchBusy = false, searchTruncated = false, searchErrors = 0) }
            return
        }
        _state.update { it.copy(searchBusy = true) }
        val hidden = _state.value.showHidden
        searchJob = viewModelScope.launch {
            delay(250) // typing debounce
            val report = withContext(AppDispatchers.io) {
                runCatching {
                    SearchEngine().search(dir.toPath(), SearchEngine.namePredicate(text, hidden), isCancelled = { !isActive }, includeHidden = hidden)
                }.getOrNull()
            }
            if (report == null || report.cancelled) { _state.update { it.copy(searchBusy = false) }; return@launch }
            val hits = report.hits.map { h ->
                val f = File(h.dir, h.name)
                FileItem(
                    id = f.absolutePath, name = h.name, isDir = h.isDirectory,
                    size = if (h.isDirectory) -1L else h.size, modified = h.modifiedAt,
                    kind = if (h.isDirectory) null else FileKinds.of(h.name), path = f.absolutePath,
                    parent = File(h.dir).name
                )
            }
            _state.update { cur ->
                if (cur.query?.trim() != text) cur
                else cur.copy(searchHits = sorted(hits, cur.sort), searchBusy = false, searchTruncated = report.truncated, searchErrors = report.errors)
            }
        }
    }

    // ---------- selection ----------

    /** آخر عنصر حدّده المستخدم؛ نقطة بداية تحديد النطاق بالضغط المطوّل. */
    private var selectionAnchor: String? = null

    fun toggleSelect(id: String) {
        _state.update {
            it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id)
        }
        selectionAnchor = if (id in _state.value.selection) id else null
    }

    /**
     * ضغط مطوّل أثناء وجود تحديد: يحدد كل العناصر (ملفات ومجلدات) بين آخر عنصر محدد وهذا العنصر.
     * بلا تحديد سابق يعمل كتبديل عادي.
     */
    fun longSelect(id: String) {
        val st = _state.value
        val anchor = selectionAnchor
        if (!st.selecting || anchor == null || anchor == id) { toggleSelect(id); return }
        val list = st.visibleItems
        val a = list.indexOfFirst { it.id == anchor }
        val b = list.indexOfFirst { it.id == id }
        if (a < 0 || b < 0) { toggleSelect(id); return }
        val range = list.subList(minOf(a, b), maxOf(a, b) + 1).map { it.id }
        _state.update { it.copy(selection = it.selection + range) }
        selectionAnchor = id
    }
    fun selectAll() = _state.update { it.copy(selection = it.visibleItems.mapTo(HashSet()) { i -> i.id }) }
    fun clearSelection() { selectionAnchor = null; _state.update { it.copy(selection = emptySet()) } }

    private fun selectedItems(): List<FileItem> = _state.value.let { s -> s.items.filter { it.id in s.selection } }

    // ---------- operations (routed through the existing operation service) ----------

    fun copySelection(move: Boolean) {
        val loc = _state.value.location
        if (loc is Location.Archive) {
            val names = selectedItems().mapNotNull { it.entry }
            if (names.isEmpty()) return
            _state.update { it.copy(clipboard = Clipboard(names, move, loc.file.absolutePath), selection = emptySet()) }
            return
        }
        val paths = selectedItems().mapNotNull { it.path }
        if (paths.isEmpty()) return
        _state.update { it.copy(clipboard = Clipboard(paths, move), selection = emptySet()) }
    }

    fun cancelClipboard() = _state.update { it.copy(clipboard = null) }

    fun paste() {
        val s = _state.value
        val clip = s.clipboard ?: return
        when (val loc = s.location) {
            is Location.Dir -> if (clip.archive != null) pasteArchiveToDisk(clip, loc.dir) else pasteToDisk()
            is Location.Archive -> pasteIntoArchive(clip, loc)
        }
    }

    private fun pasteToDisk() {
        val s = _state.value
        val clip = s.clipboard ?: return
        val dest = (s.location as? Location.Dir)?.dir ?: return
        val sources = clip.paths.map { File(it).toPath() }
        val request = if (clip.move) OpRequest.Move(sources, dest.toPath()) else OpRequest.Copy(sources, dest.toPath())
        val queued = FileOperationService.enqueue(getApplication(), request)
        if (queued) _state.update { it.copy(clipboard = null) }
        sayResult(queued, if (queued) R.string.ui_op_queued else R.string.ui_op_queue_failed)
    }

    fun deleteSelection() {
        if (_state.value.location is Location.Archive) { archiveDelete(); return }
        val paths = selectedItems().mapNotNull { it.path }.map { File(it).toPath() }
        if (paths.isEmpty()) return
        val queued = FileOperationService.enqueue(getApplication(), OpRequest.Delete(paths))
        if (queued) clearSelection()
        sayResult(queued, if (queued) R.string.ui_op_queued else R.string.ui_op_queue_failed)
    }

    fun rename(item: FileItem, newName: String) {
        if (item.isArchiveEntry) { archiveRename(item, newName); return }
        val path = item.path ?: return
        if (NameRules.problem(newName.trim()) != null) { sayErr(str(R.string.ui_err_bad_name)); return }
        viewModelScope.launch {
            val ok = withContext(AppDispatchers.io) {
                runCatching { SafeRename.rename(File(path).toPath(), newName.trim()) }.isSuccess
            }
            sayResult(ok, if (ok) R.string.ui_renamed else R.string.ui_err_rename)
            load()
        }
    }

    fun defaultArchiveName(): String {
        val s = _state.value
        val one = selectedItems().singleOrNull()
        return (one?.name?.substringBeforeLast('.', one.name)
            ?: (s.location as? Location.Dir)?.dir?.name ?: "archive")
    }

    fun compressSelection(name: String) {
        val s = _state.value
        val dir = (s.location as? Location.Dir)?.dir ?: return
        val sources = selectedItems().mapNotNull { it.path }.map { File(it).toPath() }
        if (sources.isEmpty()) return
        val target = name.trim().let { if (it.endsWith(".zip", ignoreCase = true)) it else "$it.zip" }
        if (NameRules.problem(target) != null) { sayErr(str(R.string.ui_err_bad_name)); return }
        val queued = FileOperationService.enqueue(getApplication(), OpRequest.Compress(sources, dir.toPath(), target))
        if (queued) clearSelection()
        sayResult(queued, if (queued) R.string.ui_op_queued else R.string.ui_op_queue_failed)
    }

    // ---------- bulk rename (one engine for preview and execution: BatchRename.plan) ----------

    /** Last successful on-disk bulk rename, kept so [undoBulkRename] can reverse it. */
    private var lastBulkRename: List<RenameStep>? = null

    /** The selected items in the order currently shown (the numbering follows this order). */
    private fun orderedSelection(): List<FileItem> =
        _state.value.let { s -> s.visibleItems.filter { it.id in s.selection } }

    /** Inputs + existing names for the dialog's preview. Null when the location can't be bulk-renamed. */
    suspend fun bulkRenameContext(): BulkRenameContext? = withContext(AppDispatchers.io) {
        val items = orderedSelection()
        if (items.size < 2) return@withContext null
        when (val loc = _state.value.location) {
            is Location.Dir -> {
                val existing = loc.dir.list()?.toList() ?: return@withContext null
                BulkRenameContext(items.map { RenameInput(it.name, it.isDir) }, existing, strictFat = true)
            }
            is Location.Archive -> {
                val model = zipLoad?.model ?: return@withContext null
                BulkRenameContext(
                    items.map { RenameInput(it.name, it.isDir) },
                    model.children(loc.inner).map { it.baseName }, strictFat = false
                )
            }
        }
    }

    fun bulkRename(spec: BatchRenameSpec) {
        val items = orderedSelection()
        if (items.size < 2) return
        val loc = _state.value.location
        if (loc is Location.Archive) { bulkRenameArchive(items, spec, loc); return }
        val dir = (loc as? Location.Dir)?.dir ?: return
        viewModelScope.launch {
            val attempt = withContext(AppDispatchers.io) {
                runCatching {
                    // Same engine as the preview, re-run against the folder as it is right now.
                    val plan = BatchRename.plan(items.map { RenameInput(it.name, it.isDir) }, spec, dir.list()?.toList().orEmpty())
                    if (!plan.canApply) return@runCatching null
                    val steps = RenameEngine.plan(dir.toPath(), plan.renames())
                    val outcome = RenameEngine.execute(steps) { _, _ -> }
                    when (outcome) {
                        // (خصوصية) أسماء الملفات لا تُدوَّن نصاً صريحاً (تعمية
                        // المسارات لا تغطي اسماً مجرداً بلا مسار): العدد ورمز
                        // المجلد يكفيان للتشخيص دون كشف أسماء المستخدم.
                        is RenameOutcome.Success -> {
                            AppLog.i("rename", "bulk rename ${steps.size} in ${PathRedactor.token(dir.path)}"); steps
                        }
                        is RenameOutcome.Failure -> {
                            AppLog.e("rename", "bulk rename failed in ${PathRedactor.token(dir.path)} (rolledBack=${outcome.rolledBack})", outcome.cause)
                            emptyList<RenameStep>()
                        }
                    }
                }
            }
            val result = attempt.getOrNull()
            when {
                attempt.isFailure -> sayErr(str(R.string.ui_bulk_failed, items.size))
                result == null -> sayErr(str(R.string.ui_bulk_blocked))
                result.isEmpty() -> sayErr(str(R.string.ui_bulk_failed, items.size))
                else -> {
                    lastBulkRename = result
                    _events.tryEmit(UiEvent.RenameDone(str(R.string.ui_renamed_n, result.size, items.size)))
                }
            }
            clearSelection()
            load()
        }
    }

    private fun bulkRenameArchive(items: List<FileItem>, spec: BatchRenameSpec, loc: Location.Archive) {
        val model = zipLoad?.model
        if (model == null) { sayErr(str(R.string.ui_err_readonly_archive)); return }
        viewModelScope.launch {
            val done = withContext(AppDispatchers.io) {
                runCatching {
                    val plan = BatchRename.plan(
                        items.map { RenameInput(it.name, it.isDir) }, spec,
                        model.children(loc.inner).map { it.baseName }, strictFat = false
                    )
                    if (!plan.canApply) return@runCatching -1
                    val entryOf = items.associate { it.name to it.entry }
                    // اسم في الخطة بلا مدخل أرشيف مقابل = خطة غير صالحة: رسالة واضحة بدل انهيار.
                    val resolved = plan.renames().map { (old, new) -> (entryOf[old] ?: return@runCatching -1) to new }
                    model.renameBatch(resolved)
                }
            }
            val n = done.getOrNull()
            when {
                n == null -> sayErr(editErrorText(done.exceptionOrNull() ?: IllegalStateException()))
                n < 0 -> sayErr(str(R.string.ui_bulk_blocked))
                else -> say(str(R.string.ui_renamed_n, n, items.size))
            }
            clearSelection()
            load()
        }
    }

    /** Reverses the last on-disk bulk rename (two-phase, never overwriting). */
    fun undoBulkRename() {
        val steps = lastBulkRename ?: return
        lastBulkRename = null
        viewModelScope.launch {
            val ok = withContext(AppDispatchers.io) {
                RenameEngine.execute(RenameEngine.undoSteps(steps)) is RenameOutcome.Success
            }
            sayResult(ok, if (ok) R.string.ui_undone else R.string.ui_err_rename)
            load()
        }
    }

    /** Extract one entry to cache (never next to the archive) and ask the UI to open it. */
    fun openArchiveEntry(item: FileItem) {
        val loc = _state.value.location as? Location.Archive ?: return
        val entry = item.entry ?: return
        sayInfo(str(R.string.ui_extracting))
        val model = zipLoad?.model
        viewModelScope.launch {
            val file = withContext(AppDispatchers.io) {
                val dest = File(getApplication<Application>().cacheDir, "zip_view/${System.nanoTime()}").also { it.mkdirs() }
                if (model != null) {
                    runCatching {
                        val target = File(dest, item.name)
                        when (val src = model.get(entry)?.source) {
                            is ZipSource.Original -> ZipRewriter.extractOne(model.archive, src.name, target.toPath())
                            is ZipSource.External -> java.nio.file.Files.copy(src.path, target.toPath())
                            else -> return@runCatching null
                        }
                        target
                    }.getOrNull()
                } else {
                    val r = container.archiveService.extract(loc.file, dest, filter = EntryFilter.only(setOf(entry)))
                    if (r is ExtractOutcome.Done) {
                        File(r.folder, entry).takeIf { it.isFile }
                            ?: r.folder.walkTopDown().firstOrNull { it.isFile && it.name == item.name }
                    } else null
                }
            }
            if (file != null) _events.tryEmit(UiEvent.OpenExtracted(file.absolutePath, if (model != null) entry else null))
            else sayErr(str(R.string.ui_err_open_entry))
        }
    }

    // ---------- editing the open ZIP (all in memory until saved) ----------

    private fun editErrorText(t: Throwable): String = str(
        when ((t as? ZipEditException)?.reason) {
            ZipEditException.Reason.EXISTS -> R.string.ui_err_exists
            ZipEditException.Reason.INVALID_NAME -> R.string.ui_err_bad_name
            ZipEditException.Reason.INTO_ITSELF -> R.string.ui_err_into_itself
            else -> R.string.ui_err_edit
        }
    )

    private fun editArchive(block: (ZipModel) -> Unit) {
        val model = zipLoad?.model ?: return
        viewModelScope.launch {
            val err = withContext(AppDispatchers.io) { runCatching { block(model) }.exceptionOrNull() }
            if (err != null) sayErr(editErrorText(err))
            _state.update { it.copy(selection = emptySet()) }
            load()
        }
    }

    fun archiveDelete() {
        val names = selectedItems().mapNotNull { it.entry }
        if (names.isNotEmpty()) editArchive { it.delete(names) }
    }

    fun archiveRename(item: FileItem, newName: String) {
        val entry = item.entry ?: return
        editArchive { it.rename(entry, newName) }
    }

    /** Creates an empty file or a folder in the current location (a normal folder or an open ZIP). */
    fun createItem(rawName: String, folder: Boolean) {
        val name = rawName.trim()
        when (val loc = _state.value.location) {
            is Location.Dir -> {
                if (NameRules.problem(name, strictFat = true) != null) { sayErr(str(R.string.ui_err_bad_name)); return }
                viewModelScope.launch {
                    val failure = withContext(AppDispatchers.io) {
                        runCatching {
                            val target = File(loc.dir, name)
                            if (target.exists()) return@runCatching R.string.ui_err_exists
                            val ok = if (folder) target.mkdir() else target.createNewFile()
                            if (ok) null else R.string.ui_err_create
                        }.getOrDefault(R.string.ui_err_create)
                    }
                    sayResult(failure == null, failure ?: R.string.ui_created)
                    load()
                }
            }
            is Location.Archive -> {
                if (folder) { archiveMkdir(name); return }
                if (NameRules.problem(name, strictFat = false) != null) { sayErr(str(R.string.ui_err_bad_name)); return }
                // The empty file must stay on disk until the archive is saved (the model reads it then).
                editArchive { m ->
                    val dir = File(getApplication<Application>().cacheDir, "zip_view/new/${System.nanoTime()}")
                    dir.mkdirs()
                    val f = File(dir, name)
                    f.createNewFile()
                    m.add(loc.inner, f.toPath())
                }
            }
        }
    }

    fun archiveMkdir(name: String) {
        val inner = (_state.value.location as? Location.Archive)?.inner ?: return
        editArchive { it.mkdir(inner, name) }
    }

    /** The user saved an edited entry from the text editor: swap its content in the model. */
    fun replaceArchiveEntry(entry: String, edited: String) {
        editArchive { it.replaceFile(entry, File(edited).toPath()) }
        say(str(R.string.ui_entry_updated))
    }

    private fun pasteIntoArchive(clip: Clipboard, loc: Location.Archive) {
        if (zipLoad?.model == null) { sayErr(str(R.string.ui_err_readonly_archive)); return }
        if (clip.archive != null) {
            if (File(clip.archive) != loc.file) { sayErr(str(R.string.ui_err_other_archive)); return }
            editArchive { it.transfer(clip.paths, loc.inner, copy = !clip.move) }
        } else {
            editArchive { m -> clip.paths.forEach { m.add(loc.inner, File(it).toPath()) } }
            if (clip.move) pendingSourceDeletes += clip.paths // originals are removed only after a successful save
        }
        _state.update { it.copy(clipboard = null) }
    }

    /**
     * نسخ/نقل عناصر من أرشيف إلى مجلد على القرص. الاستخراج يمر عبر خدمة العمليات
     * (F7: تقدم من الإشعار، إلغاء، سجل واسترداد، وسياسة تعارض موحّدة)؛ خطوة
     * «النقل» الإضافية — حذف العناصر من ملف الأرشيف بعد نجاح الاستخراج — تُنفَّذ
     * في نطاق الحاوية الذي يعيش مع العملية، لا viewModelScope الذي يُقتل مع
     * مغادرة الشاشة فتنقطع إعادة كتابة الأرشيف في منتصفها.
     *
     * حدود الخطوة (موثّقة بصدق): حذف العناصر من الأرشيف يعيش مع العملية فقط —
     * قتل العملية قبل اكتماله يترك الأرشيف كاملًا والعناصر منسوخة (وضع فشل
     * آمن: لا يضيع ملف)، ويمكن إعادته بلصق «نقل» جديد. الاشتراك في النتائج
     * يسبق الجدولة عمداً: outcomes بلا replay، ولو انبعثت النتيجة قبل الاشتراك
     * فوُتت، فنضمن الترتيب: مشغّل المُجمّع ثم enqueue. (تحديث) انتظار النتيجة
     * محدود بمهلة: موت الخدمة قبل بث النتيجة كان يعلّق الكوروتين إلى الأبد.
     */
    private fun pasteArchiveToDisk(clip: Clipboard, dest: File) {
        val archive = File(clip.archive ?: return)
        val opId = FileOpEngine.newOpId()
        if (clip.move) {
            // «نقل»: اشتراك أولًا، ثم حذف العناصر من ملف الأرشيف بعد نجاح الاستخراج.
            container.processScope.launch {
                val outcome = kotlinx.coroutines.withTimeoutOrNull(OUTCOME_WAIT_MS) {
                    container.opsCenter.outcomes
                        .filter { it.opId == opId && it.failed == 0 && it.ok > 0 }
                        .first()
                }
                if (outcome == null) {
                    AppLog.i("archive", "لم تُرصد نتيجة الاستخراج $opId (مهلة/موت الخدمة)؛ خطوة حذف العناصر من الأرشيف تُترك للمستخدم")
                    return@launch
                }
                val err = withContext(AppDispatchers.io) {
                    runCatching {
                        val m = ZipModel.load(archive.toPath()).model
                            ?: throw ZipEditException(ZipEditException.Reason.UNSUPPORTED, "read-only archive")
                        m.delete(clip.paths)
                        ZipRewriter.write(m)
                    }.exceptionOrNull()
                }
                if (err != null) AppLog.e("archive", "تعذر حذف العناصر المنقولة من الأرشيف", err)
                load()
            }
        }
        val queued = FileOperationService.enqueue(
            getApplication(),
            OpRequest.Extract(archive.toPath(), dest.toPath(), Charsets.UTF_8.name(), clip.paths.toList(), opId, placeDirectly = true)
        )
        if (!queued) return
        _state.update { it.copy(clipboard = null) }
        sayResult(queued, if (queued) R.string.ui_op_queued else R.string.ui_op_queue_failed)
    }

    /** Writes the edits into the archive (temp file, verify, atomic replace), then continues any pending navigation. */
    fun saveArchive() {
        val model = zipLoad?.model ?: return
        _state.update { it.copy(saving = true, askSave = false) }
        sayInfo(str(R.string.ui_saving))
        viewModelScope.launch {
            val err = withContext(AppDispatchers.io) { runCatching { ZipRewriter.write(model) }.exceptionOrNull() }
            if (err == null) {
                zipLoad = runCatching { ZipModel.load(model.archive) }.getOrNull()
                val gone = pendingSourceDeletes.map { File(it).toPath() }
                pendingSourceDeletes.clear()
                if (gone.isNotEmpty()) FileOperationService.enqueue(getApplication(), OpRequest.Delete(gone))
                say(str(R.string.ui_archive_saved))
            } else {
                AppLog.e("archive", "تعذر حفظ الأرشيف", err)
                val reason = err.message?.takeIf { it.isNotBlank() } ?: err.javaClass.simpleName
                sayErr(str(R.string.ui_err_archive_save_reason, reason))
            }
            _state.update { it.copy(saving = false) }
            val next = pendingNav
            pendingNav = null
            if (err == null && next != null) navigate(next, force = true) else load()
        }
    }

    fun discardArchiveChanges() {
        val next = pendingNav
        pendingNav = null
        zipFile = null; zipLoad = null
        pendingSourceDeletes.clear()
        _state.update { it.copy(askSave = false, clipboard = it.clipboard?.takeIf { c -> c.archive == null }) }
        if (next != null) navigate(next, force = true) else load()
    }

    fun cancelAskSave() {
        pendingNav = null
        _state.update { it.copy(askSave = false) }
    }

    /**
     * استخراج الأرشيف المحدد (كله أو العناصر المحددة) بجواره — عبر خدمة العمليات
     * (F7): تقدم من الإشعار، إلغاء فوري، سجل واسترداد بعد قتل العملية، وسياسة
     * تعارض موحّدة، بدل viewModelScope الذي يُقتل مع مغادرة الشاشة.
     */
    fun extractHere(archive: File, entries: Set<String>?) {
        val dest = archive.parentFile ?: return
        val selected = entries?.toList()
        val queued = FileOperationService.enqueue(
            getApplication(), OpRequest.Extract(archive.toPath(), dest.toPath(), Charsets.UTF_8.name(), selected)
        )
        if (queued) clearSelection()
        sayResult(queued, if (queued) R.string.ui_op_queued else R.string.ui_op_queue_failed)
    }

    fun selectedArchiveEntries(): Set<String> =
        selectedItems().mapNotNull { it.entry }.toSet()
}

/** "2.jpg" < "10.jpg": digit runs compare as numbers, everything else case-insensitively. */
internal fun naturalCompare(a: String, b: String): Int {
    var i = 0; var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]; val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ie = i; while (ie < a.length && a[ie].isDigit()) ie++
            var je = j; while (je < b.length && b[je].isDigit()) je++
            val na = a.substring(i, ie).trimStart('0'); val nb = b.substring(j, je).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = ie; j = je
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++; j++
        }
    }
    return (a.length - i) - (b.length - j)
}

/** مهلة انتظار نتيجة عملية وُعدت من الخدمة (خطوة «النقل» من الأرشيف). */
private const val OUTCOME_WAIT_MS = 20L * 60 * 1000

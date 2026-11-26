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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openfilemanager.app.ArchiveRejections
import org.openfilemanager.app.AppDispatchers
import org.openfilemanager.app.BrowserLocation
import org.openfilemanager.app.FeatureFlags
import org.openfilemanager.app.FileOperationService
import org.openfilemanager.app.OpRequest
import org.openfilemanager.app.R
import org.openfilemanager.app.appContainer
import org.openfilemanager.app.usecase.RenameCoordinator
import org.openfilemanager.app.usecase.ZipItemTransfer
import org.openfilemanager.core.ArchiveInspector
import org.openfilemanager.core.BatchRename
import org.openfilemanager.core.BatchRenameSpec
import org.openfilemanager.core.FileKinds
import org.openfilemanager.core.FolderSort
import org.openfilemanager.core.NameRules
import org.openfilemanager.core.SearchEngine
import org.openfilemanager.core.SafeRename
import org.openfilemanager.core.ZipLoad
import org.openfilemanager.core.ZipModel

/**
 * The only bridge between the Compose UI and :core / the existing services.
 * UI code never touches java.io.File listing, engines or services directly:
 * it reads [state] and calls the functions below.
 */
class BrowserViewModel(app: Application) : AndroidViewModel(app), ArchiveBrowserController.Host {
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

    // منسق عمليات الأرشيف — كان ~200 سطر مدمجاً هنا.
    // zipTransfer يُعرَّف قبل archiveCtl لأن الأخير يستعمله في التهيئة.
    private val zipTransfer = org.openfilemanager.app.usecase.ZipItemTransfer()
    private val archiveCtl: ArchiveBrowserController = ArchiveBrowserController(
        appContext = app,
        scope = viewModelScope,
        processScope = container.processScope,
        opsCenter = container.opsCenter,
        archiveService = container.archiveService,
        zipTransfer = zipTransfer,
        host = this
    )
    // منسق التسمية الجماعية — كان ~110 سطر مدمجاً هنا.
    private val bulkRenameCtl: BulkRenameController by lazy {
        BulkRenameController(viewModelScope, RenameCoordinator(container.opsCenter.journal), this)
    }

    // ---- ArchiveBrowserController.Host implementation ----
    override fun archiveState() = ArchiveBrowserController.ArchiveState(zipFile, zipLoad, pendingNav, pendingSourceDeletes.toList())
    override fun setZipLoad(value: ZipLoad?) { zipLoad = value }
    override fun setZipFile(value: File?) { zipFile = value }
    override fun setPendingNav(value: Location?) { pendingNav = value }
    override fun setPendingSourceDeletes(value: List<String>) {
        pendingSourceDeletes.clear()
        pendingSourceDeletes.addAll(value)
    }
    override fun updateState(transform: (BrowserUiState) -> BrowserUiState) { _state.update(transform) }
    override fun emit(event: UiEvent) { _events.tryEmit(event) }
    override fun say(text: String) { _events.tryEmit(UiEvent.Message(text)) }
    override fun sayErr(text: String) { _events.tryEmit(UiEvent.Message(text, SnackKind.ERROR)) }
    override fun sayInfo(text: String) { _events.tryEmit(UiEvent.Message(text, SnackKind.INFO)) }
    override fun sayResult(ok: Boolean, resId: Int) { _events.tryEmit(UiEvent.Message(str(resId), if (ok) SnackKind.SUCCESS else SnackKind.ERROR)) }
    override fun str(resId: Int, vararg args: Any): String = getApplication<Application>().getString(resId, *args)
    override fun reloadListing() = load()
    override fun navigateTo(location: Location, force: Boolean) = navigate(location, force)
    override fun clearSelection() { selectionAnchor = null; _state.update { it.copy(selection = emptySet()) } }
    override fun appContext(): android.content.Context = getApplication()

    init {
        load()
        // Any finished copy/move/delete/extract refreshes the listing automatically.
        viewModelScope.launch { container.opsCenter.outcomes.collect { load() } }
        // عرض نتائج العمليات والاعتراف عليها انتقلا إلى حلقة العرض في
        // OfmApp: الإقرار كان يحدث هنا قبل التأكد من عرض Snackbar فعلاً فكانت
        // الرسالة تضيع بإعادة إنشاء الواجهة بين القول والإظهار.
        viewModelScope.launch(AppDispatchers.io) {
            runCatching { File(app.cacheDir, "zip_view").deleteRecursively() }
        }
        // (سباق التحميل) القراءة المتزامنة عند البناء كانت تسبق تحميل DataStore أحياناً (preload يمنح سبقاً لا يضمنه) فيفتح المتصفح بالافتراضيات طول عمر…
        viewModelScope.launch {
            runCatching { settings.ensureLoaded() }
            if (!userAdjustedOptions) {
                _state.update { it.copy(showHidden = settings.showHidden, sort = settings.defaultSort, grid = settings.gridView) }
                load()
            }
        }
    }

    // str/say/sayErr/sayInfo/sayResult أصبحت override للـ Host interface —
    // يستعملها ArchiveBrowserController عبر host.* بدل تكرارها. والـ ViewModel نفسه
    // يبقى يستعملها محلياً بنفس الأسماء.
    /** نجاح → أخضر، فشل → أحمر. */
    /**
     * Queues [request] with the operation service. The request file is written with fsync, so the
     * blocking part runs on the IO dispatcher; [onQueued] runs on the caller's (main) context.
     */
    private fun enqueueAsync(request: OpRequest, onQueued: () -> Unit = {}) {
        viewModelScope.launch {
            val queued = withContext(AppDispatchers.io) { FileOperationService.enqueue(getApplication(), request) }
            if (queued) onQueued()
            sayResult(queued, if (queued) R.string.ui_op_queued else R.string.ui_op_queue_failed)
        }
    }

    // sayResult أصبحت override للـ Host interface؛ لا حاجة لنسخة خاصة.
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
                val specific = if (FeatureFlags.archiveRejectionFeedback) ArchiveRejections.reasonOf(it) else null
                sayErr(str(specific?.let(ArchiveRejections::messageRes) ?: R.string.ui_err_read_folder))
            }
        }
    }

    private fun listLocation(loc: Location, hidden: Boolean, sort: FolderSort): Pair<List<FileItem>, Boolean> = when (loc) {
        is Location.Dir -> {
            // القراءة عبر طبقة التخزين الموحدة StorageRepository لا File.listFiles مباشرة: خصائص واحدة لكل عنصر، وتخطي الروابط الرمزية، وتمييز «تعذر التعداد» عن…
            val entries = container.storage.list(BrowserLocation(loc.dir.toPath()))
                ?: throw java.io.IOException("unreadable directory: ${loc.dir}")
            sortedFileItems(entries
                .filter { hidden || !it.name.startsWith(".") }
                .map { f ->
                    FileItem(
                        id = f.path.toString(), name = f.name, isDir = f.isDirectory,
                        size = if (f.isDirectory) -1L else f.size, modified = f.modified,
                        kind = if (f.isDirectory) null else FileKinds.of(f.name), path = f.path.toString()
                    )
                }, sort, settings.foldersFirst) to false
        }
        is Location.Archive -> {
            val zip = if (isZip(loc.file)) ensureZip(loc.file) else null
            if (zip != null) {
                sortedFileItems(zip.children(loc.inner).map { e ->
                    FileItem(
                        id = (if (e.isDirectory) "d:" else "f:") + e.name, name = e.baseName, isDir = e.isDirectory,
                        size = if (e.isDirectory) -1L else e.size, modified = e.modifiedAt,
                        kind = if (e.isDirectory) null else FileKinds.of(e.baseName), path = null, entry = e.name
                    )
                }, sort, settings.foldersFirst) to false
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
                sortedFileItems(children.values.toList(), sort, settings.foldersFirst) to listing.truncated
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
                val isStorageRoot = l.dir == root || runCatching {
                    container.storage.roots().any { it.path.toFile() == l.dir }
                }.getOrDefault(false)
                if (isStorageRoot || parent == null) false else { navigate(Location.Dir(parent)); true }
            }
        }
    }

    // ---------- view options ----------

    /** عدّل المستخدم خيار عرض قبل اكتمال تحميل الإعدادات: قيمه تسود على المرصودة. */
    private var userAdjustedOptions = false

    fun setSort(sort: FolderSort) {
        userAdjustedOptions = true
        settings.defaultSort = sort
        // الترتيب الجديد يُطبَّق أيضاً على نتائج البحث المعروضة، وإلا بقيت
        // بترتيبها القديم حتى إعادة البحث.
        _state.update {
            it.copy(
                sort = sort,
                items = sortedFileItems(it.items, sort, settings.foldersFirst),
                searchHits = it.searchHits?.let { h -> sortedFileItems(h, sort, settings.foldersFirst) }
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
                else cur.copy(searchHits = sortedFileItems(hits, cur.sort, settings.foldersFirst), searchBusy = false, searchTruncated = report.truncated, searchErrors = report.errors)
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
    // clearSelection انتقل إلى override fun clearSelection() في قسم Host أعلاه.

    private fun selectedItems(): List<FileItem> = _state.value.let { s -> s.visibleItems.filter { it.id in s.selection } }

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
        enqueueAsync(request) { _state.update { it.copy(clipboard = null) } }
    }

    fun deleteSelection() {
        if (_state.value.location is Location.Archive) { archiveDelete(); return }
        val paths = selectedItems().mapNotNull { it.path }.map { File(it).toPath() }
        if (paths.isEmpty()) return
        enqueueAsync(OpRequest.Delete(paths)) { clearSelection() }
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
        enqueueAsync(OpRequest.Compress(sources, dir.toPath(), target)) { clearSelection() }
    }

    // ---------- bulk rename (one engine for preview and execution: BatchRename.plan) ----------
    // كل عمليات التسمية الجماعية مفوّضة إلى BulkRenameController — كانت ~110 سطر هنا.

    // zipTransfer انتقل إلى أعلى (قبل archiveCtl) لتفادي مشكلة التهيئة.

    /** The selected items in the order currently shown (the numbering follows this order). */
    private fun orderedSelection(): List<FileItem> =
        _state.value.let { s -> s.visibleItems.filter { it.id in s.selection } }

    /** Inputs + existing names for the dialog's preview. Null when the location can't be bulk-renamed. */
    suspend fun bulkRenameContext(): BulkRenameContext? {
        return bulkRenameCtl.bulkRenameContext(orderedSelection(), _state.value.location, zipLoad?.model)
    }

    fun bulkRename(spec: BatchRenameSpec) {
        bulkRenameCtl.bulkRename(orderedSelection(), spec, _state.value.location, zipLoad?.model)
    }

    /** Reverses the last on-disk bulk rename (two-phase, never overwriting),
     *  journalled like the forward path so an abrupt kill mid-undo is recoverable (E1). */
    fun undoBulkRename() {
        bulkRenameCtl.undoBulkRename()
    }

    /** Extract one entry to cache (never next to the archive) and ask the UI to open it. */
    fun openArchiveEntry(item: FileItem) {
        val loc = _state.value.location as? Location.Archive ?: return
        archiveCtl.openArchiveEntry(item, loc)
    }

    // ---------- editing the open ZIP (all in memory until saved) ----------
    // كل عمليات الأرشيف مفوّضة إلى ArchiveBrowserController — كانت ~200 سطر هنا.

    fun archiveDelete() {
        val names = selectedItems().mapNotNull { it.entry }
        archiveCtl.archiveDelete(names)
    }

    fun archiveRename(item: FileItem, newName: String) {
        val entry = item.entry ?: return
        archiveCtl.archiveRename(entry, newName)
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
                val inner = loc.inner
                archiveCtl.editArchive { m ->
                    val dir = File(getApplication<Application>().cacheDir, "zip_view/new/${System.nanoTime()}")
                    dir.mkdirs()
                    val f = File(dir, name)
                    f.createNewFile()
                    m.add(inner, f.toPath())
                }
            }
        }
    }

    fun archiveMkdir(name: String) {
        val inner = (_state.value.location as? Location.Archive)?.inner ?: return
        archiveCtl.archiveMkdir(inner, name)
    }

    /** The user saved an edited entry from the text editor: swap its content in the model. */
    fun replaceArchiveEntry(entry: String, edited: String) {
        archiveCtl.replaceArchiveEntry(entry, edited)
    }

    private fun pasteIntoArchive(clip: Clipboard, loc: Location.Archive) {
        archiveCtl.pasteIntoArchive(clip, loc)
    }

    private fun pasteArchiveToDisk(clip: Clipboard, dest: File) {
        archiveCtl.pasteArchiveToDisk(clip, dest)
    }

    /** Writes the edits into the archive (temp file, verify, atomic replace), then continues any pending navigation. */
    fun saveArchive() {
        archiveCtl.saveArchive()
    }

    fun discardArchiveChanges() {
        archiveCtl.discardArchiveChanges()
    }

    fun cancelAskSave() {
        archiveCtl.cancelAskSave()
    }

    fun extractHere(archive: File, entries: Set<String>?) {
        archiveCtl.extractHere(archive, entries)
    }

    fun selectedArchiveEntries(): Set<String> =
        selectedItems().mapNotNull { it.entry }.toSet()
}

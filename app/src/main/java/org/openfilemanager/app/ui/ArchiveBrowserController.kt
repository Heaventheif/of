package org.openfilemanager.app.ui

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openfilemanager.app.AppDispatchers
import org.openfilemanager.app.AppLog
import org.openfilemanager.app.ArchiveService
import org.openfilemanager.app.ExtractOutcome
import org.openfilemanager.app.FileOperationService
import org.openfilemanager.app.OpRequest
import org.openfilemanager.app.OpsCenter
import org.openfilemanager.app.R
import org.openfilemanager.app.usecase.ZipItemTransfer
import org.openfilemanager.core.EntryFilter
import org.openfilemanager.core.FileOpEngine
import org.openfilemanager.core.ZipEditException
import org.openfilemanager.core.ZipLoad
import org.openfilemanager.core.ZipModel
import org.openfilemanager.core.ZipRewriter

/** منسق عمليات الأرشيف داخل المتصفح. */
class ArchiveBrowserController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val processScope: CoroutineScope,
    private val opsCenter: OpsCenter,
    private val archiveService: ArchiveService,
    private val zipTransfer: ZipItemTransfer,
    private val host: Host
) {
    /**
     * واجهة مضيف ArchiveBrowserController. ترث [BrowserHost] المشتركة
     * وتضيف وصولاً خاصاً بالأرشيف (حالة ZIP، طلبات معلقة). هذا يفصل Controller
     * عن BulkRenameController — كلاهما يستخدم BrowserHost لكن لكل واحد احتياجاته الخاصة.
     */
    interface Host : BrowserHost {
        fun archiveState(): ArchiveState
        fun setZipLoad(value: ZipLoad?)
        fun setZipFile(value: File?)
        fun setPendingNav(value: Location?)
        fun setPendingSourceDeletes(value: List<String>)
    }

    /** لقطة من حالة الأرشيف يملكها ViewModel. */
    data class ArchiveState(
        val zipFile: File?,
        val zipLoad: ZipLoad?,
        val pendingNav: Location?,
        val pendingSourceDeletes: List<String>
    )

    // ---- فتح مدخل للعرض ----

    fun openArchiveEntry(item: FileItem, location: Location.Archive) {
        val entry = item.entry ?: return
        host.sayInfo(host.str(R.string.ui_extracting))
        val model = host.archiveState().zipLoad?.model
        scope.launch {
            val file = withContext(AppDispatchers.io) {
                val dest = File(appContext.cacheDir, "zip_view/${System.nanoTime()}").also { it.mkdirs() }
                if (model != null) {
                    runCatching { zipTransfer.extractEntryForView(model, entry, item.name, dest) }.getOrNull()
                } else {
                    val r = archiveService.extract(location.file, dest, filter = EntryFilter.only(setOf(entry)))
                    if (r is ExtractOutcome.Done) {
                        File(r.folder, entry).takeIf { it.isFile }
                            ?: r.folder.walkTopDown().firstOrNull { it.isFile && it.name == item.name }
                    } else null
                }
            }
            if (file != null) host.emit(UiEvent.OpenExtracted(file.absolutePath, if (model != null) entry else null))
            else host.sayErr(host.str(R.string.ui_err_open_entry))
        }
    }

    // ---- تعديل النموذج ----

    fun editErrorText(t: Throwable): String = host.str(
        when ((t as? ZipEditException)?.reason) {
            ZipEditException.Reason.EXISTS -> R.string.ui_err_exists
            ZipEditException.Reason.INVALID_NAME -> R.string.ui_err_bad_name
            ZipEditException.Reason.INTO_ITSELF -> R.string.ui_err_into_itself
            else -> R.string.ui_err_edit
        }
    )

    fun editArchive(block: (ZipModel) -> Unit) {
        val model = host.archiveState().zipLoad?.model ?: return
        scope.launch {
            val err = withContext(AppDispatchers.io) { runCatching { block(model) }.exceptionOrNull() }
            if (err != null) host.sayErr(editErrorText(err))
            host.updateState { it.copy(selection = emptySet()) }
            host.reloadListing()
        }
    }

    fun archiveDelete(selectedEntries: List<String>) {
        if (selectedEntries.isNotEmpty()) editArchive { it.delete(selectedEntries) }
    }

    fun archiveRename(entry: String, newName: String) {
        editArchive { it.rename(entry, newName) }
    }

    fun archiveMkdir(inner: String, name: String) {
        editArchive { it.mkdir(inner, name) }
    }

    fun replaceArchiveEntry(entry: String, edited: String) {
        editArchive { it.replaceFile(entry, File(edited).toPath()) }
        host.say(host.str(R.string.ui_entry_updated))
    }

    fun pasteIntoArchive(clip: Clipboard, loc: Location.Archive) {
        val st = host.archiveState()
        val decision = zipTransfer.planPaste(
            model = st.zipLoad?.model, currentArchive = loc.file,
            sourceArchive = clip.archive, paths = clip.paths, into = loc.inner, move = clip.move
        )
        when (decision) {
            ZipItemTransfer.PasteDecision.NoEditableModel -> { host.sayErr(host.str(R.string.ui_err_readonly_archive)); return }
            ZipItemTransfer.PasteDecision.FromOtherArchive -> { host.sayErr(host.str(R.string.ui_err_other_archive)); return }
            is ZipItemTransfer.PasteDecision.Paste -> {
                editArchive { m -> zipTransfer.execute(decision.op, m) }
                if (decision.pendingSourceDeletes) {
                    host.setPendingSourceDeletes(st.pendingSourceDeletes + clip.paths)
                }
            }
        }
        host.updateState { it.copy(clipboard = null) }
    }

    // ---- الحفظ / التخلي عن التعديلات ----

    fun saveArchive() {
        val model = host.archiveState().zipLoad?.model ?: return
        host.updateState { it.copy(saving = true, askSave = false) }
        host.sayInfo(host.str(R.string.ui_saving))
        scope.launch {
            val err = withContext(AppDispatchers.io) { runCatching { ZipRewriter.write(model) }.exceptionOrNull() }
            if (err == null) {
                host.setZipLoad(runCatching { ZipModel.load(model.archive) }.getOrNull())
                val gone = host.archiveState().pendingSourceDeletes.map { File(it).toPath() }
                host.setPendingSourceDeletes(emptyList())
                if (gone.isNotEmpty()) FileOperationService.enqueueOnIo(appContext, OpRequest.Delete(gone))
                host.say(host.str(R.string.ui_archive_saved))
            } else {
                AppLog.e("archive", "تعذر حفظ الأرشيف", err)
                // err.message قد يكون رمز CoreOpCode — نمرّره عبر المترجم.
                val rawReason = err.message?.takeIf { it.isNotBlank() } ?: err.javaClass.simpleName
                val reason = org.openfilemanager.app.CoreMessageResolver.resolve(host.appContext(), rawReason)
                host.sayErr(host.str(R.string.ui_err_archive_save_reason, reason))
            }
            host.updateState { it.copy(saving = false) }
            val next = host.archiveState().pendingNav
            host.setPendingNav(null)
            if (err == null && next != null) host.navigateTo(next, true) else host.reloadListing()
        }
    }

    fun discardArchiveChanges() {
        val next = host.archiveState().pendingNav
        host.setPendingNav(null)
        host.setZipFile(null); host.setZipLoad(null)
        host.setPendingSourceDeletes(emptyList())
        host.updateState { it.copy(askSave = false, clipboard = it.clipboard?.takeIf { c -> c.archive == null }) }
        if (next != null) host.navigateTo(next, true) else host.reloadListing()
    }

    fun cancelAskSave() {
        host.setPendingNav(null)
        host.updateState { it.copy(askSave = false) }
    }

    fun extractHere(archive: File, entries: Set<String>?) {
        val dest = archive.parentFile ?: return
        val selected = entries?.toList()
        scope.launch {
            val queued = withContext(AppDispatchers.io) {
                FileOperationService.enqueue(appContext, OpRequest.Extract(archive.toPath(), dest.toPath(), Charsets.UTF_8.name(), selected))
            }
            if (queued) {
                host.clearSelection()
                host.sayResult(queued, if (queued) R.string.ui_op_queued else R.string.ui_op_queue_failed)
            }
        }
    }

    fun pasteArchiveToDisk(clip: Clipboard, dest: File) {
        val archive = File(clip.archive ?: return)
        val opId = FileOpEngine.newOpId()
        if (clip.move) {
            // «نقل»: اشتراك أولاً، ثم حذف العناصر من الأرشيف بعد نجاح الاستخراج.
            processScope.launch {
                val outcome = kotlinx.coroutines.withTimeoutOrNull(OUTCOME_WAIT_MS) {
                    opsCenter.outcomes
                        .filter { it.opId == opId && it.failed == 0 && it.ok > 0 }
                        .first()
                }
                if (outcome == null) {
                    AppLog.i("archive", "لم تُرصد نتيجة الاستخراج $opId (مهلة/موت الخدمة)؛ خطوة حذف العناصر من الأرشيف تُترك للمستخدم")
                    return@launch
                }
                val err = withContext(AppDispatchers.io) {
                    when (val r = zipTransfer.deleteMovedEntries(archive.toPath(), clip.paths)) {
                        ZipItemTransfer.MoveOutResult.Deleted -> null
                        is ZipItemTransfer.MoveOutResult.Failed -> r.error
                    }
                }
                if (err != null) AppLog.e("archive", "تعذر حذف العناصر المنقولة من الأرشيف", err)
                host.reloadListing()
            }
        }
        scope.launch {
            val queued = FileOperationService.enqueueOnIo(
                appContext,
                OpRequest.Extract(archive.toPath(), dest.toPath(), Charsets.UTF_8.name(), clip.paths.toList(), opId, placeDirectly = true)
            )
            if (!queued) return@launch
            host.updateState { it.copy(clipboard = null) }
            host.sayResult(queued, if (queued) R.string.ui_op_queued else R.string.ui_op_queue_failed)
        }
    }

    companion object {
        /** مهلة انتظار نتيجة عملية وُعدت من الخدمة (خطوة «النقل» من الأرشيف). */
        const val OUTCOME_WAIT_MS = 20L * 60 * 1000
    }
}

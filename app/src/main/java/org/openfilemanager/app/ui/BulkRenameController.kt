package org.openfilemanager.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openfilemanager.app.AppDispatchers
import org.openfilemanager.app.AppLog
import org.openfilemanager.app.R
import org.openfilemanager.app.usecase.RenameCoordinator
import org.openfilemanager.core.BatchRename
import org.openfilemanager.core.BatchRenameSpec
import org.openfilemanager.core.PathRedactor
import org.openfilemanager.core.RenameInput
import org.openfilemanager.core.RenameOutcome
import org.openfilemanager.core.RenameStep
import org.openfilemanager.core.ZipModel

/** منسق عمليات إعادة التسمية الجماعية. */
class BulkRenameController(
    private val scope: CoroutineScope,
    private val renameCoordinator: RenameCoordinator,
    private val host: BrowserHost
) {
    /** آخر تسمية جماعية ناجحة على القرص، لتمكين [undoBulkRename]. */
    private var lastBulkRename: List<RenameStep>? = null

    /** هل توجد تسمية سابقة يمكن التراجع عنها؟ */
    fun canUndo(): Boolean = lastBulkRename != null

    /**
     * يبني سياق التسمية الجماعية للعرض في نافذة المعاينة. null إن لم يكن الموقع
     * قابلاً للتسمية الجماعية (أقل من عنصرين، أو أرشيف للقراءة فقط).
     */
    suspend fun bulkRenameContext(items: List<FileItem>, location: Location, zipModel: ZipModel?): BulkRenameContext? = withContext(AppDispatchers.io) {
        if (items.size < 2) return@withContext null
        when (location) {
            is Location.Dir -> {
                val existing = location.dir.list()?.toList() ?: return@withContext null
                BulkRenameContext(items.map { RenameInput(it.name, it.isDir) }, existing, strictFat = true)
            }
            is Location.Archive -> {
                val model = zipModel ?: return@withContext null
                BulkRenameContext(
                    items.map { RenameInput(it.name, it.isDir) },
                    model.children(location.inner).map { it.baseName }, strictFat = false
                )
            }
        }
    }

    /** ينفذ تسمية جماعية على القرص أو داخل أرشيف مفتوح. */
    fun bulkRename(items: List<FileItem>, spec: BatchRenameSpec, location: Location, zipModel: ZipModel?) {
        if (items.size < 2) return
        if (location is Location.Archive) {
            bulkRenameArchive(items, spec, location, zipModel); return
        }
        val dir = (location as? Location.Dir)?.dir ?: return
        scope.launch {
            val attempt = withContext(AppDispatchers.io) {
                runCatching {
                    val plan = BatchRename.plan(items.map { RenameInput(it.name, it.isDir) }, spec, dir.list()?.toList().orEmpty())
                    if (!plan.canApply) return@runCatching null
                    val outcome = renameCoordinator.execute(dir.toPath(), plan.renames())
                    when (outcome) {
                        is RenameOutcome.Success -> {
                            AppLog.i("rename", "bulk rename ${outcome.steps.size} in ${PathRedactor.token(dir.path)}")
                            outcome.steps
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
                attempt.isFailure -> host.sayErr(host.str(R.string.ui_bulk_failed, items.size))
                result == null -> host.sayErr(host.str(R.string.ui_bulk_blocked))
                result.isEmpty() -> host.sayErr(host.str(R.string.ui_bulk_failed, items.size))
                else -> {
                    lastBulkRename = result
                    host.emit(UiEvent.RenameDone(host.str(R.string.ui_renamed_n, result.size, items.size)))
                }
            }
            host.clearSelection()
            host.reloadListing()
        }
    }

    private fun bulkRenameArchive(items: List<FileItem>, spec: BatchRenameSpec, loc: Location.Archive, model: ZipModel?) {
        if (model == null) { host.sayErr(host.str(R.string.ui_err_readonly_archive)); return }
        scope.launch {
            val done = withContext(AppDispatchers.io) {
                runCatching {
                    val plan = BatchRename.plan(
                        items.map { RenameInput(it.name, it.isDir) }, spec,
                        model.children(loc.inner).map { it.baseName }, strictFat = false
                    )
                    if (!plan.canApply) return@runCatching -1
                    val entryOf = items.associate { it.name to it.entry }
                    val resolved = plan.renames().map { (old, new) -> (entryOf[old] ?: return@runCatching -1) to new }
                    model.renameBatch(resolved)
                }
            }
            val n = done.getOrNull()
            when {
                n == null -> {
                    // editErrorText محدد في ArchiveBrowserController؛ نمرر الخطأ عبر host.
                    val err = done.exceptionOrNull() ?: IllegalStateException()
                    val reason = (err as? org.openfilemanager.core.ZipEditException)?.let { ze ->
                        host.str(when (ze.reason) {
                            org.openfilemanager.core.ZipEditException.Reason.EXISTS -> R.string.ui_err_exists
                            org.openfilemanager.core.ZipEditException.Reason.INVALID_NAME -> R.string.ui_err_bad_name
                            org.openfilemanager.core.ZipEditException.Reason.INTO_ITSELF -> R.string.ui_err_into_itself
                            else -> R.string.ui_err_edit
                        })
                    } ?: err.message ?: err.javaClass.simpleName
                    host.sayErr(reason)
                }
                n < 0 -> host.sayErr(host.str(R.string.ui_bulk_blocked))
                else -> host.say(host.str(R.string.ui_renamed_n, n, items.size))
            }
            host.clearSelection()
            host.reloadListing()
        }
    }

    /**
     * يتراجع عن آخر تسمية جماعية على القرص (مرحلتان، بلا استبدال، مع سجل
     * كالمرور الأمامي فالانقطاع أثناء التراجع قابل للاسترداد).
     */
    fun undoBulkRename() {
        val steps = lastBulkRename ?: return
        lastBulkRename = null
        scope.launch {
            val ok = withContext(AppDispatchers.io) {
                renameCoordinator.executeUndo(steps) is RenameOutcome.Success
            }
            host.sayResult(ok, if (ok) R.string.ui_undone else R.string.ui_err_rename)
            host.reloadListing()
        }
    }
}

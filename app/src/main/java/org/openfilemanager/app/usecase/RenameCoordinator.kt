package org.openfilemanager.app.usecase

import org.openfilemanager.app.AppLog
import org.openfilemanager.core.JournalRenameAdapter
import org.openfilemanager.core.OpJournal
import org.openfilemanager.core.OpState
import org.openfilemanager.core.RenameEngine
import org.openfilemanager.core.RenameOutcome
import org.openfilemanager.core.RenameStep
import java.nio.file.Path
import java.util.UUID

/**
 * (E5) رسائل السجل رموز ثابتة مستقلة عن اللغة: السجل بيانات تقرأه أدوات
 * خارجية وواجهات لاحقة، والعربية فيه تظهر حرفياً بلا ترجمة. الترجمات
 * مسؤولية strings.xml (ui_bulk_rename وغيرها موجودة مسبقاً).
 */
internal object RenameJournalMessages {
    const val BULK_RENAME = "BULK_RENAME"
    const val BULK_RENAME_UNDO = "BULK_RENAME_UNDO"
    const val BULK_RENAME_ROLLED_BACK = "BULK_RENAME_ROLLED_BACK"
}

class RenameCoordinator(private val journal: OpJournal) {

    /**
     * ينفذ أزواج (قديم، جديد) داخل مجلد واحد عبر المحرك على مرحلتين مع تدوين
     * كل نقلة. لا يلمس شيئاً إن لم يكن هناك ما يُغيَّر.
     */
    fun execute(
        dir: Path,
        renames: List<Pair<String, String>>,
        opId: String = UUID.randomUUID().toString().take(8)
    ): RenameOutcome {
        val steps = RenameEngine.plan(dir, renames, opId)
        return runJournaled(steps, opId, kindLabel = RenameJournalMessages.BULK_RENAME)
    }

    /** يتراجع عن تسمية جماعية منجزة. */
    fun executeUndo(
        done: List<RenameStep>,
        opId: String = UUID.randomUUID().toString().take(8)
    ): RenameOutcome =
        runJournaled(RenameEngine.undoSteps(done), opId, kindLabel = RenameJournalMessages.BULK_RENAME_UNDO)

    private fun runJournaled(steps: List<RenameStep>, opId: String, kindLabel: String): RenameOutcome {
        if (steps.isEmpty()) return RenameOutcome.Success(emptyList())

        val adapter = JournalRenameAdapter(journal, opId, steps)
        try {
            adapter.begin()
        } catch (t: Exception) {
            return RenameOutcome.Failure(t, rolledBack = true)
        }

        val outcome = RenameEngine.execute(steps, adapter)
        when (outcome) {
            is RenameOutcome.Success -> runCatching {
                journal.finishOp(opId, OpState.FINISHED, outcome.steps.size, 0, 0, kindLabel)
            }
            is RenameOutcome.Failure -> {
                if (outcome.rolledBack) {
                    // (E5) السبب التفصيلي إلى logcat لا إلى السجل؛ السجل يأخذ
                    // الرمز الثابت حصراً.
                    AppLog.e(
                        "rename",
                        "bulk rename rolled back: ${outcome.cause.message ?: outcome.cause.javaClass.simpleName}",
                        outcome.cause
                    )
                    try {
                        journal.finishOp(
                            opId, OpState.FAILED, 0, steps.size, 0,
                            RenameJournalMessages.BULK_RENAME_ROLLED_BACK
                        )
                    } catch (_: Exception) {
                        // الكتابة تعذّرت والأسماء كها كانت: حذف القيد أخف
                        // خيارٍ وأأمن (لا حالة وسطية يستردّها أحد).
                        runCatching { journal.remove(opId) }
                    }
                }
                // تراجعٌ فشل أو حالة مجهولة: القيد يبقى RUNNING للاسترداد عند الإقلاع.
            }
        }
        return outcome
    }
}

package org.openfilemanager.app

import org.openfilemanager.core.OpJournal
import org.openfilemanager.core.OpKind
import org.openfilemanager.core.OpRecovery
import org.openfilemanager.core.OpRecord
import org.openfilemanager.core.OpState

/**
 * تنفيذ قرار المستخدم على عملية منقطعة (F-01): استئناف أو إلغاء وتنظيف.
 * الكائن بلا سياق أندرويد ولا كوروتينات: يُستدعى من حوار الاسترداد على مسار
 * IO، والاستئناف نفسه يمر عبر [FileOperationService.enqueue] كما أي طلب.
 *
 * (تحقق قبل القرار) القرار يُنفَّذ على حالة السجل اللحظية لا على نسخة الحوار:
 * سجل انتهى فعلاً (FINISHED/CANCELLED/FAILED) لا يُستأنف ولا يُلغى مرة
 * ثانية (المسار الزائف: نافذة السباق بين enqueue وخاتمة الخدمة) — يُعاد
 * خطأ واضح بدل إعادة تنفيذ ما تم. [resolveForJournal] صرفة (بلا AppContainer)
 * فتُختبر على JVM.
 *
 * الضغط والاستخراج لا يصلان هنا أصلاً: [OpRecovery.recoverableOps] يستبعدهما،
 * و[OpsCenter.refreshRecoverable] يُسقطهما وينظف مؤقتاتهما صامتاً.
 */
object OpRecoveryActions {

    /** حد عمر المؤقتات عند الاستئناف/الإلغاء: مؤقت أحدث قد يخص كتابة حية أخرى في المجلد نفسه. */
    const val TEMP_MIN_AGE_MS = 60_000L

    /**
     * يعيد رسالة خطأ قصيرة عند الفشل، أو null عند النجاح.
     * [resumeRequest] يرسل طلب الاستئناف للخدمة ويعيد نجاح الإرسال — فصلٌ
     * يبقي هذا الكائن صرفاً قابلاً للاختبار بلا أنظمة أندرويد.
     */
    fun resolve(
        container: AppContainer,
        op: OpRecord,
        resume: Boolean,
        resumeRequest: (OpRequest.Resume) -> Boolean
    ): String? {
        val err = resolveForJournal(container.opsCenter.journal, op, resume, resumeRequest)
        if (err == null && resume) {
            // سُجل الاستئناف المُرسَل: الفحص التالي لا يعرض العملية ثانية خلال
            // نافذة السباق (يمسح عند خاتمة العملية أو فشل الجدولة).
            container.opsCenter.markResumeDispatched(op.id)
        }
        return err
    }

    /**
     * المنطق الصرف فوق سجل معطى (قابل للاختبار بلا أندرويد). يتحقق من حالة
     * السجل الحية قبل أي تنظيف أو جدولة أو إنهاء.
     */
    fun resolveForJournal(
        journal: OpJournal,
        op: OpRecord,
        resume: Boolean,
        resumeRequest: (OpRequest.Resume) -> Boolean
    ): String? {
        // الحالة اللحظية من السجل — النسخة المعروضة في الحوار قد تكون قديمة.
        val live = journal.op(op.id) ?: return "no journal record for ${op.id}"
        return when {
            op.kind == OpKind.RENAME ->
                // التسمية لها مسار استرداد خاص (JournalRenameAdapter): إكمال
                // الاسم النهائي أو إرجاع الأسماء الأصلية — لا استئناف من سجل
                // عناصر النسخ ولا حذف مؤقتات عام هنا؛ recoverRename يدوّن
                // الخاتمة في السجل بنفسه.
                OpRecovery.recoverRename(journal, op.id, complete = resume).errors.firstOrNull()

            resume -> {
                if (live.state != OpState.RUNNING && live.state != OpState.INTERRUPTED) {
                    // انتهت فعلاً (بخدمة حية أو قرار سابق): لا إعادة تنفيذ.
                    return "operation ${op.id} is already ${live.state.name.lowercase()}"
                }
                // مؤقتات المحاولة القتيلة تُنظف بأفضل جهد قبل الاستئناف
                // (بحد عمر حتى لا تمس كتابة حية في المجلد نفسه)؛ الاستئناف
                // يعيد العناصر غير المنجزة من سجل العملية لا من صفر.
                runCatching { OpRecovery.cleanupTemps(journal, live, minAgeMs = TEMP_MIN_AGE_MS) }
                if (resumeRequest(OpRequest.Resume(op.id))) null else "queue failed"
            }

            else -> {
                if (live.state != OpState.RUNNING && live.state != OpState.INTERRUPTED) {
                    // إنهاء سجل منتهٍ لا يضيف إلا سطر F زائفاً ويعلّم العملية
                    // ملغاة بعد نجاحها — قرار بلا أثر يُتقبل بصمت.
                    return null
                }
                runCatching { OpRecovery.cleanupTemps(journal, live, minAgeMs = TEMP_MIN_AGE_MS) }
                journal.finishOp(
                    op.id, OpState.CANCELLED, live.ok, live.failed, live.skipped,
                    "أُلغيت من حوار الاسترداد"
                )
                null
            }
        }
    }
}

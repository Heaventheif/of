package org.openfilemanager.app.recovery

import org.openfilemanager.app.OpRecoveryActions
import org.openfilemanager.app.OpRequest
import org.openfilemanager.core.OpJournal
import org.openfilemanager.core.OpKind
import org.openfilemanager.core.OpRecovery
import org.openfilemanager.core.OpRecord

internal class RecoveryCenterModel(private val journal: OpJournal) {

    /** نتيجة فحص السجل: القائمة أو خطأ قراءة صريح — الفشل لا يتنكر حالةً فارغة. */
    data class Listing(val ops: List<OpRecord>, val error: String? = null)

    /**
     * فحص السجل الحالي. [activeOpIds] و[serviceBusy] و[resumeDispatched]
     * تمررها الشاشة من OpsCenter (عمليات حية/نافذة سباق الاستئناف) والاختبارات
     * تستخدم الافتراضي الفارغ — نفس دلالة OpsCenter.refreshRecoverable.
     */
    fun load(
        activeOpIds: Set<String> = emptySet(),
        serviceBusy: Boolean = false,
        resumeDispatched: Set<String> = emptySet()
    ): Listing {
        val ops = runCatching { journal.ops() }
            .getOrElse { return Listing(emptyList(), it.message ?: it.javaClass.simpleName) }
        val recoverable = runCatching {
            OpRecovery.recoverableOps(ops, activeOpIds, serviceBusy)
        }.getOrElse { return Listing(emptyList(), it.message ?: it.javaClass.simpleName) }
        return Listing(recoverable.filterNot { it.id in resumeDispatched })
    }

    /** سطر المسارات للعرض: (المصدر، الوجهة). للتسمية يُقرأ من عنصرها الأول فالقيد لا يحمل مساراته. */
    fun routeOf(op: OpRecord): Pair<String, String> =
        if (op.kind == OpKind.RENAME) {
            val first = runCatching { journal.items(op.id).firstOrNull() }.getOrNull()
            (first?.src ?: "") to (first?.dst ?: "")
        } else {
            (op.sources.firstOrNull() ?: op.targetDir) to op.targetDir
        }

    /**
     * استئناف: إكمال ما انقطع. يعيد رسالة خطأ عند الفشل أو null عند النجاح.
     * التسمية تُنفَّذ فوراً هنا (خاتمة دُوّنت)، والبقية تُرسل للخدمة.
     */
    fun resume(opId: String, resumeRequest: (OpRequest.Resume) -> Boolean): String? =
        resolve(opId, resume = true, resumeRequest)

    /**
     * تراجع (قرار المستخدم الصريح «إلغاء وتنظيف» / «تراجع عن التسمية»):
     * التسمية تعود لأسمائها الأصلية عبر recoverRename، والبقية تُنهى CANCELLED
     * مع تنظيف مؤقتاتها بحدّ العمر المعتاد.
     */
    fun rollBack(opId: String, resumeRequest: (OpRequest.Resume) -> Boolean): String? =
        resolve(opId, resume = false, resumeRequest)

    /** إزالة من القائمة: تتطلب تأكيداً صريحاً — [confirmed] = false لا يلمس السجل إطلاقاً (لا خاتمة ولا تنظيف ولا طلب خدمة). */
    fun dismiss(opId: String, confirmed: Boolean, resumeRequest: (OpRequest.Resume) -> Boolean): String? {
        if (!confirmed) return null
        return resolve(opId, resume = false, resumeRequest)
    }

    /** القرار يعمل على الحالة اللحظية من السجل — كما يفعل حوار الاسترداد القائم. */
    private fun resolve(opId: String, resume: Boolean, resumeRequest: (OpRequest.Resume) -> Boolean): String? {
        val op = runCatching { journal.op(opId) }.getOrNull()
            ?: return "no journal record for $opId"
        return OpRecoveryActions.resolveForJournal(journal, op, resume, resumeRequest)
    }
}

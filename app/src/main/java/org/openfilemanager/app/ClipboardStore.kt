package org.openfilemanager.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.openfilemanager.core.OpKind

/**
 * [0.7.9] حافظة اللصق تنجو من دورة حياة ViewModel: كانت أعضاء في
 * ViewModel فتُفقد عند قتل العملية، فتُترك الحافظة ممتلئة بعد
 * MOVE اكتمل (awaitingMoveOpId ضاع) ويعيد المستخدم لصق ملفات انتقلت فعلاً.
 * Singleton في [AppContainer] — تُقرأ وتُكتب من ViewModel عبر مراجع immutable،
 * وتستمع لنتائج OpsCenter بنفسها فتفريغ حافظة القص بعد نقل ناجح لا يعود
 * معتمداً على بقاء ViewModel حياً.
 *
 * ليس في :core عمداً: يعتمد على FileEntry (طبقة أندرويد).
 */
class ClipboardStore(private val opsCenter: OpsCenter, scope: CoroutineScope) {

    @Volatile
    private var entries: List<FileEntry> = emptyList()

    @Volatile
    private var isCut = false

    @Volatile
    private var awaitingMoveOpId: String? = null

    init {
        // [0.7.9] النتائج كانت تُجمع في init الخاص بالـ ViewModel فتضيع معه؛
        // الجمع هنا يبقى حياً ما بقيت العملية، والنطاق من AppContainer.
        scope.launch {
            opsCenter.outcomes.collect { outcome ->
                if (outcome.kind == OpKind.MOVE) {
                    onMoveOutcome(outcome.opId, outcome.failed, outcome.skipped)
                }
            }
        }
    }

    val current: List<FileEntry> get() = entries
    val cut: Boolean get() = isCut
    val pendingMoveOpId: String? get() = awaitingMoveOpId

    fun set(entries: List<FileEntry>, cut: Boolean) {
        this.entries = entries.toList() // defensive copy
        this.isCut = cut
        this.awaitingMoveOpId = null
    }

    fun onMoveStarted(opId: String) {
        awaitingMoveOpId = opId
    }

    /**
     * يعيد true إن طابق opId النقل المنتظر. النقل الناجح كلياً
     * (failed == 0 && skipped == 0) يفرّغ الحافظة؛ غير ذلك تبقى للصق/إعادة
     * المحاولة (والانتظار يزول — نتيجة ثانية لنفس العملية لا تصح أصلاً).
     */
    fun onMoveOutcome(opId: String?, failed: Int, skipped: Int): Boolean {
        if (awaitingMoveOpId == null || opId == null || awaitingMoveOpId != opId) return false
        awaitingMoveOpId = null
        if (failed == 0 && skipped == 0) {
            entries = emptyList()
            isCut = false
        }
        return true
    }

    fun clear() {
        entries = emptyList()
        isCut = false
        awaitingMoveOpId = null
    }
}

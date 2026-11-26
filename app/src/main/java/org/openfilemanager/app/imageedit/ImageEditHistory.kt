package org.openfilemanager.app.imageedit

internal class ImageEditHistory<T>(
    /** الحالة المرجعية للجلسة «بلا تعديل». */
    baseline: T,
    /** سعة مكدس التراجع — مثبَّتة على السعة الافتراضية لـEditHistory في core. */
    private val capacity: Int = DEFAULT_CAPACITY,
    /** مصدر زمن الميلي ثانية لدمج النص المتلاحق — حقن للاختبار المحدَّد. */
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** يُنادى بكل حالة تسقط من التاريخ دون أن تُعاد للمستدعي. */
    private val onDropped: (T) -> Unit = {}
) {

    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    private var sessionBaseline: T = baseline
    private var gestureRecording = false
    private var sliderRecording = false
    private var oneShotRecorded = false
    private var lastTextRecordAt = 0L

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** تسجيل الحالة الحالية قبل تعديل جديد: يمسح مستقبل الإعادة ويسقط الأقدم بالسعة. */
    fun record(state: T) {
        past.addLast(state)
        while (past.size > capacity) onDropped(past.removeFirst())
        if (future.isNotEmpty()) {
            for (stale in future) onDropped(stale)
            future.clear()
        }
    }

    /** آخر حالة مسجّلة؛ null إن لم يوجد ما يُلغى. الحالة الحالية تُخزَّن للإعادة. */
    fun undo(current: T): T? {
        val previous = past.removeLastOrNull() ?: return null
        future.addLast(current)
        return previous
    }

    /** الحالة التالية؛ null إن لم يوجد ما يُعاد. الحالة الحالية تعود إلى مكدس الماضي. */
    fun redo(current: T): T? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        return next
    }

    /** إفراغ المكدسين (لا يمس أعلام الدمج — لا يُنادى من المحرّر حالياً). */
    fun clear() {
        for (dropped in past) onDropped(dropped)
        for (dropped in future) onDropped(dropped)
        past.clear()
        future.clear()
    }

    /**
     * قاعدة mutate: التعديل المسجَّل يكتب الحالة السابقة قبل تطبيقه ما لم
     * تكن إيماءة أو تزام منزلق نشطاً — حينها من يسجّل هو بدايتهما فقط.
     */
    fun recordForMutation(state: T, record: Boolean) {
        if (record && !gestureRecording && !sliderRecording) record(state)
    }

    /** بداية إيماءة سحب متواصلة: تسجيل واحد للسحبة كاملة لا لكل إطار. */
    fun beginGesture(state: T) {
        if (!gestureRecording && !sliderRecording) record(state)
        gestureRecording = true
    }

    /** نهاية الإيماءة: يُنزل علم الإيماءة فقط (علم المنزلق مستقل). */
    fun endGesture() {
        gestureRecording = false
    }

    /** بداية سحب منزلق: تسجيل مرة واحدة حتى انتهاء السحب. */
    fun beginSliderCommit(state: T) {
        if (!sliderRecording && !gestureRecording) record(state)
        sliderRecording = true
    }

    /** نهاية التزام المنزلق: يُنزل علم المنزلق فقط. */
    fun endSliderCommit() {
        sliderRecording = false
    }

    /** بداية ضربة «تسجيل واحد» (بداية ضربة الممحاة): يُعاد تسليح القفل. */
    fun beginOneShot() {
        oneShotRecorded = false
    }

    /** يسجّل أول نداء بعد التسليح فقط؛ البقية تُدمَج في التسجيل الأول. */
    fun recordOneShot(state: T) {
        if (!oneShotRecorded) {
            record(state)
            oneShotRecorded = true
        }
    }

    /**
     * بوابة النص المتلاحق: تسمح بالتسجيل إن مرّت المهلة منذ آخر تسجيل مسموح،
     * ولا تقدّم ختم الزمن إلا عند السماح (حتى نداء record=false لا يحرّكه).
     * لا تسجّل بنفسها — مرّر الناتج إلى [recordForMutation].
     */
    fun shouldRecordTextEdit(record: Boolean): Boolean {
        if (!record) return false
        val now = nowMs()
        if (now - lastTextRecordAt > TEXT_COALESCE_MS) {
            lastTextRecordAt = now
            return true
        }
        return false
    }

    /** تحديث المرجع «بلا تعديل» عند اكتمال التحميل. */
    fun resetSessionBaseline(baseline: T) {
        sessionBaseline = baseline
    }

    /** الاتساخ = الحالة الحالية تختلف عن مرجع الجلسة. */
    fun isDirty(current: T): Boolean = current != sessionBaseline

    /** للاختبار فقط: عدد الحالات القابلة للتراجع (مثل EditHistory في core). */
    fun pastSizeForTest(): Int = past.size

    companion object {
        /** السعة المثبَّتة — مطابقة لسعة EditHistory الافتراضية في core. */
        const val DEFAULT_CAPACITY = 60

        /** مهلة دمج تعديلات النص المتلاحقة بالملي ثانية (كانت 800 حرفياً). */
        const val TEXT_COALESCE_MS = 800L
    }
}

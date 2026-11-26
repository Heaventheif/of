package org.openfilemanager.app.imageedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEditHistoryTest {

    // ---- المكدس: تسجيل/تراجع/إعادة ----

    @Test
    fun undoRedoCycle() {
        val h = ImageEditHistory<Int>(baseline = 0)
        var s = 1
        h.record(s) // سجّل 1 قبل أن تصير الحالة 2
        s = 2
        h.record(s)
        s = 3
        assertTrue(h.canUndo)
        assertFalse(h.canRedo)
        s = h.undo(s)!!
        assertEquals(2, s)
        s = h.undo(s)!!
        assertEquals(1, s)
        assertFalse(h.canUndo)
        assertTrue(h.canRedo)
        s = h.redo(s)!!
        assertEquals(2, s)
        assertTrue(h.canUndo)
        assertTrue(h.canRedo)
        s = h.redo(s)!!
        assertEquals(3, s)
    }

    @Test
    fun undoRedoOnEmptyReturnNull() {
        val h = ImageEditHistory<Int>(baseline = 0)
        assertNull(h.undo(1))
        assertNull(h.redo(1))
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
    }

    @Test
    fun recordClearsRedoAndDropsDiscardedInOrder() {
        val dropped = mutableListOf<Int>()
        val h = ImageEditHistory(baseline = 0, onDropped = { dropped += it })
        var s = 1
        h.record(s)
        s = 2
        assertEquals(1, h.undo(s)!!)
        s = 1
        assertTrue(h.canRedo)
        h.record(s) // تعديل جديد يمسح مستقبل الإعادة → الحالة 2 تسقط
        assertEquals(listOf(2), dropped)
        assertFalse(h.canRedo)
    }

    @Test
    fun undoRedoSwapDropsNothing() {
        val dropped = mutableListOf<Int>()
        val h = ImageEditHistory(baseline = 0, onDropped = { dropped += it })
        h.record(1)
        h.record(2)
        assertEquals(2, h.undo(3)!!)
        assertEquals(3, h.redo(4)!!)
        assertTrue(dropped.isEmpty()) // المتناقل بين المكدسين والمرتجع ليس سقوطاً
    }

    // ---- السعة (المثبَّتة 60 كالسلف في core) ----

    @Test
    fun capacityDropsOldestInOrder() {
        val dropped = mutableListOf<Int>()
        val h = ImageEditHistory(baseline = 0, capacity = 3, onDropped = { dropped += it })
        var s = 0
        repeat(5) { h.record(s); s++ } // تسجيل 0..4
        assertEquals(3, h.pastSizeForTest())
        assertEquals(listOf(0, 1), dropped) // الأقدم يسقط أولاً
        assertEquals(4, h.undo(s))
    }

    @Test
    fun defaultCapacityIsSixty() {
        val h = ImageEditHistory<Int>(baseline = 0)
        repeat(61) { h.record(it) }
        assertEquals(ImageEditHistory.DEFAULT_CAPACITY, h.pastSizeForTest())
        assertEquals(60, h.undo(-1)!!)
        assertTrue(h.canRedo) // الحالة الحالية (-1) صارت في مستقبل الإعادة — كما في السلف
    }

    @Test
    fun clearDropsAllHeldPayloadsPastThenFuture() {
        val dropped = mutableListOf<Int>()
        val h = ImageEditHistory(baseline = 0, onDropped = { dropped += it })
        h.record(1)
        h.record(2)
        assertEquals(2, h.undo(3)!!) // past=[1] وfuture=[3]
        h.clear()
        assertEquals(listOf(1, 3), dropped) // الماضي ثم المستقبل بترتيب السقوط
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertNull(h.undo(4))
        assertNull(h.redo(4))
    }

    // ---- دمج الإيماءة (كانت gestureRecording) ----

    @Test
    fun gestureRecordsOnceAndSuppressesMutationRecords() {
        val h = ImageEditHistory<Int>(baseline = 0)
        h.beginGesture(1) // تسجيل واحد لبداية الإيماءة
        h.beginGesture(2) // داخل الإيماءة نفسها: بلا تسجيل إضافي
        h.recordForMutation(3, record = true) // ما دامت الإيماءة نشطة لا تسجيل
        assertEquals(1, h.pastSizeForTest())
        h.endGesture()
        h.recordForMutation(4, record = true)
        assertEquals(2, h.pastSizeForTest())
        assertEquals(4, h.undo(-1)!!)
        assertEquals(1, h.undo(-2)!!)
        assertFalse(h.canUndo)
    }

    // ---- دمج التزام المنزلق (كانت sliderRecording) ----

    @Test
    fun sliderCommitRecordsOnceUntilEnd() {
        val h = ImageEditHistory<Int>(baseline = 0)
        h.beginSliderCommit(1)
        h.beginSliderCommit(2)
        h.recordForMutation(3, record = true)
        assertEquals(1, h.pastSizeForTest())
        h.endSliderCommit()
        h.recordForMutation(4, record = true)
        assertEquals(2, h.pastSizeForTest())
        assertEquals(4, h.undo(-1)!!)
        assertEquals(1, h.undo(-2)!!)
    }

    // ---- العلمان معاً: سلوك مثبَّت من الشيفرة الأصلية ----
    // beginGesture أثناء تزام منزلق نشط: لا تسجيل ويرتفع العلمان معاً،
    // ولا تسجيل حتى يُنزل كلاهما (نفس ترتيب نزولهما endGesture/endSliderCommit).

    @Test
    fun gestureDuringSliderSuppressesEverythingUntilBothEnd() {
        val h = ImageEditHistory<Int>(baseline = 0)
        h.beginSliderCommit(1) // يسجّل
        h.beginGesture(2) // المنزلق نشط: بلا تسجيل والعلمان معاً مرفوعان
        h.recordForMutation(3, record = true)
        assertEquals(1, h.pastSizeForTest())
        h.endGesture() // بقي علم المنزلق
        h.recordForMutation(4, record = true)
        assertEquals(1, h.pastSizeForTest())
        h.endSliderCommit()
        h.recordForMutation(5, record = true)
        assertEquals(2, h.pastSizeForTest())
        assertEquals(5, h.undo(-1)!!)
        assertEquals(1, h.undo(-2)!!)
    }

    // ---- قفل الضربة الواحدة (كانت eraseRecorded للممحاة) ----

    @Test
    fun oneShotRecordsOncePerStroke() {
        val h = ImageEditHistory<Int>(baseline = 0)
        h.recordOneShot(1) // قبل أي تسليح: القفل مُسلَّح أصلاً (كما eraseRecorded=false)
        h.recordOneShot(2)
        assertEquals(1, h.pastSizeForTest())
        h.beginOneShot() // بداية ضربة جديدة يعيد التسليح
        h.recordOneShot(3)
        h.recordOneShot(4)
        assertEquals(2, h.pastSizeForTest())
        assertEquals(3, h.undo(-1)!!)
        assertEquals(1, h.undo(-2)!!)
    }

    // ---- بوابة النص المتلاحق (كانت lastTextRecordAt/800ms) ----

    @Test
    fun textThrottleAllowsOneRecordPerWindow() {
        var now = 1000L
        val h = ImageEditHistory(baseline = 0, nowMs = { now })
        assertTrue(h.shouldRecordTextEdit(record = true)) // 1000-0 > 800
        now = 1200L
        assertFalse(h.shouldRecordTextEdit(record = true)) // 200 فقط
        now = 1800L
        assertFalse(h.shouldRecordTextEdit(record = true)) // 800 بالضبط: ليست > 800
        now = 1801L
        assertTrue(h.shouldRecordTextEdit(record = true))
        now = 2000L
        assertFalse(h.shouldRecordTextEdit(record = false)) // record=false لا يقدّم الختم
        now = 5000L
        assertTrue(h.shouldRecordTextEdit(record = true)) // الختم بقى 1801 (نداء false لم يحرّكه)
        assertEquals(0, h.pastSizeForTest()) // البوابة لا تسجّل بنفسها — المستدعي يسجّل عبر recordForMutation
    }

    // ---- الجلسة: المرجع والاتساخ (كانت initialSnap/dirty) ----

    @Test
    fun dirtyTracksSessionBaseline() {
        val h = ImageEditHistory(baseline = 0)
        assertFalse(h.isDirty(0))
        assertTrue(h.isDirty(7))
        h.resetSessionBaseline(7) // كما تفعل load() عند اكتمال التحميل
        assertFalse(h.isDirty(7))
        assertTrue(h.isDirty(0))
    }
}

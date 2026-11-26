package org.openfilemanager.app.history

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OperationHistoryStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newStore(): OperationHistoryStore = OperationHistoryStore(tmp.root)

    // ---- الاحتفاظ المحدود: الحد يُحفظ والأقدم يُرمى والترتيب كما أُضيف ----
    @Test
    fun boundedRetentionDropsOldestBeyondCapAndKeepsInsertionOrder() {
        val store = newStore()
        repeat(OperationHistoryStore.MAX_ENTRIES + 5) { i ->
            store.record("COPY", ok = i + 1, failed = 0, skipped = 0)
        }
        val entries = store.entries()
        assertEquals("يُحتفظ بالحد الأقصى تماماً", OperationHistoryStore.MAX_ENTRIES, entries.size)
        // الأقدم خمسة (ok=1..5) رُميت؛ أول ما بقي ok=6.
        assertEquals(6, entries.first().ok)
        assertEquals(OperationHistoryStore.MAX_ENTRIES + 5, entries.last().ok)
        // الترتيب محفوظ تصاعدياً بترتيب الإضافة (ليس بالطابع الزمني).
        assertEquals((6..OperationHistoryStore.MAX_ENTRIES + 5).toList(), entries.map { it.ok })
        assertTrue(entries.all { it.state == OperationOutcomeState.COMPLETED && it.kind == "COPY" })
    }

    // ---- المسح يُفرغ المخزن (والمجلد يبقى صالحاً للاستخدام بعده) ----
    @Test
    fun clearEmptiesTheStoreAndItStaysUsable() {
        val store = newStore()
        store.record("DELETE", 2, 1, 3)
        store.record("MOVE", 1, 0, 0)
        assertEquals(2, store.entries().size)
        store.clear()
        assertEquals("المسح يُفرغ السجل", emptyList<OperationHistoryEntry>(), store.entries())
        // والمخزن يظل صالحاً: تسجيل بعد المسح يبدأ سجلاً جديداً.
        store.record("EXTRACT", 1, 0, 0)
        assertEquals(1, store.entries().size)
        assertEquals("EXTRACT", store.entries().single().kind)
    }

    // ---- البقاء بعد إعادة التشغيل: مثيل جديد فوق الملف نفسه ----
    @Test
    fun survivesRestartAsNewInstanceOverTheSameRoot() {
        val store = newStore()
        store.record("COPY", 3, 0, 0)
        store.record("COMPRESS", 0, 0, 1)
        store.record(null, 0, 2, 0)
        val before = store.entries()
        // «إعادة تشغيل»: مثيل جديد كلي على الجذر نفسه (نفس الملف على القرص).
        val reopened = newStore()
        val after = reopened.entries()
        assertEquals(before, after)
        assertEquals(3, after.size)
        assertEquals("COPY", after[0].kind)
        assertEquals("COMPRESS", after[1].kind)
        assertNull("نوع مجهول (استئناف) يبقى محفوظاً كنوع غائب", after[2].kind)
        assertEquals(OperationOutcomeState.FAILED, after[2].state)
        assertEquals(2, after[2].failed)
    }

    // ---- القراءة لقطة للعرض فقط: لا تفاعل مع الإضافات اللاحقة ولا تعديل داخلي ----
    @Test
    fun entriesIsAnAppendOnlySnapshotForTheUi() {
        val store = newStore()
        store.record("COPY", 1, 0, 0)
        store.record("MOVE", 2, 0, 0)
        val snapshot = store.entries()
        assertEquals(2, snapshot.size)
        // إضافة لاحقة لا تغيّر اللقطة القديمة، والمخزن نفسه يرى الجديد.
        store.record("DELETE", 1, 0, 0)
        assertEquals("اللقطة قائمة مستقلة للقراءة فقط", 2, snapshot.size)
        assertEquals(3, store.entries().size)
        // لا إعادة ترتيب ولا تراكم سطور مكرر بعد قراءات متكررة.
        assertEquals(store.entries(), store.entries())
        assertEquals(listOf("COPY", "MOVE", "DELETE"), store.entries().map { it.kind })
    }

    // ---- مشتق حالة الخاتمة من العدّادات (التعيين الموثق في ملاحظة التصميم) ----
    @Test
    fun outcomeStateIsDerivedPurelyFromCounts() {
        assertEquals(OperationOutcomeState.COMPLETED, outcomeStateOf(3, 0, 0))
        assertEquals(OperationOutcomeState.PARTIAL, outcomeStateOf(2, 1, 0))
        assertEquals(OperationOutcomeState.PARTIAL, outcomeStateOf(1, 0, 4))
        assertEquals(OperationOutcomeState.FAILED, outcomeStateOf(0, 1, 0))
        assertEquals(OperationOutcomeState.FAILED, outcomeStateOf(0, 5, 2))
        assertEquals(OperationOutcomeState.CANCELLED, outcomeStateOf(0, 0, 1))
        assertEquals(OperationOutcomeState.CANCELLED, outcomeStateOf(0, 0, 0))
    }

    // ---- الأسطر التالفة تُتخطى عند القراءة (دفاع ضد كتابة مبتورة) ----
    @Test
    fun corruptLinesAreSkippedOnRead() {
        val store = newStore()
        store.record("COPY", 1, 0, 0)
        val f = File(File(tmp.root, "op-history"), "history.log")
        assertTrue(f.isFile)
        val good = f.readLines().single()
        f.writeText("$good\nCOPY,تالف\n,COPY,1,COMPLETED\n2,COMPLETED,COPY\n")
        store.record("MOVE", 1, 0, 0)
        val entries = store.entries()
        assertEquals("السطر التالف فقط يُتخطى والسليمان يبقيان", 2, entries.size)
        assertEquals(listOf("COPY", "MOVE"), entries.map { it.kind })
        assertFalse(entries.any { it.kind == null })
    }
}

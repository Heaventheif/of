package org.openfilemanager.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.datastore.preferences.core.stringPreferencesKey

class AppSettingsDiskQueueTest {
    /** مخزن حقن الأعطال: يفشل N مرة قادمة أو باستمرار، ويحفظ حالة نهائية قابلة للقراءة. */
    private class FakeStore {
        val data = HashMap<String, String>()
        var transientFailures = 0
        var failAlways = false
        var writes = 0

        private fun gate() {
            if (failAlways) throw IllegalStateException("store broken")
            if (transientFailures > 0) { transientFailures--; throw IllegalStateException("transient") }
        }

        fun put(key: String, value: String) {
            gate()
            data[key] = value
            writes++
        }

        fun clear() {
            gate()
            data.clear()
        }
    }

    private class RecordingQueue(store: FakeStore) {
        val queue = SettingsDiskEditQueue(
            apply = { edit ->
                when (edit) {
                    is DiskEdit.SetValue -> store.put(edit.key.name, (edit.value as DiskEdit.SupportedValue.Str).v)
                    is DiskEdit.Remove -> store.put(edit.key.name, "<removed>")
                    is DiskEdit.ClearAll -> store.clear()
                    is DiskEdit.Barrier -> edit.done.complete(Unit)
                }
            },
            retryMs = 0L,
            maxAttempts = 3,
            onGiveUp = { _, _, _ -> }
        )
    }

    private fun str(key: String) = stringPreferencesKey(key)

    // ---- عطل عابر ثم نجاح: التعديل يصل والعلم صافٍ ----
    @Test
    fun transientFailureThenSuccessPersistsEditAndClearsFlag() = runBlocking {
        val store = FakeStore().apply { transientFailures = 2 }
        val q = RecordingQueue(store).queue

        q.submit(DiskEdit.SetValue(str("k1"), DiskEdit.SupportedValue.Str("v1")))

        assertEquals("v1", store.data["k1"])
        assertFalse(q.saveFailed.value)
        assertTrue(q.pendingSnapshot().isEmpty())
    }

    // ---- فشل مستمر يتجاوز 256 تعديلاً: لا يُرمى أي تعديل (كان يُرمى الأقدم) ----
    @Test
    fun sustainedFailureBeyond256EditsKeepsEveryPendingEdit() = runBlocking {
        val store = FakeStore().apply { failAlways = true }
        val q = RecordingQueue(store).queue

        // 300 تعديلاً على 300 مفتاح مميزاً + تكرار على مفتاح واحد: كل شيء يبقى.
        repeat(300) { i -> q.submit(DiskEdit.SetValue(str("k$i"), DiskEdit.SupportedValue.Str("v$i"))) }
        q.submit(DiskEdit.SetValue(str("dup"), DiskEdit.SupportedValue.Str("a")))
        q.submit(DiskEdit.SetValue(str("dup"), DiskEdit.SupportedValue.Str("b")))

        assertTrue("العلم مرفوع عند فشل مستمر", q.saveFailed.value)
        assertEquals("لا إسقاط: 300 مفتاحاً مميزاً كلها في الطابور", 301, q.pendingSnapshot().size)
        assertEquals("الأحدث يسود للمفتاح المكرر", "b",
            (q.pendingSnapshot().last { it is DiskEdit.SetValue && it.key == str("dup") }
                as DiskEdit.SetValue).value.let { (it as DiskEdit.SupportedValue.Str).v })
    }

    // ---- الدمج لكل مفتاح: ثلاث كتابات لنفس المفتاح = تعديل واحد بالأحدث ----
    @Test
    fun coalescingKeepsLastWritePerKeyInPlace() = runBlocking {
        val store = FakeStore().apply { failAlways = true }
        val q = RecordingQueue(store).queue

        q.submit(DiskEdit.SetValue(str("k"), DiskEdit.SupportedValue.Str("v1")))
        q.submit(DiskEdit.SetValue(str("other"), DiskEdit.SupportedValue.Str("o1")))
        q.submit(DiskEdit.SetValue(str("k"), DiskEdit.SupportedValue.Str("v2")))
        q.submit(DiskEdit.SetValue(str("k"), DiskEdit.SupportedValue.Str("v3")))

        val pending = q.pendingSnapshot()
        assertEquals("طابور محدود بالمفاتيح المميزة", 2, pending.size)
        assertEquals("التعديل المدمج يبقى في موضع أول كتابة للمفتاح (الترتيب محفوظ)", "k",
            (pending[0] as DiskEdit.SetValue).key.name)
        assertEquals("v3",
            (pending[0] as DiskEdit.SetValue).value.let { (it as DiskEdit.SupportedValue.Str).v })
        assertEquals("other", (pending[1] as DiskEdit.SetValue).key.name)
    }

    // ---- الإزالة تدمج فوق كتابة معلقة، والكتابة تدمج فوق إزالة معلقة ----
    @Test
    fun removeAndSetCoalesceOnTheSameKey() = runBlocking {
        val store = FakeStore().apply { failAlways = true }
        val q = RecordingQueue(store).queue

        q.submit(DiskEdit.SetValue(str("k"), DiskEdit.SupportedValue.Str("v")))
        q.submit(DiskEdit.Remove(str("k")))
        q.submit(DiskEdit.SetValue(str("j"), DiskEdit.SupportedValue.Str("w")))
        q.submit(DiskEdit.Remove(str("j")))

        val pending = q.pendingSnapshot()
        assertEquals(2, pending.size)
        assertTrue("الإزالة تفوق الكتابة المعلقة لنفس المفتاح", pending[0] is DiskEdit.Remove)
        assertTrue("الإزالة تفوق الإزالة... والكتابة الأحدث تفوق الإزالة", pending[1] is DiskEdit.Remove)
    }

    // ---- ClearAll حاجز ترتيب: يفوق ما قبله، وما بعده يُحفظ بترتيبه ----
    @Test
    fun clearAllSupersedesEarlierEditsAndPreservesLaterOnes() = runBlocking {
        val store = FakeStore().apply { failAlways = true }
        val q = RecordingQueue(store).queue

        q.submit(DiskEdit.SetValue(str("a"), DiskEdit.SupportedValue.Str("1")))
        q.submit(DiskEdit.SetValue(str("b"), DiskEdit.SupportedValue.Str("2")))
        q.submit(DiskEdit.ClearAll)
        q.submit(DiskEdit.SetValue(str("c"), DiskEdit.SupportedValue.Str("3")))
        q.submit(DiskEdit.SetValue(str("a"), DiskEdit.SupportedValue.Str("4")))

        val pending = q.pendingSnapshot()
        assertEquals("ما قبل ClearAll يتلاشى بقرار المستخدم نفسه", 3, pending.size)
        assertTrue(pending[0] is DiskEdit.ClearAll)
        assertEquals("c", (pending[1] as DiskEdit.SetValue).key.name)
        assertEquals("a", (pending[2] as DiskEdit.SetValue).key.name)
    }

    // ---- الحاجز يكتمل بعد كتابة كل ما قبله فعلاً ----
    @Test
    fun barrierCompletesOnlyAfterEarlierEditsAreApplied() = runBlocking {
        val store = FakeStore().apply { transientFailures = 1 }
        val q = RecordingQueue(store).queue

        q.submit(DiskEdit.SetValue(str("first"), DiskEdit.SupportedValue.Str("1")))
        val done = CompletableDeferred<Unit>()
        q.submit(DiskEdit.Barrier(done))
        done.await()
        q.submit(DiskEdit.SetValue(str("second"), DiskEdit.SupportedValue.Str("2")))

        assertEquals("1", store.data["first"])
        assertEquals("2", store.data["second"])
        assertFalse(q.saveFailed.value)
    }

    // ---- محاكاة الإقلاع على المخزن: الحالة النهائية = آخر قيم اللقطة (الأحدث يسود) ----
    @Test
    fun recoveredStoreStateMatchesCoalescedSnapshotForANewReader() = runBlocking {
        val store = FakeStore().apply { failAlways = true }
        val q = RecordingQueue(store).queue

        q.submit(DiskEdit.SetValue(str("k1"), DiskEdit.SupportedValue.Str("old")))
        q.submit(DiskEdit.SetValue(str("k2"), DiskEdit.SupportedValue.Str("keep")))
        q.submit(DiskEdit.SetValue(str("k1"), DiskEdit.SupportedValue.Str("final")))
        assertTrue(q.saveFailed.value)

        // «الخدمة عادت»: المخزن يُشفى ثم حاجز يصرف كل المعلق.
        store.failAlways = false
        val done = CompletableDeferred<Unit>()
        q.submit(DiskEdit.Barrier(done))
        done.await()

        // قارئ جديد (إقلاع تالٍ) يقرأ ما وصل للقرص فعلاً — آخر قيم الجلسة.
        assertEquals("final", store.data["k1"])
        assertEquals("keep", store.data["k2"])
        assertFalse("التفريغ الكامل يمسح العلم", q.saveFailed.value)
        assertTrue(q.pendingSnapshot().isEmpty())
    }

    // ---- تفريغ بعد عطل: لا تعديل ضائع بالترتيب ----
    @Test
    fun drainAfterRecoveryAppliesEverythingInOrder() = runBlocking {
        val store = FakeStore().apply { failAlways = true }
        val q = RecordingQueue(store).queue
        q.submit(DiskEdit.SetValue(str("a"), DiskEdit.SupportedValue.Str("1")))
        q.submit(DiskEdit.SetValue(str("b"), DiskEdit.SupportedValue.Str("2")))

        store.failAlways = false
        q.submit(DiskEdit.SetValue(str("c"), DiskEdit.SupportedValue.Str("3")))

        assertEquals(listOf("1", "2", "3"), listOf("a", "b", "c").map { store.data[it] })
        assertFalse(q.saveFailed.value)
    }
}

package org.openfilemanager.app

import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsWriteStatusTest {

    /** مخزن حقن الأعطال (النمط نفسه من AppSettingsDiskQueueTest): يفشل باستمرار أو N مرة. */
    private class FakeStore {
        val data = HashMap<String, String>()
        var transientFailures = 0
        var failAlways = false

        private fun gate() {
            if (failAlways) throw IllegalStateException("store broken")
            if (transientFailures > 0) { transientFailures--; throw IllegalStateException("transient") }
        }

        fun put(key: String, value: String) {
            gate()
            data[key] = value
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

    // ---- (أ) الفشل يرفع حالة المؤشر المرصودة (saveFailed StateFlow صائبة) ----
    @Test
    fun sustainedFailureRaisesObservedIndicatorState() = runBlocking {
        val store = FakeStore().apply { failAlways = true }
        val q = RecordingQueue(store).queue

        q.submit(DiskEdit.SetValue(str("k"), DiskEdit.SupportedValue.Str("v")))

        assertTrue("حالة المؤشر المرصودة مرفوعة عند فشل مستمر", q.saveFailed.value)
        assertEquals("لا إسقاط: التعديل الفاشل يبقى في رأس الطابور", 1, q.pendingSnapshot().size)
    }

    // ---- (ب) نجاح إعادة المحاولة يصرف الطابور كله ويمسح حالة المؤشر ----
    @Test
    fun retrySuccessDrainsQueueAndClearsIndicator() = runBlocking {
        val store = FakeStore().apply { failAlways = true }
        val q = RecordingQueue(store).queue
        q.submit(DiskEdit.SetValue(str("k"), DiskEdit.SupportedValue.Str("v")))
        assertTrue(q.saveFailed.value)

        // «الخدمة عادت» ثم زر إعادة المحاولة: تفريغ نشط بلا تعديل جديد.
        store.failAlways = false
        q.retryPending()

        assertEquals("v", store.data["k"])
        assertFalse("نجاح التفريغ الكامل يمسح حالة المؤشر", q.saveFailed.value)
        assertTrue(q.pendingSnapshot().isEmpty())
    }

    // ---- (ج) لا تعديل ضائع عبر إعادة المحاولة: كل المعلق يُطبق بالترتيب وبأحدث قيمة لكل مفتاح ----
    @Test
    fun noEditIsLostAcrossRetryAllPendingAppliedWithLatestPerKey() = runBlocking {
        val store = FakeStore().apply { failAlways = true }
        val q = RecordingQueue(store).queue

        q.submit(DiskEdit.SetValue(str("k1"), DiskEdit.SupportedValue.Str("old")))
        q.submit(DiskEdit.SetValue(str("k2"), DiskEdit.SupportedValue.Str("keep")))
        q.submit(DiskEdit.SetValue(str("k1"), DiskEdit.SupportedValue.Str("final")))
        assertTrue(q.saveFailed.value)

        store.failAlways = false
        q.retryPending()

        assertEquals("القيمة الأحدث للمفتاح المدمج هي التي وصلت للقرص", "final", store.data["k1"])
        assertEquals("keep", store.data["k2"])
        assertFalse(q.saveFailed.value)
        assertTrue("الطابور فارغ بعد الاسترداد — دلالات استرداد D5 نفسها", q.pendingSnapshot().isEmpty())
    }

    // ---- إعادة المحاولة بلا طابور معلق لا تمسح علم فشل التحميل الخارجي ----
    @Test
    fun retryWithoutPendingEditsDoesNotClaimFixedBrokenState() = runBlocking {
        val store = FakeStore()
        val q = RecordingQueue(store).queue
        q.markBroken()
        assertTrue(q.saveFailed.value)

        q.retryPending()

        assertTrue("إعادة محاولة بلا عمل لا تدّعي إصلاحاً لم يحدث", q.saveFailed.value)
    }

    // ---- E2: فشل التحميل علم مستقل؛ نجاح الكتابة لا يمسحه ----
    @Test
    fun loadFailureSurvivesASuccessfulWrite() = runBlocking {
        val store = FakeStore()
        val q = RecordingQueue(store).queue
        q.markBroken() // فشل تحميل (preload)
        assertTrue(q.saveFailed.value)

        q.submit(DiskEdit.SetValue(str("k"), DiskEdit.SupportedValue.Str("v")))

        assertEquals("الكتابة نجحت", "v", store.data["k"])
        assertTrue(
            "نجاح الكتابة لا يخفي فشل التحميل: الإعدادات تعمل على الافتراضيات",
            q.saveFailed.value
        )
    }

    // ---- E2: إعادة التحميل الناجح (من retryPendingWrites) وحدها تمسح علم التحميل ----
    @Test
    fun clearLoadFailedClearsTheCombinedIndicator() = runBlocking {
        val store = FakeStore()
        val q = RecordingQueue(store).queue
        q.markBroken()
        assertTrue(q.saveFailed.value)

        // مسار إعادة المحاولة الناجح في AppSettings: ensureLoaded نجح ← مسح العلم.
        q.clearLoadFailed()

        assertFalse(q.saveFailed.value)
    }

    // ---- E2: فشل الكتابة المتكرر يبقى بعد فشل تحميل مُسح (العلمان مستقلان) ----
    @Test
    fun writeGiveUpRaisesIndicatorEvenAfterLoadWasCleared() = runBlocking {
        val store = FakeStore()
        val q = RecordingQueue(store).queue
        q.markBroken()
        q.clearLoadFailed()
        assertFalse(q.saveFailed.value)

        store.failAlways = true
        q.submit(DiskEdit.SetValue(str("k"), DiskEdit.SupportedValue.Str("v")))

        assertTrue("فشل الكتابة الفعلي (onGiveUp) هو ما يرفع المؤشر", q.saveFailed.value)
        assertEquals(1, q.pendingSnapshot().size)
    }
}

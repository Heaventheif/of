package org.openfilemanager.app.reader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderStorePptxFontMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private val K_DOCX = intPreferencesKey("docx_font_px")
    private val K_PPTX = intPreferencesKey("pptx_font_px")

    /** متجر فوق ملف مؤقت + الملف نفسه (لفحص المفاتيح مباشرة) + نطاق يُلغى بعد الاختبار. */
    private class Fixture private constructor(val store: ReaderStore, val ds: DataStore<Preferences>, val scope: CoroutineScope) {
        companion object {
            fun create(root: File): Fixture {
                val file = File(root, "reader-${UUID.randomUUID()}.preferences_pb")
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val ds = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
                return Fixture(ReaderStore.forDataStore(ds), ds, scope)
            }
        }

        fun close() = scope.cancel()
    }

    // ---- تثبيت جديد ----
    @Test
    fun freshInstallKeepsDefaultsAndWritesNothing() = runBlocking {
        val f = Fixture.create(tmp.root)
        try {
            val prefs = f.store.prefsNow()
            assertEquals(ReaderPrefs.DEFAULT_DOCX_FONT, prefs.pptxFontPx)
            assertEquals(ReaderPrefs.DEFAULT_DOCX_FONT, prefs.docxFontPx)
            // لا قيمة DOCX قديمة فلا هجرة: مفتاح PPTX لم يُكتب على القرص بعد.
            assertNull(f.ds.data.first()[K_PPTX])
        } finally {
            f.close()
        }
    }

    // ---- ترقية بقيمة قديمة: نسخة لمرة واحدة والقيمة القديمة تبقى ----
    @Test
    fun upgradeCopiesOldDocxFontOnceAndKeepsTheOldValue() = runBlocking {
        val f = Fixture.create(tmp.root)
        try {
            // «تركيبة قديمة»: مستخدم غيّر خط DOCX إلى 22 قبل التحديث.
            f.ds.edit { it[K_DOCX] = 22 }

            assertEquals("الهجرة تنسخ القيمة القديمة إلى PPTX", 22, f.store.prefsNow().pptxFontPx)
            assertEquals("قيمة DOCX لا تُحذف", 22, f.store.prefsNow().docxFontPx)

            // تغيير DOCX بعد الهجرة لا يعيد ربط PPTX (الهجرة مرة واحدة لا ربط حي).
            f.store.setDocxFont(14)
            assertEquals("الهجرة لا تتكرر: PPTX ثابت", 22, f.store.prefsNow().pptxFontPx)
            assertEquals(14, f.store.prefsNow().docxFontPx)
            assertTrue("القيمة المنسوخة موجودة على القرص", f.ds.data.first()[K_PPTX] == 22)
        } finally {
            f.close()
        }
    }

    // ---- الاستقلالية في الاتجاهين ----
    @Test
    fun pptxChangeDoesNotAffectDocxAndViceVersa() = runBlocking {
        val f = Fixture.create(tmp.root)
        try {
            f.ds.edit { it[K_DOCX] = 22 }
            assertEquals(22, f.store.prefsNow().pptxFontPx) // الهجرة

            f.store.setPptxFont(28)
            assertEquals("تغيير PPTX لا يلمس DOCX", 22, f.store.prefsNow().docxFontPx)
            assertEquals(28, f.store.prefsNow().pptxFontPx)

            f.store.setDocxFont(12)
            assertEquals("تغيير DOCX لا يلمس PPTX", 28, f.store.prefsNow().pptxFontPx)
            assertEquals(12, f.store.prefsNow().docxFontPx)
        } finally {
            f.close()
        }
    }
}

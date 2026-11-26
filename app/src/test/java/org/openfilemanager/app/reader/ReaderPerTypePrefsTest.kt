package org.openfilemanager.app.reader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openfilemanager.app.FeatureFlags

class ReaderPerTypePrefsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val K_THEME = stringPreferencesKey("theme")
    private val K_DOCX = intPreferencesKey("docx_font_px")
    private val K_PPTX = intPreferencesKey("pptx_font_px")
    private val K_PDF_ZOOM = floatPreferencesKey("pdf_zoom")
    private val K_PDF_DARK = booleanPreferencesKey("pdf_dark")
    private val K_TEXT = intPreferencesKey("text_editor_font_px")

    /** متجر فوق ملف مؤقت + الملف نفسه (لفحص المفاتيح مباشرة) + نطاق يُلغى بعد الاختبار. */
    private class Fixture private constructor(
        val store: ReaderStore,
        val ds: DataStore<Preferences>,
        val scope: CoroutineScope,
        /** محاكاة SharedPreferences «editor_style»/size — تتغير يدوياً بين المراحل. */
        val legacy: MutableList<Int?>
    ) {
        companion object {
            fun create(root: File, initialLegacy: Int? = null): Fixture {
                val file = File(root, "reader-${UUID.randomUUID()}.preferences_pb")
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val ds = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
                val legacy = mutableListOf<Int?>(initialLegacy)
                val store = ReaderStore.forDataStore(ds) { legacy.firstOrNull() }
                return Fixture(store, ds, scope, legacy)
            }
        }

        fun close() = scope.cancel()
    }

    // ---- تثبيت جديد: قيم افتراضية لكل الأنواع ولا مفتاح مكتوب على القرص ----
    @Test
    fun freshInstallKeepsDefaultsForEveryReaderTypeAndWritesNoKeys() = runBlocking {
        val f = Fixture.create(tmp.root) // المحرر لم يُفتح قط: لا قيمة قديمة
        try {
            val prefs = f.store.prefsNow()
            assertEquals(ReaderPrefs.DEFAULT_DOCX_FONT, prefs.docxFontPx)
            assertEquals(ReaderPrefs.DEFAULT_DOCX_FONT, prefs.pptxFontPx)
            assertEquals(1f, prefs.pdfZoom)
            assertEquals(false, prefs.pdfDark)
            assertEquals(ReaderPrefs.DEFAULT_TEXT_EDITOR_FONT, prefs.textEditorFontPx)
            // لا أي مفتاح مكتوب: تثبيت نظيف بلا هجرة ولا افتراضيات مسكوبة.
            assertTrue("تثبيت جديد يكتب صفر مفاتيح", f.ds.data.first().asMap().isEmpty())
        } finally {
            f.close()
        }
    }

    // ---- ترقية: القيمة القديمة للمحرر تُنسخ مرة واحدة والقديمة تُنسك ولا يُعاد الربط ----
    @Test
    fun upgradeMigratesLegacyTextEditorFontOnceOldValueRetainedAndNeverRebound() = runBlocking {
        val f = Fixture.create(tmp.root, initialLegacy = 20) // «تركيبة قديمة»: المستخدم ضبط حجم خط المحرر 20
        try {
            assertEquals("الهجرة تنسخ القيمة القديمة مرة واحدة", 20, f.store.prefsNow().textEditorFontPx)
            assertEquals("القيمة المنسوخة على القرص", 20, f.ds.data.first()[K_TEXT])
            // القيمة القديمة تبقى كما هي (المتجر يقرأها فقط — لا حذف ولا تعديل):
            // مصدرها المحقون لم يتغير بإجراء أي من عمليات المتجر.
            assertEquals(20, f.legacy.first())

            // تغيّر المصدر القديم بعد الهجرة لا يعيد الربط (هجرة لمرة واحدة لا ربط حي):
            // مثيل جديد فوق الملف نفسه — كأنه إقلاع بعد تحديث — يقرأ القيمة المنسوخة.
            f.legacy[0] = 24
            val reopened = ReaderStore.forDataStore(f.ds) { f.legacy.firstOrNull() }
            assertEquals("لا يعاد النسخ فوق القيمة الموجودة", 20, reopened.prefsNow().textEditorFontPx)
            assertEquals(20, f.ds.data.first()[K_TEXT])
        } finally {
            f.close()
        }
    }

    // ---- الهجرة خارج الحدود تُحصر بحدود المحرر نفسها (11..28) ----
    @Test
    fun migratedLegacyValueIsCoercedIntoTheEditorBounds() = runBlocking {
        val f = Fixture.create(tmp.root, initialLegacy = 40)
        try {
            assertEquals(ReaderPrefs.MAX_TEXT_EDITOR_FONT, f.store.prefsNow().textEditorFontPx)
        } finally {
            f.close()
        }
    }

    @Test
    fun migrationIsFullySkippedWhilePerReaderPrefsFlagIsOff() = runBlocking {
        FeatureFlags.perReaderPrefs = false
        val f = Fixture.create(tmp.root, initialLegacy = 22)
        try {
            assertEquals("العلم مطفأً: القيمة القديمة لا تُنسخ", ReaderPrefs.DEFAULT_TEXT_EDITOR_FONT, f.store.prefsNow().textEditorFontPx)
            assertTrue("العلم مطفأً: لا مفتاح text_editor_font_px على القرص", f.ds.data.first()[K_TEXT] == null)
            // تفعيل العلم لاحقاً (نفس المثيل): الهجرة تجري لأنها لم تُحاول من قبل.
            FeatureFlags.perReaderPrefs = true
            assertEquals(22, f.store.prefsNow().textEditorFontPx)
            assertEquals(22, f.ds.data.first()[K_TEXT])
        } finally {
            FeatureFlags.perReaderPrefs = true
            f.close()
        }
    }

    // ---- لا تسريب متقاطع في الاتجاهين بين كل الأنواع ----
    @Test
    fun noCrossReaderLeakageBetweenTextDocxPptxAndPdfInBothDirections() = runBlocking {
        val f = Fixture.create(tmp.root)
        try {
            // نص → الآخرون
            f.store.setTextEditorFont(24)
            assertEquals(ReaderPrefs.DEFAULT_DOCX_FONT, f.store.prefsNow().docxFontPx)
            assertEquals(ReaderPrefs.DEFAULT_DOCX_FONT, f.store.prefsNow().pptxFontPx)
            assertEquals(1f, f.store.prefsNow().pdfZoom)
            assertEquals(24, f.store.prefsNow().textEditorFontPx)
            // DOCX → النص وPDF وPPTX
            f.store.setDocxFont(13)
            assertEquals(24, f.store.prefsNow().textEditorFontPx)
            assertEquals(13, f.store.prefsNow().docxFontPx)
            assertEquals(ReaderPrefs.DEFAULT_DOCX_FONT, f.store.prefsNow().pptxFontPx)
            assertEquals(1f, f.store.prefsNow().pdfZoom)
            // PDF → النص وDOCX
            f.store.setPdfZoom(2f)
            f.store.setPdfDark(true)
            assertEquals(24, f.store.prefsNow().textEditorFontPx)
            assertEquals(13, f.store.prefsNow().docxFontPx)
            assertEquals(2f, f.store.prefsNow().pdfZoom)
            f.store.setPptxFont(30)
            assertEquals(24, f.store.prefsNow().textEditorFontPx)
            assertEquals(13, f.store.prefsNow().docxFontPx)
            assertEquals(30, f.store.prefsNow().pptxFontPx)
            assertEquals(2f, f.store.prefsNow().pdfZoom)
            // والقرص يطابق: كل مفتاح بقيمته الخاصة بلا تسريب.
            val disk = f.ds.data.first()
            assertEquals(24, disk[K_TEXT])
            assertEquals(13, disk[K_DOCX])
            assertEquals(30, disk[K_PPTX])
            assertEquals(2f, disk[K_PDF_ZOOM])
            assertEquals(true, disk[K_PDF_DARK])
            assertEquals(null, disk[K_THEME])
        } finally {
            f.close()
        }
    }

    @Test
    fun pptxMigrationFromDocxStillWorksAlongsideTheNewTextKey() = runBlocking {
        val f = Fixture.create(tmp.root)
        try {
            f.ds.edit { it[K_DOCX] = 26 }
            val prefs = f.store.prefsNow()
            assertEquals("هجرة D8 تنسخ DOCX إلى PPTX كما كانت", 26, prefs.pptxFontPx)
            assertEquals(26, prefs.docxFontPx)
            assertEquals("مفتاح المحرر الجديد بلا مصدر قديم يبقى افتراضياً", ReaderPrefs.DEFAULT_TEXT_EDITOR_FONT, prefs.textEditorFontPx)
            assertEquals(26, f.ds.data.first()[K_PPTX])
            assertEquals(null, f.ds.data.first()[K_TEXT])
        } finally {
            f.close()
        }
    }
}

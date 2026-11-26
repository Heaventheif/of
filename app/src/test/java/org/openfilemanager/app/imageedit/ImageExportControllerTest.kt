package org.openfilemanager.app.imageedit

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.openfilemanager.app.imageedit.engine.ImageExporter
import org.openfilemanager.app.imageedit.model.CutOp
import org.openfilemanager.app.imageedit.model.EditLayer
import org.openfilemanager.app.imageedit.model.ExportFormat
import org.openfilemanager.core.imageedit.ImageTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** نطاق بسيط للاختبارات — Unconfined dispatcher يجعل الكوروتينات متزامنة. */
private fun testScope() = CoroutineScope(SupervisorJob() + EmptyCoroutineContext)

/** اختبارات [ImageExportController] على JVM خالص — عمليات التصدير محقونة كدوال وهمية فلا حاجة لـ Context أو Bitmap. */
class ImageExportControllerTest {

    // ---- عدّادات وهمية للحدود ----

    private class Harness {
        var saving = false
        val flagLog = mutableListOf<Boolean>()
        val events = mutableListOf<EditorEvent>()
        var opInvocations = 0
        var lastSnapshot: ImageExportController.ExportSnapshot? = null
        var lastStateReadAtOp = 0

        var stateReads = 0

        fun getState(): EditorUiState {
            stateReads++
            return EditorUiState(saving = saving, name = "test.jpg")
        }

        fun controller(
            result: ImageExporter.ExportResult,
            dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
            saveOp: (suspend (ImageExportController.ExportSnapshot, String, ExportFormat, Int, Float) -> ImageExporter.ExportResult)? = null
        ): ImageExportController = ImageExportController(
            scope = testScope(),
            imagePath = "/tmp/test.jpg",
            getState = { getState() },
            setSaving = { v -> saving = v; flagLog += v },
            emit = { events += it },
            dispatcher = dispatcher,
            saveFailedRes = 101,
            replaceFailedRes = 202,
            saveAsCopyOp = { snap, name, format, quality, scale ->
                opInvocations++
                lastSnapshot = snap
                lastStateReadAtOp = stateReads
                (saveOp ?: { _, _, _, _, _ -> result })(snap, name, format, quality, scale)
            },
            replaceOriginalOp = { snap, _ ->
                opInvocations++
                lastSnapshot = snap
                result
            }
        )

        companion object {
            val OK = ImageExporter.ExportResult(ok = true, path = "/tmp/out.jpg", error = null)
            val OK_NULL_PATH = ImageExporter.ExportResult(ok = true, path = null, error = null)
            val FAILED = ImageExporter.ExportResult(ok = false, path = null, error = 5)
        }
    }

    // ---- saveCopy ----

    @Test
    fun saveCopySuccessTogglesFlagAndEmitsSavedWithPath() = runBlocking {
        val h = Harness()
        val ctl = h.controller(Harness.OK)
        ctl.saveCopy("out", ExportFormat.JPEG, 90, 1f)
        // العلم: true قبل العملية ثم false بعدها.
        assertEquals(listOf(true, false), h.flagLog)
        // الحدث: Saved بالمسار.
        assertEquals(listOf<EditorEvent>(EditorEvent.Saved("/tmp/out.jpg")), h.events)
        assertEquals(1, h.opInvocations)
        assertTrue(h.lastSnapshot != null)
    }

    @Test
    fun saveCopySuccessWithNullPathEmitsEmptyPath() = runBlocking {
        val h = Harness()
        h.controller(Harness.OK_NULL_PATH).saveCopy("out", ExportFormat.JPEG, 90, 1f)
        assertEquals(listOf<EditorEvent>(EditorEvent.Saved("")), h.events)
    }

    @Test
    fun saveCopyFailureEmitsFailedWithSaveRes() = runBlocking {
        val h = Harness()
        h.controller(Harness.FAILED).saveCopy("out", ExportFormat.JPEG, 90, 1f)
        assertEquals(listOf<EditorEvent>(EditorEvent.Failed(101)), h.events)
        // العلم يعود false حتى عند الفشل.
        assertEquals(listOf(true, false), h.flagLog)
    }

    @Test
    fun saveCopySkippedWhenAlreadySaving() = runBlocking {
        val h = Harness()
        h.saving = true // الحالة الحالية «جاري الحفظ»
        h.controller(Harness.OK).saveCopy("out", ExportFormat.JPEG, 90, 1f)
        // لا عملية ولا أحداث ولا قلب علم.
        assertEquals(0, h.opInvocations)
        assertEquals(0, h.events.size)
        assertEquals(0, h.flagLog.size)
    }

    @Test
    fun saveCopyReadsStateSnapshotInsideOperation() = runBlocking {
        // (تصحيح v5) التسلسل الأصلي: علم saving=true يُضبط قبل الإطلاق، ولقطة
        // الحالة تُقرأ داخل الكوروتين — أي أن العملية ترى saving=true وقت التنفيذ.
        // مع Unconfined يعمل جسم الكوروتين تزامنياً فالترتيب حتمي.
        var savingAtOpTime: Boolean? = null
        var opRan = false
        val h = Harness()
        val ctl = ImageExportController(
            scope = testScope(),
            imagePath = "/tmp/test.jpg",
            getState = { EditorUiState(saving = h.saving, name = "test.jpg") },
            setSaving = { v -> h.saving = v },
            emit = { h.events += it },
            dispatcher = Dispatchers.Unconfined,
            saveFailedRes = 101,
            replaceFailedRes = 202,
            saveAsCopyOp = { snap, _, _, _, _ ->
                opRan = true
                savingAtOpTime = h.saving
                Harness.OK
            },
            replaceOriginalOp = { _, _ -> Harness.OK }
        )
        ctl.saveCopy("out", ExportFormat.JPEG, 90, 1f)
        assertTrue("Export operation must run", opRan)
        // اللقطة قُرئت بعد ضبط العلم (داخل الكوروتين) لا قبله.
        assertEquals(true, savingAtOpTime)
        assertEquals(listOf<EditorEvent>(EditorEvent.Saved("/tmp/out.jpg")), h.events)
    }

    @Test
    fun saveCopySnapshotCarriesEditorState() = runBlocking {
        val h = Harness()
        var captured: ImageExportController.ExportSnapshot? = null
        val ctl = ImageExportController(
            scope = testScope(),
            imagePath = "/tmp/test.jpg",
            getState = { EditorUiState(saving = false, transform = ImageTransform().withFreeRotation(45f)) },
            setSaving = { h.saving = it },
            emit = { h.events += it },
            dispatcher = Dispatchers.Unconfined,
            saveFailedRes = 101,
            replaceFailedRes = 202,
            saveAsCopyOp = { snap, _, _, _, _ ->
                captured = snap
                Harness.OK
            },
            replaceOriginalOp = { _, _ -> Harness.OK }
        )
        ctl.saveCopy("out", ExportFormat.JPEG, 90, 1f)
        assertEquals(45f, captured!!.transform.freeRotation, 0.001f)
        assertEquals(emptyList<CutOp>(), captured!!.cutOps)
        assertEquals(emptyList<EditLayer>(), captured!!.layers)
    }

    // ---- replaceOriginal ----

    @Test
    fun replaceOriginalSuccessEmitsSaved() = runBlocking {
        val h = Harness()
        h.controller(Harness.OK).replaceOriginal(90)
        assertEquals(listOf<EditorEvent>(EditorEvent.Saved("/tmp/out.jpg")), h.events)
        assertEquals(listOf(true, false), h.flagLog)
    }

    @Test
    fun replaceOriginalFailureEmitsFailedWithReplaceRes() = runBlocking {
        val h = Harness()
        h.controller(Harness.FAILED).replaceOriginal(90)
        assertEquals(listOf<EditorEvent>(EditorEvent.Failed(202)), h.events)
        assertEquals(listOf(true, false), h.flagLog)
    }

    @Test
    fun replaceOriginalSkippedWhenAlreadySaving() = runBlocking {
        val h = Harness()
        h.saving = true
        h.controller(Harness.OK).replaceOriginal(90)
        assertEquals(0, h.opInvocations)
        assertEquals(0, h.events.size)
        assertEquals(0, h.flagLog.size)
    }
}

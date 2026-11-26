package org.openfilemanager.app.imageedit

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.openfilemanager.app.AppDispatchers
import org.openfilemanager.app.R
import org.openfilemanager.app.imageedit.engine.ImageExporter
import org.openfilemanager.app.imageedit.model.CutOp
import org.openfilemanager.app.imageedit.model.EditLayer
import org.openfilemanager.app.imageedit.model.ExportFormat
import org.openfilemanager.core.imageedit.Adjust
import org.openfilemanager.core.imageedit.ImageTransform

/** منسق التصدير: يملك تدفق الحفظ/الاستبدال كاملاً — حارس «جاري الحفظ»، وقلب العلم حول العملية، وقراءة لقطة الحالة داخل الكوروتين، وترجمة النتيجة إلى حدث. */
class ImageExportController(
    private val scope: CoroutineScope,
    private val imagePath: String,
    private val getState: () -> EditorUiState,
    private val setSaving: (Boolean) -> Unit,
    private val emit: (EditorEvent) -> Unit,
    private val dispatcher: CoroutineDispatcher = AppDispatchers.io,
    /** مورد رسالة فشل «حفظ كنسخة» — يُمرَّر من ViewModel لأن R غير متاح باختبارات JVM. */
    private val saveFailedRes: Int,
    /** مورد رسالة فشل «استبدال الأصل». */
    private val replaceFailedRes: Int,
    private val saveAsCopyOp: suspend (
        snapshot: ExportSnapshot, baseName: String, format: ExportFormat, quality: Int, scale: Float
    ) -> ImageExporter.ExportResult,
    private val replaceOriginalOp: suspend (
        snapshot: ExportSnapshot, quality: Int
    ) -> ImageExporter.ExportResult,
) {

    /** لقطة نقية مما يحتاجه التصدير من حالة الجلسة — بلا أندرويد فتصلح للاختبارات. */
    data class ExportSnapshot(
        val transform: ImageTransform,
        val adjust: Adjust,
        val layers: List<EditLayer>,
        val cutOps: List<CutOp>
    )

    private fun snapshotOf(s: EditorUiState) =
        ExportSnapshot(transform = s.transform, adjust = s.adjust, layers = s.layers, cutOps = s.cutOps)

    /** @param scale تغيير حجم المخرَج: 1 = القرار الأصلي (بعد القصّ)، وأقل للتصغير. */
    fun saveCopy(baseName: String, format: ExportFormat, quality: Int, scale: Float = 1f) {
        if (getState().saving) return
        setSaving(true)
        scope.launch(dispatcher) {
            val snapshot = snapshotOf(getState())
            val result = saveAsCopyOp(snapshot, baseName, format, quality, scale)
            setSaving(false)
            if (result.ok) emit(EditorEvent.Saved(result.path ?: ""))
            else emit(EditorEvent.Failed(saveFailedRes))
        }
    }

    fun replaceOriginal(quality: Int) {
        if (getState().saving) return
        setSaving(true)
        scope.launch(dispatcher) {
            val snapshot = snapshotOf(getState())
            val result = replaceOriginalOp(snapshot, quality)
            setSaving(false)
            if (result.ok) emit(EditorEvent.Saved(result.path ?: ""))
            else emit(EditorEvent.Failed(replaceFailedRes))
        }
    }
}

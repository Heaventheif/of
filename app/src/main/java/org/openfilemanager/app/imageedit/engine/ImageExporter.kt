package org.openfilemanager.app.imageedit.engine

import android.content.Context
import android.graphics.Bitmap
import org.openfilemanager.app.AppDispatchers
import org.openfilemanager.app.MediaLibrary
import org.openfilemanager.app.imageedit.model.CutOp
import org.openfilemanager.app.imageedit.model.EditLayer
import org.openfilemanager.app.imageedit.model.ExportFormat
import org.openfilemanager.core.imageedit.Adjust
import org.openfilemanager.core.imageedit.ImageTransform
import org.openfilemanager.core.AtomicIO
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.max

/** تصدير الصورة المحرّرة: يفك الأصل بقراره الكامل (بسقف أمان للذاكرة)، يرسم الحالة كاملة، ثم يرمّز ويكتب كتابة ذرّية عبر AtomicIO.atomicWrite — فلا. */
object ImageExporter {

    /** سقف أمان أبعاد التصدير — بعده يُقلَّص القرار حفاظاً على الذاكرة. */
    private const val EXPORT_CAP = 8192

    data class ExportResult(val ok: Boolean, val path: String?, val error: Int?)

    /** يفك الأصل بدقته الكاملة، يطبّق القص ثم يرسم الحالة؛ يعيد null إن تعذّر الفك. */
    private fun renderDocument(
        src: Path,
        transform: ImageTransform,
        layers: List<EditLayer>,
        cutOps: List<CutOp>,
        format: ExportFormat,
        adjust: Adjust,
        scale: Float
    ): Bitmap? {
        val bounds = ImageDecoder.readBounds(src) ?: return null
        val (base, _) = ImageDecoder.decodeForExport(src, bounds.exifOrientation, EXPORT_CAP) ?: return null
        val docScale = base.width.toFloat() / bounds.width
        val cutBase = if (cutOps.isEmpty()) base else CutEngine.apply(base, cutOps, bounds.width.toFloat())
        val rendered = ImageRenderer.render(
            cutBase, transform, layers, docScale * scale.coerceIn(0.05f, 1f),
            opaqueBackground = format == ExportFormat.JPEG, adjust = adjust
        )
        if (cutBase !== base) cutBase.recycle()
        base.recycle()
        return rendered
    }

    /** توليد اسم غير مستخدم في نفس المجلد: name.jpg ثم name (1).jpg ... */
    fun uniqueName(dir: Path, baseName: String, extension: String): Path {
        var candidate = dir.resolve("$baseName.$extension")
        var i = 1
        while (Files.exists(candidate)) {
            candidate = dir.resolve("$baseName ($i).$extension")
            i++
        }
        return candidate
    }

    /** اقتراح الاسم الافتراضي للنسخة الجديدة: <الاسم الأصلي>_edited */
    fun defaultCopyName(originalPath: String): String {
        val file = Paths.get(originalPath).fileName?.toString() ?: "image"
        val dot = file.lastIndexOf('.')
        val base = if (dot > 0) file.substring(0, dot) else file
        return "${base}_edited"
    }

    /** حفظ نسخة جديدة بجانب الأصل. */
    suspend fun saveAsCopy(
        context: Context,
        originalPath: String,
        transform: ImageTransform,
        layers: List<EditLayer>,
        cutOps: List<CutOp>,
        baseName: String,
        format: ExportFormat,
        quality: Int,
        adjust: Adjust = Adjust(),
        scale: Float = 1f
    ): ExportResult = withContext(AppDispatchers.io) {
        try {
            val src = Paths.get(originalPath)
            val rendered = renderDocument(src, transform, layers, cutOps, format, adjust, scale)
                ?: return@withContext ExportResult(false, null, null)
            val target = uniqueName(src.parent ?: Paths.get("."), baseName, format.extension)
            val ok = writeBitmap(rendered, target, format, quality)
            rendered.recycle()
            if (!ok) return@withContext ExportResult(false, null, null)
            MediaLibrary(context).scanFiles(listOf(target.toString()))
            ExportResult(true, target.toString(), null)
        } catch (e: Exception) {
            ExportResult(false, null, null)
        }
    }

    /**
     * استبدال الأصل (بنفس صيغته) بعد تأكيد صريح من المستخدم. الكتابة ذرّية،
     * ولا يُسمح بها إلا للصيغ القابلة للترميز بأمان (JPEG/PNG/WebP).
     */
    suspend fun replaceOriginal(
        context: Context,
        originalPath: String,
        transform: ImageTransform,
        layers: List<EditLayer>,
        cutOps: List<CutOp>,
        quality: Int,
        adjust: Adjust = Adjust()
    ): ExportResult = withContext(AppDispatchers.io) {
        try {
            val src = Paths.get(originalPath)
            val ext = src.fileName?.toString()?.substringAfterLast('.')?.lowercase() ?: ""
            val format = when (ext) {
                "jpg", "jpeg" -> ExportFormat.JPEG
                "png" -> ExportFormat.PNG
                "webp" -> ExportFormat.WEBP
                else -> return@withContext ExportResult(false, null, null) // HEIC وغيرها لا تُستبدل بصيغة أخرى
            }
            val rendered = renderDocument(src, transform, layers, cutOps, format, adjust, 1f)
                ?: return@withContext ExportResult(false, null, null)
            val ok = writeBitmap(rendered, src, format, quality)
            rendered.recycle()
            if (!ok) return@withContext ExportResult(false, null, null)
            MediaLibrary(context).scanFiles(listOf(src.toString()))
            ExportResult(true, src.toString(), null)
        } catch (e: Exception) {
            ExportResult(false, null, null)
        }
    }

    private fun writeBitmap(bitmap: Bitmap, target: Path, format: ExportFormat, quality: Int): Boolean {
        val buffer = ByteArrayOutputStream(max(1 shl 20, bitmap.byteCount / 2))
        val compressed = bitmap.compress(format.compressFormat, quality.coerceIn(10, 100), buffer)
        if (!compressed) return false
        val bytes = buffer.toByteArray()
        if (bytes.isEmpty()) return false
        AtomicIO.atomicWrite(target) { it.write(bytes) }
        return true
    }
}

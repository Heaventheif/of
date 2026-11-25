package org.openfilemanager.app.imageedit.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import org.openfilemanager.app.AppDispatchers
import org.openfilemanager.core.ExifOrientation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max

/**
 * فك ترميز الصور للمحرّر: يقرأ الأبعاد والـEXIF أولاً بلا فك كامل، ثم يفك
 * معاينة مصغّرة للتحرير، ولا يُفك القرار الكامل إلا عند التصدير. لا يُفك
 * الأصل مراراً أثناء التحرير — المعاينة تُخزَّن مرة واحدة في الجلسة.
 */
object ImageDecoder {

    data class Bounds(val width: Int, val height: Int, val exifOrientation: Int)

    /** أبعاد الصورة المعترضة بعد تطبيق اتجاه EXIF — بلا فك ترميز كامل. */
    fun readBounds(path: Path): Bounds? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path.toString(), opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val orientation = runCatching { ExifOrientation.read(path) }.getOrDefault(1)
        val swap = orientation in 5..8
        val w = if (swap) opts.outHeight else opts.outWidth
        val h = if (swap) opts.outWidth else opts.outHeight
        return Bounds(w, h, orientation)
    }

    /**
     * فك معاينة لا يتجاوز ضلعها الأكبر [maxDim]، مع تطبيق اتجاه EXIF.
     * يعاد null عند الفشل. يعمل على الخيط الذي استُدعي فيه — نادِه من io.
     */
    fun decodePreview(path: Path, exifOrientation: Int, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path.toString(), opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeFile(path.toString(), decodeOpts) ?: return null
        val oriented = applyExif(raw, exifOrientation)
        return if (oriented !== raw) { raw.recycle(); oriented } else raw
    }

    /**
     * فك الصورة بالقرار الكامل للتصدير، مع سقف أمان للذاكرة [capDim].
     * يعاد البت ماب مع معامل التقليص المستخدم (1f = قرار كامل).
     */
    fun decodeForExport(path: Path, exifOrientation: Int, capDim: Int): Pair<Bitmap, Float>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path.toString(), opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= capDim) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888 // شفافية PNG عند الدوران الحر
        }
        val raw = BitmapFactory.decodeFile(path.toString(), decodeOpts) ?: return null
        val oriented = applyExif(raw, exifOrientation)
        if (oriented !== raw) raw.recycle()
        return Pair(oriented, 1f / sample)
    }

    /** فك صورة تراكب مضافة فوق الأصل بسقف معقول للذاكرة. */
    fun decodeOverlay(context: Context, uriString: String, maxDim: Int = 2048): Bitmap? {
        return runCatching {
            val resolver = context.contentResolver
            val uri = android.net.Uri.parse(uriString)
            val bounds = resolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, opts)
                opts
            } ?: return null
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use { s2 ->
                BitmapFactory.decodeStream(
                    s2, null, BitmapFactory.Options().apply { inSampleSize = sample }
                )
            } ?: return null
            decoded
        }.getOrNull()
    }

    /** تطبيق اتجاه EXIF (تدوير + قلب معاكس حيث يلزم) — يعاد نفس الكائن إن لا شيء يلزم. */
    private fun applyExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> { matrix.setRotate(180f); matrix.postScale(-1f, 1f) }
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** هل الملف صورة ساكنة يفتحها فك الترميز؟ (لعرض إجراء التعديل أو إخفائه) */
    fun canDecode(path: Path): Boolean = Files.isReadable(path) && readBounds(path) != null
}

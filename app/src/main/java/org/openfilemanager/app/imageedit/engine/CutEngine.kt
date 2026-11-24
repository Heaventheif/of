package org.openfilemanager.app.imageedit.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.nio.ByteBuffer
import org.openfilemanager.app.imageedit.model.CutOp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * محرّك القص: يطبّق [CutOp] على نسخة من الصورة الأساس فيجعل البكسلات المقصوصة شفافة.
 * - الحدود الحرة تُرسم بمسار مضاد للتعرّج (DST_OUT).
 * - «العصا السحرية» تحلّل صورة عمل لا يتجاوز ضلعها [WORK_MAX] فتبقى الذاكرة محدودة حتى في
 *   التصدير بدقة كاملة، ثم تُكبَّر القناع بتنعيم فتخرج الحواف ناعمة وبنفس نتيجة المعاينة.
 */
object CutEngine {
    private const val WORK_MAX = 2048
    private const val MIN_ALPHA = 16

    /** يعيد نسخة جديدة؛ [src] لا يُعدَّل. [docW] عرض المستند لحساب المقياس. */
    fun apply(src: Bitmap, ops: List<CutOp>, docW: Float): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        if (ops.isEmpty() || docW <= 0f) return out
        val s = out.width / docW
        val canvas = Canvas(out)
        for (op in ops) {
            when (op) {
                is CutOp.Keep -> polygon(canvas, op.points, s, keepInside = true)
                is CutOp.Erase -> polygon(canvas, op.points, s, keepInside = false)
                is CutOp.Wand -> wand(out, canvas, op, s)
            }
        }
        return out
    }

    private fun polygon(canvas: Canvas, pts: List<Pair<Float, Float>>, s: Float, keepInside: Boolean) {
        if (pts.size < 3) return
        val path = Path()
        pts.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(p.first * s, p.second * s) else path.lineTo(p.first * s, p.second * s)
        }
        path.close()
        // WINDING: حدود فيها تقاطع ذاتي (رقم 8) لا تترك ثقوباً غير متوقعة
        path.fillType = if (keepInside) Path.FillType.INVERSE_WINDING else Path.FillType.WINDING
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        canvas.drawPath(path, paint)
    }

    private fun wand(out: Bitmap, canvas: Canvas, op: CutOp.Wand, s: Float) {
        val wk = min(1f, WORK_MAX / max(out.width, out.height).toFloat())
        val ww = max(1, (out.width * wk).roundToInt())
        val wh = max(1, (out.height * wk).roundToInt())
        val work = if (wk < 1f) Bitmap.createScaledBitmap(out, ww, wh, true) else out
        val px = IntArray(ww * wh)
        work.getPixels(px, 0, ww, 0, 0, ww, wh)
        if (work !== out) work.recycle()

        val sx = (op.x * s * wk).toInt().coerceIn(0, ww - 1)
        val sy = (op.y * s * wk).toInt().coerceIn(0, wh - 1)
        val seed = px[sy * ww + sx]
        if ((seed ushr 24) < MIN_ALPHA) return // البكسل ممسوح أصلاً

        val thr = (op.tolerance.coerceIn(0, 100) * 442) / 100 // أقصى مسافة لونية RGB ≈ 441.7
        val thr2 = thr * thr
        val sr = (seed shr 16) and 0xFF
        val sg = (seed shr 8) and 0xFF
        val sb = seed and 0xFF

        val mask = ByteArray(ww * wh)
        val queue = IntArray(ww * wh)
        var head = 0
        var tail = 0
        val start = sy * ww + sx
        mask[start] = 1
        queue[tail++] = start

        fun similar(c: Int): Boolean {
            if ((c ushr 24) < MIN_ALPHA) return false
            val dr = ((c shr 16) and 0xFF) - sr
            val dg = ((c shr 8) and 0xFF) - sg
            val db = (c and 0xFF) - sb
            return dr * dr + dg * dg + db * db <= thr2
        }

        while (head < tail) {
            val i = queue[head++]
            val x = i % ww
            val y = i / ww
            if (x > 0) { val n = i - 1; if (mask[n].toInt() == 0 && similar(px[n])) { mask[n] = 1; queue[tail++] = n } }
            if (x < ww - 1) { val n = i + 1; if (mask[n].toInt() == 0 && similar(px[n])) { mask[n] = 1; queue[tail++] = n } }
            if (y > 0) { val n = i - ww; if (mask[n].toInt() == 0 && similar(px[n])) { mask[n] = 1; queue[tail++] = n } }
            if (y < wh - 1) { val n = i + ww; if (mask[n].toInt() == 0 && similar(px[n])) { mask[n] = 1; queue[tail++] = n } }
        }

        // قناع ألفا: 255 حيث يُمسح
        val bytes = ByteArray(ww * wh) { if (mask[it].toInt() != 0) 0xFF.toByte() else 0 }
        val maskBmp = Bitmap.createBitmap(ww, wh, Bitmap.Config.ALPHA_8)
        maskBmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        canvas.drawBitmap(maskBmp, null, Rect(0, 0, out.width, out.height), paint)
        maskBmp.recycle()
    }
}

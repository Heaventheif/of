package org.openfilemanager.app.imageedit.engine

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import org.openfilemanager.app.imageedit.model.CutOp
import org.openfilemanager.core.imageedit.CutAlgorithms

/**
 * محرّك القص: يطبّق [CutOp] على نسخة من الصورة الأساس فيجعل البكسلات المقصوصة
 * شفافة. الصورة الأصلية ([original]) لا تُعدَّل وتُقرأ منها البكسلات عند
 * الاستعادة والموزاييك.
 *
 * - عمليات الحدود (حر/شكل/عصا/تلقائي) كلها تُنتج قناعاً ثنائياً بدقة عمل لا
 *   يتجاوز ضلعها [WORK_MAX] فتبقى الذاكرة محدودة حتى في التصدير بدقة كاملة، ثم
 *   يُنعَّم القناع (تنعيم الحافة) ويُرسم مُكبَّراً بترشيح فتخرج الحواف ناعمة وبنفس
 *   نتيجة المعاينة.
 * - الممحاة تُرسم بمسار مستدير الأطراف مباشرة؛ والاستعادة والموزاييك تُقصّان
 *   إلى محيط الضربة (الهيئة المملوءة للمسار المرسوم بسماكة الفرشاة).
 */
object CutEngine {
    private const val WORK_MAX = 2048

    /** آخر نسخة مبكسلة صُنعت للموزاييك (تُعاد بين إطارات الضربة الواحدة). */
    private var pixelCache: Triple<Bitmap, Int, Bitmap>? = null

    /** يعيد نسخة جديدة؛ [src] لا يُعدَّل. [docW] عرض المستند لحساب المقياس. */
    fun apply(src: Bitmap, ops: List<CutOp>, docW: Float, original: Bitmap = src): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        applyInPlace(out, original, ops, docW)
        return out
    }

    /** يطبّق العمليات على [out] مباشرة؛ [original] الصورة قبل أي قصّ (قد تكون هي [out]). */
    fun applyInPlace(out: Bitmap, original: Bitmap, ops: List<CutOp>, docW: Float) {
        if (ops.isEmpty() || docW <= 0f || out.width <= 0 || out.height <= 0) return
        val s = out.width / docW
        val canvas = Canvas(out)
        for (op in ops) {
            when (op) {
                is CutOp.Keep -> eraseMask(canvas, out, { ww, wh, k ->
                    CutAlgorithms.fillPolygon(scale(op.points, k * s), ww, wh, invert = true)
                }, op.feather)
                is CutOp.Erase -> eraseMask(canvas, out, { ww, wh, k ->
                    CutAlgorithms.fillPolygon(scale(op.points, k * s), ww, wh)
                }, op.feather)
                is CutOp.Wand -> eraseMask(canvas, out, { ww, wh, k ->
                    val px = pixels(out, ww, wh, k)
                    val sx = (op.x * s * k).toInt().coerceIn(0, ww - 1)
                    val sy = (op.y * s * k).toInt().coerceIn(0, wh - 1)
                    val seed = sy * ww + sx
                    CutAlgorithms.flood(px, ww, wh, intArrayOf(seed), px[seed], op.tolerance, op.edgeStop)
                }, op.feather)
                is CutOp.AutoBg -> eraseMask(canvas, out, { ww, wh, k ->
                    CutAlgorithms.autoBackground(pixels(out, ww, wh, k), ww, wh, op.tolerance, op.edgeStop)
                }, op.feather)
                is CutOp.EraseStroke ->
                    canvas.drawPath(strokePath(op.points, s), strokePaint(op.widthDoc * s, op.hardness))
                is CutOp.Restore -> {
                    val outline = strokeOutline(scale(op.points, s), op.widthDoc * s)
                    canvas.save()
                    canvas.clipPath(outline)
                    canvas.drawBitmap(original, 0f, 0f, null)
                    canvas.restore()
                }
                is CutOp.Pixelate -> {
                    val cell = max(2, (docW * op.cellFrac * s).roundToInt())
                    val outline = strokeOutline(scale(op.points, s), op.widthDoc * s)
                    val paint = Paint().apply { isFilterBitmap = false }
                    canvas.save()
                    canvas.clipPath(outline)
                    canvas.drawBitmap(pixelated(original, cell), null, Rect(0, 0, out.width, out.height), paint)
                    canvas.restore()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // الأقنعة

    /** أبعاد صورة العمل: الصورة نفسها إن كانت صغيرة، وإلا مُخفَّضة إلى [WORK_MAX]. */
    private fun workSize(b: Bitmap): Pair<Int, Int> {
        val k = if (max(b.width, b.height) <= WORK_MAX) 1f else WORK_MAX / max(b.width, b.height).toFloat()
        return Pair(max(1, (b.width * k).roundToInt()), max(1, (b.height * k).roundToInt()))
    }

    /**
     * يبني قناع الإزالة بدقة العمل ([build] يُنادى بأبعاد العمل ومقياس التخفيض k
     * الذي يحوّل بكسل الصورة إلى بكسل قناع)، ينعّمه ثم يرسمه بـ DST_OUT.
     */
    private inline fun eraseMask(
        canvas: Canvas, out: Bitmap,
        build: (ww: Int, wh: Int, k: Float) -> BooleanArray,
        feather: Int
    ) {
        val (ww, wh) = workSize(out)
        val k = ww.toFloat() / out.width
        val mask = build(ww, wh, k)
        val alpha = CutAlgorithms.soften(mask, ww, wh, CutAlgorithms.featherRadius(feather, max(ww, wh)))
        val maskBmp = Bitmap.createBitmap(ww, wh, Bitmap.Config.ALPHA_8)
        maskBmp.copyPixelsFromBuffer(ByteBuffer.wrap(alpha))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        canvas.drawBitmap(maskBmp, null, Rect(0, 0, out.width, out.height), paint)
        maskBmp.recycle()
    }

    /** بكسلات الصورة بدقة العمل (نسخة مُصغَّرة بترشيح عند الحاجة ثم تُحرَّر). */
    private fun pixels(b: Bitmap, ww: Int, wh: Int, k: Float): IntArray {
        val work = if (k < 1f) Bitmap.createScaledBitmap(b, ww, wh, true) else b
        val px = IntArray(ww * wh)
        work.getPixels(px, 0, ww, 0, 0, ww, wh)
        if (work !== b) work.recycle()
        return px
    }

    private fun scale(points: List<Pair<Float, Float>>, k: Float): List<Pair<Float, Float>> =
        points.map { Pair(it.first * k, it.second * k) }

    // ------------------------------------------------------------------
    // ضربات الفرشاة

    private fun strokePath(points: List<Pair<Float, Float>>, s: Float): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].first * s, points[0].second * s)
        for (i in 1 until points.size) path.lineTo(points[i].first * s, points[i].second * s)
        return path
    }

    private fun strokePaint(widthPx: Float, hardness: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = widthPx.coerceAtLeast(0.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        val soft = (100 - hardness.coerceIn(0, 100)) / 100f
        if (soft > 0f) maskFilter = BlurMaskFilter((widthPx * soft / 2f).coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    /**
     * المحيط المملوء لضربة (الهيئة التي يغطيها المسار المرسوم بسماكة الفرشاة)
     * ليصلح للقصّ إليه. `getFillPath` يحوّل الرسم بالسماكة إلى مسار مملوء؛ إن
     * رفض (ضربة منحلة) تُبنى الهيئة من أقراص عند النقاط.
     */
    private fun strokeOutline(points: List<Pair<Float, Float>>, widthPx: Float): Path {
        val r = widthPx.coerceAtLeast(0.5f) / 2f
        val src = strokePath(points, 1f)
        val outline = Path()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = r * 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val ok = runCatching { paint.getFillPath(src, outline) }.getOrDefault(false)
        if (!ok || outline.isEmpty) {
            outline.rewind()
            for (p in points) outline.addCircle(p.first, p.second, r, Path.Direction.CW)
        } else if (points.size == 1) {
            // ضربة من نقطة واحدة: القرص هو الهيئة كلها
            outline.rewind()
            outline.addCircle(points[0].first, points[0].second, r, Path.Direction.CW)
        }
        return outline
    }

    /** نسخة مبكسلة من الأصل: كل بلوكة [cell] بكسلات لون واحد. */
    private fun pixelated(original: Bitmap, cell: Int): Bitmap {
        val c = pixelCache
        if (c != null && c.first === original && c.second == cell && !c.third.isRecycled) return c.third
        val small = Bitmap.createScaledBitmap(
            original, max(1, ceil(original.width / cell.toDouble()).toInt()),
            max(1, ceil(original.height / cell.toDouble()).toInt()), false
        )
        pixelCache = Triple(original, cell, small)
        return small
    }

    /** يُسقِط مرجع النسخة المبكسلة المخزنة (تُنادى عند إغلاق جلسة التحرير).
     *  لا recycle صريح: التصدير قد يعمل على خيط آخر ويستعملها؛ يحرّرها جامع النفايات. */
    fun releaseCaches() {
        pixelCache = null
    }
}

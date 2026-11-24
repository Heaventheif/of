package org.openfilemanager.core.imageedit

import kotlin.math.cos
import kotlin.math.sin

/**
 * تحويل خطي ثنائي الأبعاد (Affine) خالص بلا أي اعتماد على أندرويد، حتى تُختبر
 * رياضيات المحرّر على JVM مباشرة. التمثيل بنظام المتجه-العمود:
 *
 *     x' = a*x + c*y + tx
 *     y' = b*x + d*y + ty
 *
 * نفس ترتيب قيم android.graphics.Matrix (MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y,
 * MSCALE_Y, MTRANS_Y) لذا تُحوَّل المصفوفة إلى أندرويد بلا أي إعادة ترتيب.
 */
data class Affine2(
    val a: Float, val b: Float,
    val c: Float, val d: Float,
    val tx: Float, val ty: Float
) {
    /** تطبيق التحويل على نقطة. */
    fun apply(x: Float, y: Float): Pair<Float, Float> =
        Pair(a * x + c * y + tx, b * x + d * y + ty)

    /** تطبيق التحويل على مسافة (بلا الإزاحة) — لقياس المقاييس فقط. */
    fun applyVector(x: Float, y: Float): Pair<Float, Float> = Pair(a * x + c * y, b * x + d * y)

    /**
     * تركيب: `this.then(next)` = طبّق this أولاً ثم next.
     * (next ∘ this بترميز المصفوفات.)
     */
    fun then(next: Affine2): Affine2 {
        // C = next * this  (3×3 مع الصف الأخير الثابت)
        val na = next.a * a + next.c * b
        val nb = next.b * a + next.d * b
        val nc = next.a * c + next.c * d
        val nd = next.b * c + next.d * d
        val ntx = next.a * tx + next.c * ty + next.tx
        val nty = next.b * tx + next.d * ty + next.ty
        return Affine2(na, nb, nc, nd, ntx, nty)
    }

    /** المحدّد — صفر يعني انهياراً (لا انعكاس ممكن). */
    val determinant: Float get() = a * d - c * b

    /** معامل المقياس الموحّد (متوسط المحورين). */
    val meanScale: Float
        get() {
            val sx = kotlin.math.hypot(a, b)
            val sy = kotlin.math.hypot(c, d)
            return (sx + sy) / 2f
        }

    /** المصفوفة العكسية، أو null إذا كان المحدّد صفراً. */
    fun inverted(): Affine2? {
        val det = determinant
        if (det == 0f) return null
        val ia = d / det
        val ic = -c / det
        val ib = -b / det
        val id = a / det
        return Affine2(
            ia, ib, ic, id,
            -(ia * tx + ic * ty),
            -(ib * tx + id * ty)
        )
    }

    companion object {
        val IDENTITY = Affine2(1f, 0f, 0f, 1f, 0f, 0f)

        fun translation(tx: Float, ty: Float) = Affine2(1f, 0f, 0f, 1f, tx, ty)

        fun scaling(sx: Float, sy: Float) = Affine2(sx, 0f, 0f, sy, 0f, 0f)

        /** تدوير بالدرجات. في نظام إحداثيات الصور (y لأسفل) الزاوية الموجبة باتجاه الساعة بصرياً. */
        fun rotationDegrees(degrees: Float): Affine2 {
            val r = Math.toRadians(degrees.toDouble())
            val c = cos(r).toFloat()
            val s = sin(r).toFloat()
            // [c -s; s c] — نفس دوران android.graphics.Matrix.setRotate.
            return Affine2(c, s, -s, c, 0f, 0f)
        }

        /** قلب أفقي حول محور رأسي يمر بمنتصف لوحة عرضها w: x' = w - x. */
        fun horizontalMirror(width: Float): Affine2 = Affine2(-1f, 0f, 0f, 1f, width, 0f)

        /** قلب رأسي حول محور أفقي يمر بمنتصف لوحة ارتفاعها h: y' = h - y. */
        fun verticalMirror(height: Float): Affine2 = Affine2(1f, 0f, 0f, -1f, 0f, height)

        /** تدوير ربع دورة باتجاه الساعة حول منتصف لوحة w×h: (x,y) -> (h-y, x). */
        fun quarterTurnCw(width: Float, height: Float): Affine2 =
            Affine2(0f, 1f, -1f, 0f, height, 0f)
    }
}
